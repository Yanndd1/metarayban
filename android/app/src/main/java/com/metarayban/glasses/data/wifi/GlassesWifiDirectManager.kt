package com.metarayban.glasses.data.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.metarayban.glasses.data.ble.MetaProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Manages WiFi Direct (P2P) connection to the glasses.
 *
 * Reverse-engineered from Meta View APK (com.facebook.stella):
 * - Phone creates a WiFi Direct P2P group as Group Owner
 * - SSID pattern: DIRECT-FB-*
 * - Glasses connect as a WiFi Direct client
 * - Media transfer over TCP port 20203 on 192.168.49.x subnet
 *
 * Key classes from APK:
 * - WifiDirectStrategy (createGroup, requestConnectionInfo)
 * - WifiDirectGroupOwnerImpl (manages the P2P group)
 * - WifiOwnerManager (group owner lifecycle)
 * - WifiPeer (peer connection management)
 * - WifiLease (connection lifecycle/timeout)
 *
 * Uses Android WifiP2pManager API (no root required).
 */
class GlassesWifiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "MetaWiFiDirect"
    }

    private val p2pManager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    private val channel: WifiP2pManager.Channel =
        p2pManager.initialize(context, Looper.getMainLooper(), null)

    private val _connectionState = MutableStateFlow(WifiDirectState.IDLE)
    val connectionState: StateFlow<WifiDirectState> = _connectionState.asStateFlow()

    private val _peerIpAddress = MutableStateFlow<String?>(null)
    val peerIpAddress: StateFlow<String?> = _peerIpAddress.asStateFlow()

    private var broadcastReceiver: BroadcastReceiver? = null

    /**
     * Create a WiFi Direct P2P group (phone becomes Group Owner).
     *
     * This mirrors WifiDirectStrategy.createGroup() from the Meta View APK.
     * The glasses will detect and connect to this group.
     *
     * @return true if group was created successfully
     */
    @SuppressLint("MissingPermission")
    suspend fun createGroup(): Boolean = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "Creating WiFi Direct P2P group...")
        _connectionState.value = WifiDirectState.CREATING_GROUP

        p2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "createGroup onSuccess")
                _connectionState.value = WifiDirectState.GROUP_CREATED
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.ERROR -> "INTERNAL_ERROR"
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.BUSY -> "BUSY"
                    else -> "UNKNOWN($reason)"
                }
                Log.e(TAG, "createGroup failed: reason=$reasonStr")
                _connectionState.value = WifiDirectState.ERROR
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        })

        continuation.invokeOnCancellation {
            removeGroup()
        }
    }

    /**
     * Request connection info for the P2P group.
     *
     * This mirrors WifiDirectStrategy.requestConnectionInfo() from the Meta View APK.
     * Returns the P2P connection info including group owner address.
     */
    @SuppressLint("MissingPermission")
    suspend fun requestConnectionInfo(): WifiP2pInfo? = suspendCancellableCoroutine { cont ->
        p2pManager.requestConnectionInfo(channel) { info ->
            if (info != null) {
                Log.d(TAG, "P2P Info: isGroupOwner=${info.groupFormed}, " +
                        "isGO=${info.isGroupOwner}, " +
                        "ownerAddress=${info.groupOwnerAddress}")
            }
            if (cont.isActive) {
                cont.resume(info)
            }
        }
    }

    /**
     * Request group info to get peer devices.
     *
     * Used to detect when glasses have connected to our P2P group.
     */
    @SuppressLint("MissingPermission")
    suspend fun requestGroupInfo(): WifiP2pGroup? = suspendCancellableCoroutine { cont ->
        p2pManager.requestGroupInfo(channel) { group ->
            if (group != null) {
                Log.d(TAG, "P2P Group: ssid=${group.networkName}, " +
                        "clients=${group.clientList?.size ?: 0}")
                group.clientList?.forEach { client ->
                    Log.d(TAG, "  Peer: ${client.deviceName} (${client.deviceAddress})")
                }
            }
            if (cont.isActive) {
                cont.resume(group)
            }
        }
    }

    /**
     * Register a broadcast receiver to monitor WiFi Direct state changes.
     *
     * This mirrors WiFiDirectBroadcastReceiver from the Meta View APK
     * (com.facebook.wearable.common.wifidirect.WiFiDirectBroadcastReceiver).
     *
     * @return Flow of WifiDirectEvent
     */
    fun observeWifiDirectEvents(): Flow<WifiDirectEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Log.d(TAG, "P2P state changed: enabled=$enabled")
                        trySend(WifiDirectEvent.StateChanged(enabled))
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Log.d(TAG, "P2P peers changed")
                        trySend(WifiDirectEvent.PeersChanged)
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                            WifiP2pManager.EXTRA_NETWORK_INFO
                        )
                        val p2pInfo = intent.getParcelableExtra<WifiP2pInfo>(
                            WifiP2pManager.EXTRA_WIFI_P2P_INFO
                        )

                        val connected = networkInfo?.isConnected == true
                        Log.d(TAG, "P2P connection changed: connected=$connected, " +
                                "groupFormed=${p2pInfo?.groupFormed}, " +
                                "isGO=${p2pInfo?.isGroupOwner}")

                        if (connected && p2pInfo?.groupFormed == true) {
                            _connectionState.value = WifiDirectState.PEER_CONNECTED
                            // The glasses' IP can be determined from the group info
                            // Typically 192.168.49.x where the GO is 192.168.49.1
                            _peerIpAddress.value = MetaProtocol.WIFI_DIRECT_GLASSES_IP
                        } else {
                            _connectionState.value = WifiDirectState.GROUP_CREATED
                            _peerIpAddress.value = null
                        }

                        trySend(WifiDirectEvent.ConnectionChanged(connected, p2pInfo))
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        Log.d(TAG, "P2P this device changed")
                        trySend(WifiDirectEvent.ThisDeviceChanged)
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        context.registerReceiver(receiver, intentFilter)
        broadcastReceiver = receiver

        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) { }
            broadcastReceiver = null
        }
    }

    /**
     * Remove the P2P group and disconnect.
     */
    @SuppressLint("MissingPermission")
    fun removeGroup() {
        p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P group removed")
                _connectionState.value = WifiDirectState.IDLE
                _peerIpAddress.value = null
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to remove P2P group: reason=$reason")
                _connectionState.value = WifiDirectState.IDLE
                _peerIpAddress.value = null
            }
        })
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        broadcastReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) { }
        }
        removeGroup()
    }
}

/**
 * WiFi Direct connection states.
 */
enum class WifiDirectState {
    IDLE,
    CREATING_GROUP,
    GROUP_CREATED,       // P2P group created, waiting for glasses to connect
    PEER_CONNECTED,      // Glasses connected as P2P client
    TRANSFERRING,        // Media transfer in progress
    ERROR
}

/**
 * WiFi Direct events from broadcast receiver.
 */
sealed class WifiDirectEvent {
    data class StateChanged(val enabled: Boolean) : WifiDirectEvent()
    data object PeersChanged : WifiDirectEvent()
    data class ConnectionChanged(
        val connected: Boolean,
        val p2pInfo: WifiP2pInfo?
    ) : WifiDirectEvent()
    data object ThisDeviceChanged : WifiDirectEvent()
}
