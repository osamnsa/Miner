package com.tron.miner

import android.os.Bundle
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.content.Intent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val webView = WebView(this)
        setContentView(webView)
        
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(this, "Android")
        
        // This will load the HTML file we'll put in the assets folder next
        webView.loadUrl("file:///android_asset/index.html")
    }

    @JavascriptInterface
    fun startMiningService() {
        val intent = Intent(this, MiningService::class.java)
        startForegroundService(intent)
    }
}
