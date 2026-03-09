package com.metarayban.glasses

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MetaRayBanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_TRANSFER,
            getString(R.string.notification_channel_transfer),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notifications for media transfer progress"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_TRANSFER = "media_transfer"
    }
}
