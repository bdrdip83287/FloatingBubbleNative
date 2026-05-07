package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ইমার্জেন্সি লগিং শুরু করুন - সবচেয়ে প্রথমে
        EmergencyLog.init(this)
        EmergencyLog.log("=== APP STARTED ===")
        EmergencyLog.log("MainActivity: onCreate called")
        
        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                EmergencyLog.log("MainActivity: Need overlay permission")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 100)
            } else {
                EmergencyLog.log("MainActivity: Overlay permission already granted")
                startBubbleService()
                showLogViewer()
            }
        } else {
            startBubbleService()
            showLogViewer()
        }
    }
    
    private fun showLogViewer() {
        EmergencyLog.log("MainActivity: Showing log viewer")
        
        val scrollView = ScrollView(this)
        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val title = TextView(this).apply {
            text = "📝 Emergency Log Viewer"
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }
        linearLayout.addView(title)
        
        // লগ কন্টেন্ট দেখান
        val logContent = EmergencyLog.getLogContent()
        EmergencyLog.log("Log content length: ${logContent.length}")
        
        val logTextView = TextView(this).apply {
            text = if (logContent.isNotEmpty()) logContent else "No logs yet.\n\nMake sure the app is running and generating logs."
            textSize = 11f
            setPadding(16, 16, 16, 16)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(android.graphics.Color.parseColor("#333333"))
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
        }
        linearLayout.addView(logTextView)
        
        val refreshBtn = Button(this).apply {
            text = "🔄 Refresh"
            setOnClickListener {
                logTextView.text = EmergencyLog.getLogContent()
                Toast.makeText(this@MainActivity, "Log refreshed", Toast.LENGTH_SHORT).show()
            }
        }
        linearLayout.addView(refreshBtn)
        
        val shareBtn = Button(this).apply {
            text = "📤 Share Log"
            setOnClickListener {
                val content = EmergencyLog.getLogContent()
                if (content.isNotEmpty()) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content)
                        putExtra(Intent.EXTRA_SUBJECT, "Floating Notes Emergency Log")
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Log"))
                } else {
                    Toast.makeText(this@MainActivity, "No logs to share. Run the app first.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        linearLayout.addView(shareBtn)
        
        val clearBtn = Button(this).apply {
            text = "🗑️ Clear Log"
            setOnClickListener {
                EmergencyLog.clearLog()
                logTextView.text = EmergencyLog.getLogContent()
                Toast.makeText(this@MainActivity, "Log cleared", Toast.LENGTH_SHORT).show()
            }
        }
        linearLayout.addView(clearBtn)
        
        scrollView.addView(linearLayout)
        setContentView(scrollView)
        
        EmergencyLog.log("MainActivity: Log viewer displayed successfully")
    }
    
    private fun startBubbleService() {
        try {
            val intent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
                EmergencyLog.log("MainActivity: startForegroundService called")
            } else {
                startService(intent)
                EmergencyLog.log("MainActivity: startService called")
            }
        } catch (e: Exception) {
            EmergencyLog.logError("MainActivity: Failed to start service", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        EmergencyLog.log("MainActivity: onActivityResult called, requestCode=$requestCode")
        
        if (requestCode == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                EmergencyLog.log("MainActivity: Permission granted by user")
                startBubbleService()
                showLogViewer()
            } else {
                EmergencyLog.log("MainActivity: Permission denied by user")
                Toast.makeText(this, "Permission required for floating bubble", Toast.LENGTH_LONG).show()
            }
        }
    }
}
