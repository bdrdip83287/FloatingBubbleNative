package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.SimpleLog

class MainActivity : AppCompatActivity() {

    private lateinit var log: SimpleLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        log = SimpleLog.getInstance(this)
        log.i("MainActivity", "onCreate called")
        
        checkAndRequestOverlayPermission()
    }

    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                log.i("MainActivity", "Overlay permission already granted")
                startBubbleService()
                moveTaskToBack(true)
            } else {
                log.i("MainActivity", "Requesting overlay permission")
                requestOverlayPermission()
            }
        } else {
            startBubbleService()
            moveTaskToBack(true)
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1001)
            Toast.makeText(this, "Please enable 'Display over other apps' for Floating Notes", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            log.e("MainActivity", "Failed to open permission settings", e)
            Toast.makeText(this, "Please manually enable overlay permission from Settings", Toast.LENGTH_LONG).show()
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
            log.i("MainActivity", "FloatingBubbleService started")
        } catch (e: Exception) {
            log.e("MainActivity", "Failed to start service", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "✅ Permission granted! Starting bubble...", Toast.LENGTH_SHORT).show()
                    log.i("MainActivity", "Overlay permission granted")
                    startBubbleService()
                } else {
                    Toast.makeText(this, "❌ Permission not granted. Please enable from Settings.", Toast.LENGTH_LONG).show()
                    log.w("MainActivity", "Overlay permission denied")
                }
            }
            moveTaskToBack(true)
        }
    }
}
