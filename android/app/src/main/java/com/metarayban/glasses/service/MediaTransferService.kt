package com.metarayban.glasses.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.metarayban.glasses.MetaRayBanApp
import com.metarayban.glasses.R
import com.metarayban.glasses.data.wifi.MediaTransferClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Foreground service for long-running media transfers.
 * Keeps the process alive while downloading files from the glasses' WiFi hotspot.
 */
@AndroidEntryPoint
class MediaTransferService : Service() {

    companion object {
        private const val TAG = "TransferService"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_GATEWAY_IP = "gateway_ip"
    }

    @Inject lateinit var transferClient: MediaTransferClient

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gatewayIp = intent?.getStringExtra(EXTRA_GATEWAY_IP) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            createNotification("Connexion aux lunettes..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        scope.launch {
            try {
                val count = transferClient.downloadAllMedia(gatewayIp)
                Log.d(TAG, "Transfer complete: $count files downloaded")
            } catch (e: Exception) {
                Log.e(TAG, "Transfer failed", e)
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, MetaRayBanApp.CHANNEL_TRANSFER)
            .setContentTitle(getString(R.string.notification_transfer_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
    }
}
