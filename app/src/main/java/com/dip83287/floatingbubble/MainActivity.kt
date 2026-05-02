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
        
        SimpleCrashHandler.init(this)
        log = SimpleLog.getInstance(this)
        
        log.i("MainActivity", "onCreate called")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                log.w("MainActivity", "Requesting overlay permission")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 100)
            } else {
                log.i("MainActivity", "Permission already granted")
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        layout.addView(TextView(this).apply {
            text = "✅ Floating Notes Active\n\nFloating bubble is running in background.\n\n📁 Log location:\n/storage/emulated/0/FloatingNotesLogs/"
            textSize = 14f
            setPadding(0, 0, 0, 32)
        })
        
        layout.addView(Button(this).apply {
            text = "📖 View Log"
            setOnClickListener { showLog() }
        })
        
        layout.addView(Button(this).apply {
            text = "📤 Share Log"
            setOnClickListener { shareLog() }
        })
        
        layout.addView(Button(this).apply {
            text = "🗑️ Clear Log"
            setOnClickListener { 
                log.clearLog()
                Toast.makeText(this@MainActivity, "Log cleared", Toast.LENGTH_SHORT).show()
            }
        })
        
        scrollView.addView(layout)
        setContentView(scrollView)
    }
    
    private fun showLog() {
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
        
        findViewById<android.view.View>(android.R.id.content)?.postDelayed({
            val backBtn = Button(this).apply {
                text = "← Back"
                setOnClickListener { showMainUI() }
            }
            (scrollView.parent as? android.view.ViewGroup)?.addView(backBtn)
        }, 100)
    }
    
    private fun shareLog() {
        val logFile = log.getLogFile()
        if (logFile.exists()) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    logFile
                ))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Log"))
        } else {
            Toast.makeText(this, "No log file found", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Floating bubble started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            log.e("MainActivity", "Failed to start service", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                log.i("MainActivity", "Permission granted")
                startBubbleService()
                showMainUI()
            } else {
                log.w("MainActivity", "Permission denied")
                Toast.makeText(this, "Permission required for floating bubble", Toast.LENGTH_LONG).show()
            }
        }
    }
}
