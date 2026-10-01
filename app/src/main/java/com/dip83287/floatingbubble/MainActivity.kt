package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmergencyLog.logLifecycle("MainActivity", "onCreate")

        // ✅ Only overlay permission needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay permission already granted")
                startBubbleService()
                finish()
            } else {
                EmergencyLog.log("Opening overlay settings page")
                openOverlaySettings()
            }
        } else {
            startBubbleService()
            finish()
        }
    }

    /**
     * ✅ NEW: Called when activity comes to foreground.
     * This handles the case where user tapped the notification while the
     * activity was already in background (launchMode="singleTask").
     *
     * Also handles returning from overlay settings after granting permission.
     */
    override fun onResume() {
        super.onResume()
        EmergencyLog.logLifecycle("MainActivity", "onResume")

        // ✅ If overlay permission is granted, start service and finish
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                // Check if this onResume is due to notification tap
                // (singleTask mode — MainActivity already running)
                val isNotificationLaunch = intent?.getBooleanExtra(
                    "from_notification", false
                ) ?: false

                if (isNotificationLaunch) {
                    EmergencyLog.log("Launched from notification tap")
                    startBubbleService()
                    finish()
                    return
                }

                // Also handle: user came back from settings after granting permission
                // We check if service is running — if not, start it
                if (!isServiceRunning()) {
                    EmergencyLog.log("Service not running, starting it")
                    startBubbleService()
                    finish()
                }
            }
        } else {
            // Pre-Marshmallow
            if (!isServiceRunning()) {
                startBubbleService()
                finish()
            }
        }
    }

    /**
     * ✅ Check if FloatingBubbleService is currently running.
     */
    private fun isServiceRunning(): Boolean {
        return try {
            val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            manager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == FloatingBubbleService::class.java.name
            }
        } catch (_: Exception) {
            false
        }
    }

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
            EmergencyLog.log("Opened app details settings")
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

    private fun startBubbleService() {
        try {
            val intent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            EmergencyLog.log("FloatingBubbleService started")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "startBubbleService")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "✅ Overlay permission granted!", Toast.LENGTH_SHORT).show()
                        EmergencyLog.log("Overlay permission granted")
                        startBubbleService()
                        finish()
                    } else {
                        Toast.makeText(
                            this,
                            "❌ Please enable 'Display over other apps' permission",
                            Toast.LENGTH_LONG
                        ).show()
                        EmergencyLog.logError("Overlay permission still denied")
                        finish()
                    }
                } else {
                    finish()
                }
            }, 500)
        }
    }
}