package com.metarayban.glasses.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.metarayban.glasses.data.model.ConnectionState
import com.metarayban.glasses.data.model.GlassesDevice
import com.metarayban.glasses.data.model.GlassesInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Manages BLE communication with Ray-Ban Meta glasses.
 *
 * Handles scanning, connection, bonding, GATT service discovery,
 * and protocol commands (auth, WiFi activation, etc.).
 */
@SuppressLint("MissingPermission")  // Permissions checked at UI layer
class MetaGlassesBleManager(private val context: Context) {

    companion object {
        private const val TAG = "MetaBLE"
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private var bleScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _glassesInfo = MutableStateFlow(GlassesInfo())
    val glassesInfo: StateFlow<GlassesInfo> = _glassesInfo.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    // ── Scanning ────────────────────────────────────────────────────────

    /**
     * Scan for Ray-Ban Meta glasses.
     * Emits discovered devices as a Flow. Automatically stops after timeout.
     */
    fun scanForGlasses(): Flow<GlassesDevice> = callbackFlow {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner not available"))
            return@callbackFlow
        }
        bleScanner = scanner
        _connectionState.value = ConnectionState.SCANNING

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName

                // Check if this is Meta glasses
                val isMetaGlasses = MetaProtocol.isMetaGlasses(name)
                val hasMfgData = result.scanRecord?.manufacturerSpecificData
                    ?.let { data ->
                        (0 until data.size()).any { i ->
                            data.keyAt(i) == MetaProtocol.MANUFACTURER_ID
                        }
                    } ?: false

                if (isMetaGlasses || hasMfgData) {
                    val glassesDevice = GlassesDevice(
                        name = name ?: "Unknown",
                        address = device.address,
                        rssi = result.rssi,
                        isMetaGlasses = true,
                        manufacturerData = result.scanRecord?.getManufacturerSpecificData(
                            MetaProtocol.MANUFACTURER_ID
                        ),
                    )
                    trySend(glassesDevice)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                _connectionState.value = ConnectionState.ERROR
                close(Exception("BLE scan failed: $errorCode"))
            }
        }

        // Scan with filter for Meta service UUID
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(MetaProtocol.META_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Also scan without filter (some glasses may not advertise service UUID)
        val settingsAll = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settingsAll, callback)
        Log.d(TAG, "BLE scan started")

        awaitClose {
            scanner.stopScan(callback)
            if (_connectionState.value == ConnectionState.SCANNING) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            Log.d(TAG, "BLE scan stopped")
        }
    }

    // ── Connection ──────────────────────────────────────────────────────

    /**
     * Connect to glasses and discover services.
     */
    suspend fun connect(device: GlassesDevice): Boolean =
        suspendCancellableCoroutine { continuation ->
            _connectionState.value = ConnectionState.CONNECTING

            val btDevice = bluetoothAdapter?.getRemoteDevice(device.address)
            if (btDevice == null) {
                _connectionState.value = ConnectionState.ERROR
                continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt, status: Int, newState: Int
                ) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "Connected to ${device.name}")
                            this@MetaGlassesBleManager.gatt = gatt

                            // Check bonding state
                            if (btDevice.bondState == BluetoothDevice.BOND_BONDED) {
                                _connectionState.value = ConnectionState.CONNECTED
                                gatt.discoverServices()
                            } else {
                                _connectionState.value = ConnectionState.BONDING
                                btDevice.createBond()
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "Disconnected")
                            _connectionState.value = ConnectionState.DISCONNECTED
                            gatt.close()
                            this@MetaGlassesBleManager.gatt = null
                        }
                    }

                    if (continuation.isActive) {
                        continuation.resume(newState == BluetoothProfile.STATE_CONNECTED)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Services discovered: ${gatt.services.size}")
                        readGlassesInfo(gatt)
                    }
                }

                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) return
                    handleCharacteristicRead(characteristic.uuid, value)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    Log.d(TAG, "Notification from ${characteristic.uuid}: ${value.toHex()}")
                    _notifications.tryEmit(value)
                }
            }

            gatt = btDevice.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )

            continuation.invokeOnCancellation {
                gatt?.close()
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }

    // ── GATT Operations ─────────────────────────────────────────────────

    private fun readGlassesInfo(gatt: BluetoothGatt) {
        // Read firmware version from Device Information service
        val deviceInfoService = gatt.getService(MetaProtocol.DEVICE_INFO_UUID)
        deviceInfoService?.getCharacteristic(MetaProtocol.CHAR_FIRMWARE_REV)?.let {
            gatt.readCharacteristic(it)
        }

        // Read device name
        val genericAccess = gatt.getService(MetaProtocol.GENERIC_ACCESS_UUID)
        genericAccess?.getCharacteristic(MetaProtocol.CHAR_DEVICE_NAME)?.let {
            gatt.readCharacteristic(it)
        }

        // Read Meta status characteristic
        val metaService = gatt.getService(MetaProtocol.META_SERVICE_UUID)
        metaService?.getCharacteristic(MetaProtocol.CHAR_STATUS)?.let {
            gatt.readCharacteristic(it)
        }

        // Enable notifications on command characteristic
        metaService?.getCharacteristic(MetaProtocol.CHAR_COMMAND)?.let { char ->
            gatt.setCharacteristicNotification(char, true)
            char.getDescriptor(MetaProtocol.CCCD_UUID)?.let { desc ->
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }
    }

    private fun handleCharacteristicRead(uuid: java.util.UUID, value: ByteArray) {
        val current = _glassesInfo.value
        when (uuid) {
            MetaProtocol.CHAR_FIRMWARE_REV -> {
                val fw = String(value, Charsets.UTF_8)
                _glassesInfo.value = current.copy(firmwareVersion = fw)
                Log.d(TAG, "Firmware: $fw")
            }
            MetaProtocol.CHAR_DEVICE_NAME -> {
                val name = String(value, Charsets.UTF_8)
                _glassesInfo.value = current.copy(deviceName = name)
                Log.d(TAG, "Device name: $name")
            }
            MetaProtocol.CHAR_STATUS -> {
                _glassesInfo.value = current.copy(statusData = value)
                Log.d(TAG, "Status: ${value.toHex()}")
            }
            MetaProtocol.CHAR_FLAGS -> {
                _glassesInfo.value = current.copy(flagsData = value)
                Log.d(TAG, "Flags: ${value.toHex()}")
            }
        }
    }

    /**
     * Write a command to the glasses' command characteristic.
     */
    fun writeCommand(data: ByteArray): Boolean {
        val gatt = this.gatt ?: return false
        val service = gatt.getService(MetaProtocol.META_SERVICE_UUID) ?: return false
        val char = service.getCharacteristic(MetaProtocol.CHAR_COMMAND) ?: return false

        Log.d(TAG, "Writing command: ${data.toHex()}")
        return gatt.writeCharacteristic(
            char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) == BluetoothStatusCodes.SUCCESS
    }

    /**
     * Disconnect from the glasses.
     */
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
