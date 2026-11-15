package com.aeromock.gps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.osmdroid.config.Configuration
import java.io.File

class AeroMockApp : Application() {
    companion object {
        const val CHANNEL_ID = "aeromock_spoof_channel"
    }

    override fun onCreate() {
        super.onCreate()
        val osmdroidConfig = Configuration.getInstance()
        osmdroidConfig.userAgentValue = "$packageName/1.1"
        osmdroidConfig.osmdroidBasePath = File(filesDir, "osmdroid").apply { mkdirs() }
        osmdroidConfig.osmdroidTileCache = File(cacheDir, "osmdroid/tiles").apply { mkdirs() }
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
