package com.osamnsa.xmrminer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.BatteryManager
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
 * the CPU awake while it runs, surfaces live status (hashrate, accepted /
 * rejected shares) via notification and broadcast, and pauses the process
 * (not the whole service) when the device gets too hot.
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
        const val EXTRA_ACCEPTED = "accepted"
        const val EXTRA_REJECTED = "rejected"
        private const val CHANNEL_ID = "MinerChannel"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "miner_prefs"
        private const val PREF_SHOULD_RUN = "should_run"

        private val SPEED_PATTERN: Pattern = Pattern.compile("([0-9]+(\\.[0-9]+)?)\\s*H/s")
        // xmrig logs every share result as "accepted (N/M) ..." or
        // "rejected (N/M) ..." where N/M are the running accepted/rejected
        // counts (src/net/Network.cpp) — either line gives us both totals.
        private val SHARE_PATTERN: Pattern = Pattern.compile("(accepted|rejected)\\s*\\((\\d+)/(\\d+)\\)")
        private val ANSI_PATTERN: Pattern = Pattern.compile("\\[[0-9;]*m")

        // Battery-temperature fallback thresholds (tenths of a degree Celsius)
        // for API < 29, which lacks PowerManager's thermal-status API.
        private const val BATTERY_PAUSE_TENTHS = 420   // 42.0 C
        private const val BATTERY_RESUME_TENTHS = 380  // 38.0 C
    }

    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var prefs: SharedPreferences

    private var pool: String? = null
    private var wallet: String? = null
    private var threads: Int = 1
    private var donate: Int = 1

    private var thermalPaused = false
    private var lastAccepted = 0
    private var lastRejected = 0
    private var lastHashrate: String? = null

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: return
            if (tenths == Int.MIN_VALUE) return
            if (!thermalPaused && tenths >= BATTERY_PAUSE_TENTHS) {
                pauseForHeat()
            } else if (thermalPaused && tenths <= BATTERY_RESUME_TENTHS) {
                resumeFromHeat()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mining status", NotificationManager.IMPORTANCE_LOW)
        )
        registerThermalMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean(PREF_SHOULD_RUN, false).apply()
            stopMining("Stopped")
            return START_NOT_STICKY
        }

        val newPool = intent?.getStringExtra(EXTRA_POOL)
        val newWallet = intent?.getStringExtra(EXTRA_WALLET)

        if (newPool.isNullOrBlank() || newWallet.isNullOrBlank()) {
            // No config in this intent — either a stray start or the system
            // restarting us (START_STICKY redelivers with a null intent)
            // after killing the process. Only resume if the user hadn't
            // explicitly stopped mining before that happened.
            if (!prefs.getBoolean(PREF_SHOULD_RUN, false)) {
                stopSelf()
                return START_NOT_STICKY
            }
            pool = prefs.getString(EXTRA_POOL, null)
            wallet = prefs.getString(EXTRA_WALLET, null)
            threads = prefs.getInt(EXTRA_THREADS, 1)
            donate = prefs.getInt(EXTRA_DONATE, 1)
        } else {
            pool = newPool
            wallet = newWallet
            threads = intent?.getIntExtra(EXTRA_THREADS, 1) ?: 1
            donate = intent?.getIntExtra(EXTRA_DONATE, 1) ?: 1
            prefs.edit()
                .putBoolean(PREF_SHOULD_RUN, true)
                .putString(EXTRA_POOL, pool)
                .putString(EXTRA_WALLET, wallet)
                .putInt(EXTRA_THREADS, threads)
                .putInt(EXTRA_DONATE, donate)
                .apply()
        }

        if (pool.isNullOrBlank() || wallet.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification(buildNotification("Starting…", null))
        launchEngine()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately do nothing: swiping the app away from recents
        // shouldn't stop a foreground mining session the user explicitly
        // started. The persistent notification remains the "still running"
        // signal, same as any other foreground service.
    }

    private fun registerThermalMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = getSystemService(PowerManager::class.java)
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                if (!thermalPaused && status >= PowerManager.THERMAL_STATUS_MODERATE) {
                    pauseForHeat()
                } else if (thermalPaused && status <= PowerManager.THERMAL_STATUS_LIGHT) {
                    resumeFromHeat()
                }
            }
            powerManager.addThermalStatusListener(mainExecutor, listener)
            thermalListener = listener
        } else {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    private fun unregisterThermalMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let {
                getSystemService(PowerManager::class.java).removeThermalStatusListener(it)
            }
            thermalListener = null
        } else {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (e: IllegalArgumentException) {
                // Wasn't registered (e.g. destroyed before onCreate finished); ignore.
            }
        }
    }

    private fun pauseForHeat() {
        if (thermalPaused || process == null) return
        thermalPaused = true
        process?.destroy()
        process = null
        broadcastStatus("Paused — device is hot", null)
        notificationManager.notify(NOTIFICATION_ID, buildNotification("Paused (too hot)", null))
    }

    private fun resumeFromHeat() {
        if (!thermalPaused) return
        thermalPaused = false
        launchEngine()
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

    private fun launchEngine() {
        val currentPool = pool ?: return
        val currentWallet = wallet ?: return

        val binary = File(applicationInfo.nativeLibraryDir, "libxmrig.so")
        if (!binary.exists() || !binary.canExecute()) {
            broadcastStatus("Engine missing", null)
            stopMining("Engine missing")
            return
        }

        if (wakeLock == null) {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xmrminer:mining")
        }
        // Safety-net timeout so a wake lock never survives a crashed process indefinitely.
        wakeLock?.let { if (!it.isHeld) it.acquire(6 * 60 * 60 * 1000L) }

        val command = listOf(
            binary.absolutePath,
            "-o", currentPool,
            "-u", currentWallet,
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
        broadcastStatus("Mining", lastHashrate)

        Thread {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val raw = line ?: continue
                    val text = ANSI_PATTERN.matcher(raw).replaceAll("")

                    val speedMatcher = SPEED_PATTERN.matcher(text)
                    if (speedMatcher.find()) {
                        lastHashrate = "${speedMatcher.group(1)} H/s"
                        notificationManager.notify(NOTIFICATION_ID, buildNotification("Mining active", lastHashrate))
                        broadcastStatus("Mining", lastHashrate)
                    }

                    val shareMatcher = SHARE_PATTERN.matcher(text)
                    if (shareMatcher.find()) {
                        lastAccepted = shareMatcher.group(2)?.toIntOrNull() ?: lastAccepted
                        lastRejected = shareMatcher.group(3)?.toIntOrNull() ?: lastRejected
                        broadcastStatus("Mining", lastHashrate)
                    }
                }
            } catch (e: Exception) {
                // Stream closes when the process is killed (including our own
                // thermal pause); nothing to act on.
            }
            // Only treat this as a real stop if we didn't kill it ourselves for heat.
            if (!thermalPaused) {
                broadcastStatus("Engine exited", null)
                stopMining("Engine exited")
            }
        }.start()
    }

    private fun buildNotification(status: String, hashrate: String?): Notification {
        val stopIntent = Intent(this, MiningService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val shareText = if (lastAccepted + lastRejected > 0) " • ✓$lastAccepted ✗$lastRejected" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XMR CPU Miner — $status")
            .setContentText((hashrate ?: "Connecting to pool…") + shareText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun broadcastStatus(statusText: String, hashrate: String?) {
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
        intent.putExtra(EXTRA_STATUS_TEXT, statusText)
        if (hashrate != null) intent.putExtra(EXTRA_HASHRATE, hashrate)
        intent.putExtra(EXTRA_ACCEPTED, lastAccepted)
        intent.putExtra(EXTRA_REJECTED, lastRejected)
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
        unregisterThermalMonitor()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
