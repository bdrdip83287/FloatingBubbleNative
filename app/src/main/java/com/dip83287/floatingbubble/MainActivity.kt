package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "bubble_prefs"
        private const val KEY_PERMISSION_REQUESTED = "permission_requested"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // চেক করুন ইতিমধ্যে permission দেওয়া আছে কিনা
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                // Permission already granted
                startBubbleService()
                moveTaskToBack(true)
                finish()
            } else {
                // Permission not granted - check if we already requested
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val alreadyRequested = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)
                
                if (!alreadyRequested) {
                    // First time - request permission
                    prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
                    openOverlaySettings()
                } else {
                    // Already requested but not granted - show message and close
                    Toast.makeText(this, "Please enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } else {
            // Android 6.0 এর নিচে permission দরকার নেই
            startBubbleService()
            finish()
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 100)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    // Permission granted
                    startBubbleService()
                    moveTaskToBack(true)
                    Toast.makeText(this, "Floating bubble activated!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    // Permission still not granted
                    Toast.makeText(this, "Permission required for floating bubble", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun startBubbleService() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
