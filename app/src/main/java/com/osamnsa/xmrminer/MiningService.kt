package com.osamnsa.xmrminer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Runs a real xmrig subprocess against the pool/wallet the user configured
 * in MainActivity. Does no hashing itself — it manages the process, keeps
 * the CPU awake while it runs, and surfaces live status via notification
 * and broadcast.
 */
class MiningService : Service() {

    companion object {
        const val ACTION_STOP = "com.osamnsa.xmrminer.action.STOP"
        const val ACTION_STATUS = "com.osamnsa.xmrminer.STATUS_UPDATE"
        const val EXTRA_POOL = "pool"
        const val EXTRA_WALLET = "wallet"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_DONATE = "donate"
        const val EXTRA_HASHRATE = "hashrate"
        const val EXTRA_STATUS_TEXT = "status_text"
        private const val CHANNEL_ID = "MinerChannel"
        private const val NOTIFICATION_ID = 1
        private val SPEED_PATTERN: Pattern = Pattern.compile("([0-9]+(\\.[0-9]+)?)\\s*H/s")
    }

    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mining status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMining("Stopped")
            return START_NOT_STICKY
        }

        val pool = intent?.getStringExtra(EXTRA_POOL)
        val wallet = intent?.getStringExtra(EXTRA_WALLET)
        val threads = intent?.getIntExtra(EXTRA_THREADS, 1) ?: 1
        val donate = intent?.getIntExtra(EXTRA_DONATE, 1) ?: 1

        if (pool.isNullOrBlank() || wallet.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification(buildNotification("Starting…", null))
        startEngine(pool, wallet, threads, donate)
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startEngine(pool: String, wallet: String, threads: Int, donate: Int) {
        val binary = File(applicationInfo.nativeLibraryDir, "libxmrig.so")
        if (!binary.exists() || !binary.canExecute()) {
            broadcastStatus("Engine missing", null)
            stopMining("Engine missing")
            return
        }

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xmrminer:mining")
        // Safety-net timeout so a wake lock never survives a crashed process indefinitely.
        wakeLock?.acquire(6 * 60 * 60 * 1000L)

        val command = listOf(
            binary.absolutePath,
            "-o", pool,
            "-u", wallet,
            "-p", "x",
            "-a", "rx/0",
            "-t", threads.coerceAtLeast(1).toString(),
            "--donate-level", donate.coerceIn(0, 100).toString(),
            "--no-color"
        )

        val proc = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            broadcastStatus("Failed to start engine: ${e.message}", null)
            stopMining("Failed to start")
            return
        }
        process = proc
        broadcastStatus("Mining", null)

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    val matcher = SPEED_PATTERN.matcher(text)
                    if (matcher.find()) {
                        val hashrate = "${matcher.group(1)} H/s"
                        notificationManager.notify(NOTIFICATION_ID, buildNotification("Mining active", hashrate))
                        broadcastStatus("Mining", hashrate)
                    }
                }
            } catch (e: Exception) {
                // Stream closes when the process is killed; nothing to act on.
            }
            broadcastStatus("Engine exited", null)
            stopMining("Engine exited")
        }.start()
    }

    private fun buildNotification(status: String, hashrate: String?): Notification {
        val stopIntent = Intent(this, MiningService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XMR CPU Miner — $status")
            .setContentText(hashrate ?: "Connecting to pool…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun broadcastStatus(statusText: String, hashrate: String?) {
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
        intent.putExtra(EXTRA_STATUS_TEXT, statusText)
        if (hashrate != null) intent.putExtra(EXTRA_HASHRATE, hashrate)
        sendBroadcast(intent)
    }

    private fun stopMining(reason: String) {
        process?.destroy()
        process = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        broadcastStatus(reason, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        process?.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
