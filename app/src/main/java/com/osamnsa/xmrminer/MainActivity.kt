package com.osamnsa.xmrminer

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var poolInput: EditText
    private lateinit var walletInput: EditText
    private lateinit var donateInput: EditText
    private lateinit var threadsSeek: SeekBar
    private lateinit var threadsLabel: TextView
    private lateinit var startStopButton: Button
    private lateinit var statusText: TextView
    private lateinit var hashrateText: TextView
    private lateinit var sharesText: TextView
    private lateinit var adView: AdView

    private var mining = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Mining still works without this; the status notification just won't show. */ }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val status = intent.getStringExtra(MiningService.EXTRA_STATUS_TEXT)
            val hashrate = intent.getStringExtra(MiningService.EXTRA_HASHRATE)
            val accepted = intent.getIntExtra(MiningService.EXTRA_ACCEPTED, -1)
            val rejected = intent.getIntExtra(MiningService.EXTRA_REJECTED, -1)

            if (status != null) statusText.text = status
            if (hashrate != null) hashrateText.text = hashrate
            if (accepted >= 0 && rejected >= 0) {
                sharesText.text = getString(R.string.shares_format, accepted, rejected)
            }

            val stoppedForGood = status == "Stopped" || status == "Engine exited" ||
                status == "Engine missing" || status?.startsWith("Failed") == true
            val pausedForHeat = status?.startsWith("Paused") == true

            if (stoppedForGood) {
                mining = false
                startStopButton.text = getString(R.string.start_mining)
                hashrateText.text = "0.00 H/s"
            } else if (pausedForHeat) {
                // Service is still alive and will resume on its own once the
                // device cools down; keep the Stop button available.
                hashrateText.text = "0.00 H/s"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("miner_prefs", MODE_PRIVATE)

        poolInput = findViewById(R.id.poolInput)
        walletInput = findViewById(R.id.walletInput)
        donateInput = findViewById(R.id.donateInput)
        threadsSeek = findViewById(R.id.threadsSeek)
        threadsLabel = findViewById(R.id.threadsLabel)
        startStopButton = findViewById(R.id.startStopButton)
        statusText = findViewById(R.id.statusText)
        hashrateText = findViewById(R.id.hashrateText)
        sharesText = findViewById(R.id.sharesText)
        adView = findViewById(R.id.adView)

        MobileAds.initialize(this) {}
        adView.loadAd(AdRequest.Builder().build())

        val maxThreads = Runtime.getRuntime().availableProcessors()
        threadsSeek.max = (maxThreads - 1).coerceAtLeast(0)
        threadsSeek.progress = (prefs.getInt("threads", 1) - 1).coerceIn(0, threadsSeek.max)
        threadsLabel.text = (threadsSeek.progress + 1).toString()
        threadsSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                threadsLabel.text = (progress + 1).toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        poolInput.setText(prefs.getString("pool", ""))
        walletInput.setText(prefs.getString("wallet", ""))
        donateInput.setText(prefs.getInt("donate", 1).toString())

        // Reflect a session the service may already be running (e.g. we're
        // reopening the app while mining continues in the background).
        if (prefs.getBoolean("should_run", false)) {
            mining = true
            startStopButton.text = getString(R.string.stop_mining)
            statusText.text = getString(R.string.status_resuming)
        }

        startStopButton.setOnClickListener {
            if (mining) stopMining() else confirmAndStartMining()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MiningService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    override fun onResume() {
        super.onResume()
        adView.resume()
    }

    override fun onPause() {
        adView.pause()
        super.onPause()
    }

    override fun onDestroy() {
        adView.destroy()
        super.onDestroy()
    }

    private fun confirmAndStartMining() {
        val pool = poolInput.text.toString().trim()
        val wallet = walletInput.text.toString().trim()
        val donate = donateInput.text.toString().toIntOrNull() ?: 1
        val threads = threadsSeek.progress + 1

        if (pool.isEmpty() || wallet.isEmpty()) {
            statusText.text = "Enter a pool address and your wallet address first"
            return
        }

        val binary = File(applicationInfo.nativeLibraryDir, "libxmrig.so")
        if (!binary.exists() || !binary.canExecute()) {
            AlertDialog.Builder(this)
                .setTitle("Mining engine not installed")
                .setMessage(
                    "This app doesn't ship a compiled mining engine. Build or obtain an " +
                        "official xmrig binary for your device's ABI and place it at " +
                        "app/src/main/jniLibs/<abi>/libxmrig.so before packaging the APK. " +
                        "See jniLibs/README.md in the project for instructions."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        prefs.edit()
            .putString("pool", pool)
            .putString("wallet", wallet)
            .putInt("threads", threads)
            .putInt("donate", donate)
            .apply()

        AlertDialog.Builder(this)
            .setTitle("Before you start")
            .setMessage(getString(R.string.disclosure_full))
            .setPositiveButton("Start mining") { _, _ -> startMining(pool, wallet, threads, donate) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMining(pool: String, wallet: String, threads: Int, donate: Int) {
        val intent = Intent(this, MiningService::class.java).apply {
            putExtra(MiningService.EXTRA_POOL, pool)
            putExtra(MiningService.EXTRA_WALLET, wallet)
            putExtra(MiningService.EXTRA_THREADS, threads)
            putExtra(MiningService.EXTRA_DONATE, donate)
        }
        startForegroundService(intent)
        mining = true
        startStopButton.text = getString(R.string.stop_mining)
        statusText.text = "Starting…"
        sharesText.text = getString(R.string.shares_format, 0, 0)
    }

    private fun stopMining() {
        val intent = Intent(this, MiningService::class.java).setAction(MiningService.ACTION_STOP)
        startService(intent)
        mining = false
        startStopButton.text = getString(R.string.start_mining)
    }
}
