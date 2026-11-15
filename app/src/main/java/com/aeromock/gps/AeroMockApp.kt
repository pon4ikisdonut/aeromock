package com.aeromock.gps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.osmdroid.config.Configuration

class AeroMockApp : Application() {
    companion object {
        const val CHANNEL_ID = "aeromock_spoof_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Spoofing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active mock location status and quick controls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
