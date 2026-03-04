
package com.tron.miner

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MiningService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create a notification so the user (and Android) knows we're mining
        val channelId = "MiningChannel"
        val channel = NotificationChannel(channelId, "Tron Miner Active", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TronMobile is Mining")
            .setContentText("Utilizing 2-8 cores... tap to open.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        // THE MIRACLE: This line prevents Android from killing your app
        startForeground(1, notification)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
