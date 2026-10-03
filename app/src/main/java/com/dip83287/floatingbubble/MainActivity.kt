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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST = 1002
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmergencyLog.logLifecycle("MainActivity", "onCreate")

        // ✅ Step 1: Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay permission not granted → opening settings")
                openOverlaySettings()
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
                EmergencyLog.log("Notification permission not granted → requesting")
                // ✅ IMPORTANT: Do NOT finish() here. Wait for the result callback.
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
                return
            }
        }

        // ✅ Step 3: All permissions granted → start service & finish
        startBubbleService()
        // Give service a moment to call startForeground() before finish
        handler.postDelayed({
            finish()
        }, 300)
    }

    /**
     * ✅ Open the app's "Display over other apps" settings page.
     * Uses ACTION_APPLICATION_DETAILS_SETTINGS — works on ALL devices.
     */
    private fun openOverlaySettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            Toast.makeText(
                this,
                "🔵 Go to 'Display over other apps' and enable permission",
                Toast.LENGTH_LONG
            ).show()
            EmergencyLog.log("Opened app details settings for overlay permission")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "openOverlaySettings")
            Toast.makeText(
                this,
                "Please manually enable overlay permission from Settings",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    /**
     * ✅ Handle notification permission result.
     * IMPORTANT: Do NOT finish before this. Start service here.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            EmergencyLog.log("Notification permission result received")
            // Whether granted or denied — start service
            startBubbleService()
            // Give service time to call startForeground() before finishing
            handler.postDelayed({
                finish()
            }, 300)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            handler.postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        // ✅ Overlay granted — check notification
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            // Request permission, wait for result
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_REQUEST
                            )
                        } else {
                            // No notification permission needed — start service now
                            startBubbleService()
                            handler.postDelayed({ finish() }, 300)
                        }
                    } else {
                        Toast.makeText(
                            this,
                            "❌ Please enable 'Display over other apps' permission",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                } else {
                    finish()
                }
            }, 500)
        }
    }

    /**
     * ✅ MainActivity brought to front from notification tap.
     * Start service — if already running, service does nothing.
     */
    override fun onResume() {
        super.onResume()
        EmergencyLog.logLifecycle("MainActivity", "onResume")

        // Only handle notification-launch case here
        val isNotificationLaunch = intent?.getBooleanExtra(
            "from_notification", false
        ) ?: false

        if (isNotificationLaunch &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(this)
        ) {
            EmergencyLog.log("Launched from notification tap")
            startBubbleService()
            handler.postDelayed({
                finish()
            }, 300)
        }
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