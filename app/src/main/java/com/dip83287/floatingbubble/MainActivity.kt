package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.Bundle
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
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay permission already granted")
                startBubbleService()
                moveTaskToBack(true)
            } else {
                EmergencyLog.log("Requesting overlay permission")
                requestOverlayPermission()
            }
        } else {
            startBubbleService()
            moveTaskToBack(true)
        }
    }

    private fun requestOverlayPermission() {
        try {
            // সবচেয়ে নির্ভরযোগ্য পদ্ধতি
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            
            // ব্যাখ্যা সহ Toast
            Toast.makeText(this, "🔘 Find 'Floating Notes' and turn ON 'Display over other apps'", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "requestOverlayPermission")
            
            // Fallback: সরাসরি Settings এ নিয়ে যান
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), OVERLAY_PERMISSION_REQUEST)
                Toast.makeText(this, "Search for 'Floating Notes' and enable permission", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                // Last resort: App info page
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
                    Toast.makeText(this, "Go to Permissions > Display over other apps", Toast.LENGTH_LONG).show()
                } catch (e3: Exception) {
                    EmergencyLog.logError("Failed to open any settings page")
                    Toast.makeText(this, "Please manually enable overlay permission from Settings", Toast.LENGTH_LONG).show()
                }
            }
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
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            // একটু delay দিয়ে permission check করুন
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "✅ Permission granted! Starting bubble...", Toast.LENGTH_SHORT).show()
                        EmergencyLog.log("Overlay permission granted")
                        startBubbleService()
                    } else {
                        Toast.makeText(this, "❌ Permission not granted. Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
                        EmergencyLog.logError("Overlay permission still denied")
                    }
                }
                moveTaskToBack(true)
            }, 500)
        }
    }
}
