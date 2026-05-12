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

    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmergencyLog.logLifecycle("MainActivity", "onCreate")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val permissionBtn = Button(this).apply {
            text = "Grant Overlay Permission"
            setOnClickListener {
                EmergencyLog.log("Overlay permission button clicked")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
            }
        }

        val startBtn = Button(this).apply {
            text = "Start Bubble Service"
            setOnClickListener {
                EmergencyLog.log("Start service button clicked")
                startService(Intent(this@MainActivity, FloatingBubbleService::class.java))
                refreshLogs()
                Toast.makeText(this@MainActivity, "Service started", Toast.LENGTH_SHORT).show()
            }
        }

        val stopBtn = Button(this).apply {
            text = "Stop Bubble Service"
            setOnClickListener {
                EmergencyLog.log("Stop service button clicked")
                stopService(Intent(this@MainActivity, FloatingBubbleService::class.java))
                Toast.makeText(this@MainActivity, "Service stopped", Toast.LENGTH_SHORT).show()
            }
        }

        val refreshBtn = Button(this).apply {
            text = "Refresh Logs"
            setOnClickListener { refreshLogs() }
        }

        val clearLogBtn = Button(this).apply {
            text = "Clear Logs"
            setOnClickListener {
                EmergencyLog.clearLog()
                refreshLogs()
                Toast.makeText(this@MainActivity, "Logs cleared", Toast.LENGTH_SHORT).show()
            }
        }

        val pathText = TextView(this).apply {
            textSize = 12f
            text = "\nLOG FILE:\n${EmergencyLog.getLogPath()}\n"
        }

        logText = TextView(this).apply { textSize = 11f }
        val scroll = ScrollView(this).apply { addView(logText) }

        root.addView(permissionBtn)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(refreshBtn)
        root.addView(clearLogBtn)
        root.addView(pathText)
        root.addView(scroll)

        setContentView(root)
        refreshLogs()
        checkPermission()
    }

    private fun refreshLogs() {
        try {
            logText.text = EmergencyLog.getLogContent()
        } catch (e: Exception) {
            logText.text = e.stackTraceToString()
        }
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        EmergencyLog.logLifecycle("MainActivity", "onResume")
        refreshLogs()
    }

    override fun onPause() {
        super.onPause()
        EmergencyLog.logLifecycle("MainActivity", "onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        EmergencyLog.logLifecycle("MainActivity", "onDestroy")
    }
}
