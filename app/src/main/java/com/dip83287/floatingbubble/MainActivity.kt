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
                EmergencyLog.log("Opening overlay settings directly")
                // সরাসরি overlay settings পেজে নিয়ে যান
                openOverlayPermissionSettings()
            }
        } else {
            startBubbleService()
            finish()
        }
    }

    private fun openOverlayPermissionSettings() {
        try {
            // সবচেয়ে নির্ভরযোগ্য পদ্ধতি - সরাসরি অ্যাপের overlay permission পেজে
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            Toast.makeText(this, "🔘 Find 'Floating Notes' and turn ON 'Display over other apps'", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            EmergencyLog.logException(e, "openOverlayPermissionSettings")
            // Fallback: সরাসরি main overlay settings পেজে
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), OVERLAY_PERMISSION_REQUEST)
                Toast.makeText(this, "Search for 'Floating Notes' and enable permission", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                EmergencyLog.logError("Failed to open overlay settings")
                Toast.makeText(this, "Please manually enable overlay permission from Settings > Apps > Floating Notes > Display over other apps", Toast.LENGTH_LONG).show()
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
            // একটু delay দিন permission check করার জন্য
            Handler(Looper.getMainLooper()).postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "✅ Permission granted! Starting bubble...", Toast.LENGTH_SHORT).show()
                        EmergencyLog.log("Overlay permission granted")
                        startBubbleService()
                    } else {
                        Toast.makeText(this, "❌ Permission not granted. Please enable it from Settings.", Toast.LENGTH_LONG).show()
                        EmergencyLog.logError("Overlay permission denied")
                    }
                }
                finish()
            }, 1000)
        }
    }
}
