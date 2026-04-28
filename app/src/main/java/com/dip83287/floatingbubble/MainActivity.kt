package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "bubble_prefs"
        private const val KEY_PERMISSION_REQUESTED = "permission_requested"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // একটি সাধারণ ভিউ তৈরি করুন (এটি অ্যাপ ওপেন করলেই দেখাবে)
        val textView = TextView(this).apply {
            text = "Starting Floating Notes...\nPlease wait"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        setContentView(textView)
        
        // Permission চেক করুন
        checkAndRequestPermission()
    }

    private fun checkAndRequestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                // Permission already granted
                startBubbleServiceAndClose()
            } else {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val alreadyRequested = prefs.getBoolean(KEY_PERMISSION_REQUESTED, false)
                
                if (!alreadyRequested) {
                    prefs.edit().putBoolean(KEY_PERMISSION_REQUESTED, true).apply()
                    requestOverlayPermission()
                } else {
                    Toast.makeText(this, "Please enable 'Display over other apps' permission in Settings", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } else {
            startBubbleServiceAndClose()
        }
    }

    private fun requestOverlayPermission() {
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
                    startBubbleServiceAndClose()
                } else {
                    Toast.makeText(this, "Permission required for floating bubble", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun startBubbleServiceAndClose() {
        try {
            val intent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Floating bubble started!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
        
        // অ্যাপ বন্ধ করে দিন (শুধু bubble থাকবে)
        moveTaskToBack(true)
        finish()
    }
}
