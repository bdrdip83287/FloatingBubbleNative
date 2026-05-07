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
        EmergencyLog.log("MainActivity: onCreate called")
        
        // Start service
        startBubbleService()
        
        // Show log viewer
        showLogViewer()
    }
    
    private fun showLogViewer() {
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
        
        // লগ ফাইলের পাথ দেখান
        val logFile = EmergencyLog.getLogFile()
        val pathText = TextView(this).apply {
            text = "📍 Log file location:\n${logFile?.absolutePath ?: "Not available"}\n\nYou can access this file via:\nrun-as com.dip83287.floatingbubble cat ${logFile?.absolutePath}"
            textSize = 12f
            setPadding(0, 0, 0, 32)
            setTextColor(android.graphics.Color.parseColor("#666666"))
        }
        linearLayout.addView(pathText)
        
        // লগ কন্টেন্ট দেখান
        val logContent = EmergencyLog.getLogContent()
        val logTextView = TextView(this).apply {
            text = if (logContent.isNotEmpty()) logContent else "No logs yet.\n\nMake sure the app is running."
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
        
        scrollView.addView(linearLayout)
        setContentView(scrollView)
        
        EmergencyLog.log("MainActivity: Log viewer displayed")
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
}
