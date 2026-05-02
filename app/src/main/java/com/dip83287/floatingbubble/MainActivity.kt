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
                showMainUI()
            }
        } else {
            startBubbleService()
            showMainUI()
        }
    }
    
    private fun showMainUI() {
        val scrollView = ScrollView(this)
        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val title = TextView(this).apply {
            text = "📝 Floating Notes"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        linearLayout.addView(title)
        
        val statusText = TextView(this).apply {
            text = "✅ Floating bubble is running in background.\n\n📁 Log file location:\n${filesDir}/logs/floating_notes_log.txt\n\nUse Termux to view logs:\ncat ${filesDir}/logs/floating_notes_log.txt"
            textSize = 12f
            setPadding(0, 0, 0, 32)
        }
        linearLayout.addView(statusText)
        
        val viewLogBtn = Button(this).apply {
            text = "📖 View Log"
            setOnClickListener { viewLog() }
        }
        linearLayout.addView(viewLogBtn)
        
        val shareLogBtn = Button(this).apply {
            text = "📤 Share Log"
            setOnClickListener { shareLog() }
        }
        linearLayout.addView(shareLogBtn)
        
        val clearLogBtn = Button(this).apply {
            text = "🗑️ Clear Log"
            setOnClickListener { clearLog() }
        }
        linearLayout.addView(clearLogBtn)
        
        val closeBtn = Button(this).apply {
            text = "⬇️ Minimize to Bubble"
            setOnClickListener { moveTaskToBack(true) }
        }
        linearLayout.addView(closeBtn)
        
        scrollView.addView(linearLayout)
        setContentView(scrollView)
    }
    
    private fun viewLog() {
        val logContent = log.getLogContent()
        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = logContent
            textSize = 10f
            setPadding(16, 16, 16, 16)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(textView)
        setContentView(scrollView)
        
        val backBtn = Button(this).apply {
            text = "← Back"
            setOnClickListener { showMainUI() }
        }
        (scrollView.parent as? android.view.ViewGroup)?.addView(backBtn)
    }
    
    private fun shareLog() {
        val logContent = log.getLogContent()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logContent)
            putExtra(Intent.EXTRA_SUBJECT, "Floating Notes Log")
        }
        startActivity(Intent.createChooser(shareIntent, "Share Log"))
    }
    
    private fun clearLog() {
        log.clearLog()
        Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
        showMainUI()
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
                showMainUI()
            } else {
                log.w("MainActivity", "Permission denied by user")
                Toast.makeText(this, "Permission required for floating bubble", Toast.LENGTH_LONG).show()
            }
        }
    }
}
