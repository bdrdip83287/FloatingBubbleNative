package com.dip83287.floatingbubble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
        private const val PREFS_NAME = "bubble_prefs"
        private const val KEY_OVERLAY_REQUESTED = "overlay_requested"
        private const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmergencyLog.logLifecycle("MainActivity", "onCreate")

        // ✅ Step 1: Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Requesting overlay permission")
                requestOverlayPermission()
                return
            }
        }

        // ✅ Step 2: Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                EmergencyLog.log("Requesting notification permission")
                requestNotificationPermission()
                return
            }
        }

        // ✅ Step 3: Everything granted — start service and finish quietly
        startBubbleService()
        finish()
    }

    private fun requestOverlayPermission() {
        try {
            // Try app-specific overlay settings screen first
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            EmergencyLog.log("Opened overlay permission screen")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "requestOverlayPermission")
            // Fallback to app details
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            } catch (_: Exception) {
                finish()
            }
        }
    }

    private fun requestNotificationPermission() {
        try {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        } catch (_: Exception) {
            // If this fails, just start service
            startBubbleService()
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            // Whether granted or denied — just start the service
            startBubbleService()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            handler.postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    Settings.canDrawOverlays(this)
                ) {
                    // Overlay granted — check notification permission next
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotificationPermission()
                    } else {
                        startBubbleService()
                        finish()
                    }
                } else {
                    // User didn't grant — just finish quietly
                    finish()
                }
            }, 400)
        }
    }

    /**
     * ✅ When MainActivity is brought to front from a notification tap,
     * we just ensure service is running (or do nothing if already running).
     * No UI is shown — activity finishes immediately.
     */
    override fun onResume() {
        super.onResume()
        EmergencyLog.logLifecycle("MainActivity", "onResume")

        // Only act if we didn't just launch (i.e., resumed from background)
        // If we're here and all permissions granted, just finish quietly.
        // Service will be started from onCreate if needed.
    }

    private fun startBubbleService() {
        try {
            val intent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            EmergencyLog.log("FloatingBubbleService start requested")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "startBubbleService")
        }
    }
}