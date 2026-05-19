package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
                // Permission already granted
                EmergencyLog.log("Overlay permission already granted")
                startBubbleService()
                moveTaskToBack(true)
            } else {
                // Request permission - সরাসরি সেটিংস পেজে নিয়ে যাবে
                EmergencyLog.log("Requesting overlay permission - opening settings")
                openOverlaySettings()
            }
        } else {
            // Below Android 6.0, auto granted
            startBubbleService()
            moveTaskToBack(true)
        }
    }

    private fun openOverlaySettings() {
        try {
            // সরাসরি অ্যাপের overlay permission সেটিংস পেজে নিয়ে যাবে
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            
            // ব্যাখ্যা সহ Toast দেখান
            Toast.makeText(this, "Please enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            EmergencyLog.logException(e, "openOverlaySettings")
            // যদি উপরের Intent কাজ না করে, তাহলে সরাসরি Settings এ নিয়ে যাবে
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                Toast.makeText(this, "Please grant overlay permission manually from Settings", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "✅ Permission granted! Starting bubble...", Toast.LENGTH_SHORT).show()
                    EmergencyLog.log("Overlay permission granted by user")
                    startBubbleService()
                } else {
                    Toast.makeText(this, "❌ Permission denied. Please enable from Settings.", Toast.LENGTH_LONG).show()
                    EmergencyLog.logError("Overlay permission denied by user")
                    // Permission না দিলে আবার সেটিংসে পাঠানোর অপশন
                    showRetryDialog()
                }
            }
            moveTaskToBack(true)
        }
    }
    
    private fun showRetryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("This app needs 'Display over other apps' permission to show floating notes.")
            .setPositiveButton("Open Settings") { _, _ ->
                openOverlaySettings()
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
}
