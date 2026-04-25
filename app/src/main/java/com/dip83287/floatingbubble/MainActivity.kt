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
        
        // শুধু permission চেক এবং bubble start
        checkAndStartBubble()
        
        // অ্যাপটি immediately minimize করে দিচ্ছি যাতে শুধু bubble দেখা যায়
        moveTaskToBack(true)
    }

    private fun checkAndStartBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                startBubbleService()
                // permission দেওয়ার পর অ্যাপ বন্ধ করে দিচ্ছি
                finish()
            }
        } else {
            startBubbleService()
            finish()
        }
    }

    private fun startBubbleService() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Floating bubble started", Toast.LENGTH_SHORT).show()
    }
}
