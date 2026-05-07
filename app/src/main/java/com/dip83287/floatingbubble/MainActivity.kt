package com.dip83287.floatingbubble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize logging
        EmergencyLog.init(this)
        EmergencyLog.log("MainActivity: onCreate called")
        
        // Check storage permission for Android 11+
        checkStoragePermission()
        
        // Check overlay permission
        checkOverlayPermission()
        
        // Show UI
        showUI()
    }
    
    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - External storage access is different
            EmergencyLog.log("Android 11+: External storage access via MediaStore")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }
    
    private fun checkOverlayPermission() {
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
            }
        } else {
            startBubbleService()
        }
    }
    
    private fun showUI() {
        val scrollView = ScrollView(this)
        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val title = TextView(this).apply {
            text = "📝 Floating Notes"
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }
        linearLayout.addView(title)
        
        // Log file location info
        val logFile = EmergencyLog.getLogFile()
        val infoText = TextView(this).apply {
            text = "📍 Log file location:\n${logFile?.absolutePath ?: "Not available"}\n\nYou can view this file using any file manager app."
            textSize = 12f
            setPadding(0, 0, 0, 32)
            setTextColor(android.graphics.Color.parseColor("#666666"))
        }
        linearLayout.addView(infoText)
        
        // Show log content
        val logContent = EmergencyLog.getLogContent()
        val logTextView = TextView(this).apply {
            text = if (logContent.isNotEmpty()) logContent else "No logs yet.\n\nApp is running."
            textSize = 10f
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
                }
            }
        }
        linearLayout.addView(shareBtn)
        
        scrollView.addView(linearLayout)
        setContentView(scrollView)
        
        EmergencyLog.log("MainActivity: UI displayed")
    }
    
    private fun startBubbleService() {
        try {
            val intent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            EmergencyLog.log("MainActivity: Service started")
        } catch (e: Exception) {
            EmergencyLog.logError("MainActivity: Failed to start service", e)
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                EmergencyLog.log("Storage permission granted")
            } else {
                EmergencyLog.log("Storage permission denied")
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay permission granted")
                startBubbleService()
            } else {
                EmergencyLog.log("Overlay permission denied")
            }
        }
    }
}
