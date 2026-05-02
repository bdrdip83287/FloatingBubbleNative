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
    private lateinit var logManager: LogManager
    private lateinit var flowTracker: FlowTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logManager = LogManager.getInstance(this)
        flowTracker = FlowTracker(logManager)
        CrashHandler.init(this)

        flowTracker.trackEnter("MainActivity", "onCreate")
        BaseLifecycleTracker.attach(this, logManager)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                flowTracker.trackEvent("MainActivity", "Requesting permission")
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 100)
            } else {
                startBubbleService()
                finish()
            }
        } else {
            startBubbleService()
            finish()
        }
        flowTracker.trackExit("MainActivity", "onCreate")
    }

    private fun startBubbleService() {
        try {
            val intent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            flowTracker.trackEvent("MainActivity", "Bubble service started")
        } catch (e: Exception) {
            flowTracker.trackError("MainActivity", "Failed to start service", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startBubbleService()
            finish()
        }
    }
}
