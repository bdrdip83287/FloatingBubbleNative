package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.*

class MainActivity : AppCompatActivity() {
    private lateinit var lm: LogManager
    private lateinit var ft: FlowTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lm = LogManager.getInstance(this)
        ft = FlowTracker(lm)
        CrashHandler.init(this)

        ft.trackEnter("MainActivity", "onCreate")
        BaseLifecycleTracker.attach(this, lm)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                ft.trackEvent("MainActivity", "Requesting permission")
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 100)
            } else {
                startBubbleService()
                finish()
            }
        } else {
            startBubbleService()
            finish()
        }
        ft.trackExit("MainActivity", "onCreate")
    }

    private fun startBubbleService() {
        try {
            startForegroundService(Intent(this, FloatingBubbleService::class.java))
            ft.trackEvent("MainActivity", "Bubble service started")
        } catch (e: Exception) {
            ft.trackError("MainActivity", "Failed to start service", e)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startBubbleService()
            finish()
        }
    }
}
