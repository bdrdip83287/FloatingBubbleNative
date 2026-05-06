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
        
        // Start service in background
        startBubbleService()
        
        // Show log viewer directly
        showLogViewer()
    }
    
    private fun showLogViewer() {
        val scrollView = ScrollView(this)
        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val title = TextView(this).apply {
            text = "📝 Floating Notes - Log Viewer"
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }
        linearLayout.addView(title)
        
        // লগ কন্টেন্ট দেখান
        val logContent = log.getLogContent()
        val logTextView = TextView(this).apply {
            text = if (logContent.isNotEmpty()) logContent else "No logs yet.\n\nApp will log here when running."
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
                logTextView.text = log.getLogContent()
            }
        }
        linearLayout.addView(refreshBtn)
        
        val shareBtn = Button(this).apply {
            text = "📤 Share Log"
            setOnClickListener {
                val content = log.getLogContent()
                if (content.isNotEmpty()) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content)
                        putExtra(Intent.EXTRA_SUBJECT, "Floating Notes Log")
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Log"))
                } else {
                    Toast.makeText(this@MainActivity, "No logs to share", Toast.LENGTH_SHORT).show()
                }
            }
        }
        linearLayout.addView(shareBtn)
        
        val clearBtn = Button(this).apply {
            text = "🗑️ Clear Log"
            setOnClickListener {
                log.clearLog()
                logTextView.text = log.getLogContent()
                Toast.makeText(this@MainActivity, "Log cleared", Toast.LENGTH_SHORT).show()
            }
        }
        linearLayout.addView(clearBtn)
        
        scrollView.addView(linearLayout)
        setContentView(scrollView)
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
        } catch (e: Exception) {
            log.e("MainActivity", "Failed to start service: ${e.message}", e)
        }
    }
}
