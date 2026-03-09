package com.metarayban.glasses.presentation.screens.transfer

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metarayban.glasses.data.ble.MetaGlassesBleManager
import com.metarayban.glasses.data.model.TransferState
import com.metarayban.glasses.data.wifi.GlassesWifiManager
import com.metarayban.glasses.data.wifi.MediaTransferClient
import com.metarayban.glasses.service.MediaTransferService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: MetaGlassesBleManager,
    private val wifiManager: GlassesWifiManager,
    private val transferClient: MediaTransferClient,
) : ViewModel() {

    val connectionState = bleManager.connectionState
    val transferState: StateFlow<TransferState> = transferClient.transferState

    /**
     * Full transfer flow:
     * 1. Send BLE command to activate WiFi hotspot on glasses
     * 2. Read WiFi credentials from BLE notification
     * 3. Connect to glasses' WiFi using WifiNetworkSpecifier
     * 4. Download all media via HTTP
     * 5. Disconnect WiFi and restore normal networking
     */
    fun startTransfer() {
        viewModelScope.launch {
            try {
                // Step 1: Send WiFi activation command via BLE
                // TODO: Replace with actual command after protocol analysis
                // bleManager.writeCommand(MetaProtocol.CMD_ACTIVATE_WIFI)

                // Step 2: Wait for WiFi credentials from BLE notification
                // TODO: Parse SSID and password from notification data
                // val (ssid, password) = parseWifiCredentials(notification)

                // Step 3: Connect to glasses' WiFi
                // val connected = wifiManager.connectToGlasses(ssid, password)
                // if (!connected) { error... }

                // Step 4: Start transfer via foreground service
                // val intent = Intent(context, MediaTransferService::class.java).apply {
                //     putExtra(MediaTransferService.EXTRA_GATEWAY_IP, gatewayIp)
                // }
                // context.startForegroundService(intent)

                // For now, log that this is pending protocol analysis
                android.util.Log.d(
                    "TransferVM",
                    "Transfer flow pending protocol reverse-engineering"
                )

            } catch (e: Exception) {
                android.util.Log.e("TransferVM", "Transfer failed", e)
            }
        }
    }

    override fun onCleared() {
        wifiManager.disconnect()
        super.onCleared()
    }
}
