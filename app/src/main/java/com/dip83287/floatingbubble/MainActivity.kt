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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmergencyLog.logLifecycle("MainActivity", "onCreate")

        // ✅ Step 1: Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Opening overlay settings page")
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
                EmergencyLog.log("Requesting POST_NOTIFICATIONS permission")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
                return
            }
        }

        // ✅ Step 3: সব permission আছে — service start
        startBubbleService()
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            // Permission granted or denied — either way start service
            // (যদি denied হয়, silent notification দিয়ে service চলবে)
            Handler(Looper.getMainLooper()).postDelayed({
                startBubbleService()
                finish()
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        EmergencyLog.logLifecycle("MainActivity", "onResume")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                val isNotificationLaunch = intent?.getBooleanExtra(
                    "from_notification", false
                ) ?: false

                if (isNotificationLaunch) {
                    EmergencyLog.log("Launched from notification tap")
                    startBubbleService()
                    finish()
                    return
                }

                if (!isServiceRunning()) {
                    EmergencyLog.log("Service not running, starting it")
                    startBubbleService()
                    finish()
                }
            }
        } else {
            if (!isServiceRunning()) {
                startBubbleService()
                finish()
            }
        }
    }

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
                        // ✅ Overlay granted → now check notification permission
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_REQUEST
                            )
                        } else {
                            Toast.makeText(this, "✅ Overlay permission granted!", Toast.LENGTH_SHORT).show()
                            EmergencyLog.log("Overlay permission granted")
                            startBubbleService()
                            finish()
                        }
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