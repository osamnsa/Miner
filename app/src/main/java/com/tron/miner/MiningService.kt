package com.tron.miner

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MiningService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "MinerChannel"
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(channelId, "Mining", NotificationManager.IMPORTANCE_LOW))

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tron Miner is Active")
            .setContentText("Mining TRX in background...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
