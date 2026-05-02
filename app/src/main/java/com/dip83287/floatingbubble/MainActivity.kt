package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.SimpleCrashHandler
import com.dip83287.floatingbubble.utils.SimpleLog

class MainActivity : AppCompatActivity() {
    
    private lateinit var log: SimpleLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize logging
        SimpleCrashHandler.init(this)
        log = SimpleLog.getInstance(this)
        
        log.i("MainActivity", "onCreate called")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                log.w("MainActivity", "Overlay permission not granted, requesting")
                Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 100)
            } else {
                log.i("MainActivity", "Overlay permission already granted")
                startBubbleService()
                finish()
            }
        } else {
            startBubbleService()
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
            log.i("MainActivity", "Bubble service started")
            Toast.makeText(this, "Floating bubble started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            log.e("MainActivity", "Failed to start service", e)
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                log.i("MainActivity", "Permission granted by user")
                startBubbleService()
                finish()
            } else {
                log.w("MainActivity", "Permission denied by user")
                Toast.makeText(this, "Permission required for floating bubble", Toast.LENGTH_LONG).show()
            }
        }
    }
}
