package com.osamnsa.xmrminer

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Brand splash — shown once at cold start, then hands off to MainActivity.
 * A dedicated activity (rather than the OS-level SplashScreen API) so the
 * brand name and tagline are actually visible, not just an icon flash.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val DISPLAY_MS = 1400L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }, DISPLAY_MS)
    }
}
