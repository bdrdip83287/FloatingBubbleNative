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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                startBubbleService()
                moveTaskToBack(true)
                finish()
            } else {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val alreadyRequested = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)
                
                if (!alreadyRequested) {
                    prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
                    openOverlaySettings()
                } else {
                    Toast.makeText(this, "Please enable 'Display over other apps' permission in Settings", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } else {
            startBubbleService()
            finish()
        }
    }

    private fun openOverlaySettings() {
        try {
            // পদ্ধতি ১: ডেডিকেটেড অ্যাপ সেটিংস
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivityForResult(intent, 100)
        } catch (e: Exception) {
            try {
                // পদ্ধতি ২: সাধারণ অ্যাপ সেটিংস
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, 100)
            } catch (e2: Exception) {
                // পদ্ধতি ৩: সরাসরি Display over other apps সেটিংস
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                startActivityForResult(intent, 100)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startBubbleService()
                    moveTaskToBack(true)
                    Toast.makeText(this, "Floating bubble activated!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Please enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
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
