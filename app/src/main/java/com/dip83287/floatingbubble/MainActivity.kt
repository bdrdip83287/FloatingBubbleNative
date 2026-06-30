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
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay permission already granted")
                startBubbleService()
                finish()
            } else {
                EmergencyLog.log("Opening app settings page")
                openAppSettings()
            }
        } else {
            startBubbleService()
            finish()
        }
    }

    private fun openAppSettings() {
        try {
            // ✅ App Details পেজ ওপেন করুন (সব ডিভাইসে কাজ করে)
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            Toast.makeText(this, "🔵 Go to 'Display over other apps' and enable permission", Toast.LENGTH_LONG).show()
            EmergencyLog.log("Opened app details settings")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "openAppSettings")
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
            }, 500)
        }
    }
}