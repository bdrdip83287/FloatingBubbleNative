package com.dip83287.floatingbubble

import android.content.ComponentName
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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay permission already granted")
                startBubbleService()
                finish()
            } else {
                EmergencyLog.log("Opening overlay permission settings")
                requestOverlayPermission()
            }
        } else {
            startBubbleService()
            finish()
        }
    }

    /**
     * ✅ Multiple methods to open overlay permission page
     * ✅ Method 1: Direct app-specific toggle (works on most devices)
     * ✅ Method 2: App details page (fallback)
     * ✅ Method 3: List page (final fallback)
     */
    private fun requestOverlayPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // ✅ Android 10+ - Try direct app-specific toggle first
                try {
                    // Method 1: Intent with package name (opens app-specific page on most devices)
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                    Toast.makeText(this, "✅ Enable 'Allow display over other apps'", Toast.LENGTH_LONG).show()
                    EmergencyLog.log("Android 10+: Opening overlay with package")
                } catch (e: Exception) {
                    EmergencyLog.logException(e, "Method 1 failed")
                    
                    // Method 2: Try to open app settings directly
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.parse("package:$packageName")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                        Toast.makeText(this, "Please enable 'Display over other apps' in settings", Toast.LENGTH_LONG).show()
                        EmergencyLog.log("Android 10+: Fallback to app details")
                    } catch (e2: Exception) {
                        EmergencyLog.logException(e2, "Method 2 failed")
                        
                        // Method 3: Final fallback - list page
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                            Toast.makeText(this, "Please find 'Floating Notes' and enable permission", Toast.LENGTH_LONG).show()
                            EmergencyLog.log("Android 10+: Final fallback to list page")
                        } catch (e3: Exception) {
                            EmergencyLog.logException(e3, "Method 3 failed")
                            Toast.makeText(this, "Please manually enable overlay permission from Settings", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
            } else {
                // ✅ Android 6-9 (API 23-28) - App Details পেজ
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                    Toast.makeText(this, "Please go to 'Display over other apps' and enable permission", Toast.LENGTH_LONG).show()
                    EmergencyLog.log("Android 6-9: Opening app details page")
                } catch (e: Exception) {
                    EmergencyLog.logException(e, "Android 6-9 app details failed")
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                        Toast.makeText(this, "Please enable 'Allow display over other apps'", Toast.LENGTH_LONG).show()
                    } catch (e2: Exception) {
                        EmergencyLog.logException(e2, "Android 6-9 fallback failed")
                        Toast.makeText(this, "Please manually enable overlay permission from Settings", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "requestOverlayPermission")
            Toast.makeText(this, "Please manually enable overlay permission from Settings", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, "✅ Permission granted!", Toast.LENGTH_SHORT).show()
                        EmergencyLog.log("Overlay permission granted")
                        startBubbleService()
                        finish()
                    } else {
                        Toast.makeText(this, "❌ Please enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                        EmergencyLog.logError("Overlay permission still denied")
                        finish()
                    }
                } else {
                    finish()
                }
            }, 800)
        }
    }
}