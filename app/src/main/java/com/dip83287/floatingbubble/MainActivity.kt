package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // সরাসরি "Display over other apps" সেটিংস পৃষ্ঠায় নিয়ে যান
        openOverlaySettings()
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // সরাসরি এই অ্যাপের জন্য Display over other apps সেটিংস খুলবে
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 100)
        } else {
            // Android 6.0 এর নিচে permission দরকার নেই
            startBubbleService()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 100) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    // Permission granted - start bubble service
                    startBubbleService()
                    // অ্যাপটি ব্যাকগ্রাউন্ডে পাঠিয়ে দিন
                    moveTaskToBack(true)
                    Toast.makeText(this, "Floating bubble activated!", Toast.LENGTH_SHORT).show()
                } else {
                    // Permission denied - show message and exit
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
