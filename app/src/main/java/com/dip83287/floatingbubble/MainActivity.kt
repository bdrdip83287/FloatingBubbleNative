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
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        EmergencyLog.init(this)
        EmergencyLog.write("MAIN ACTIVITY STARTED")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val overlayBtn = Button(this).apply {
            text = "Grant Overlay Permission"

            setOnClickListener {

                EmergencyLog.write("Overlay permission button clicked")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )

                    startActivity(intent)
                }
            }
        }

        val startBtn = Button(this).apply {
            text = "Start Bubble Service"

            setOnClickListener {

                EmergencyLog.write("Start service clicked")

                startService(
                    Intent(
                        this@MainActivity,
                        FloatingBubbleService::class.java
                    )
                )

                refreshLogs()
            }
        }

        val refreshBtn = Button(this).apply {
            text = "Refresh Logs"

            setOnClickListener {
                refreshLogs()
            }
        }

        logText = TextView(this).apply {
            textSize = 14f
        }

        val scroll = ScrollView(this).apply {
            addView(logText)
        }

        root.addView(overlayBtn)
        root.addView(startBtn)
        root.addView(refreshBtn)
        root.addView(scroll)

        setContentView(root)

        refreshLogs()
    }

    private fun refreshLogs() {

        try {

            val file = EmergencyLog.getLogFile()

            if (file != null && file.exists()) {

                logText.text = file.readText()

            } else {

                logText.text = "Log file not initialized or not found"
            }

        } catch (e: Exception) {

            logText.text = e.stackTraceToString()
        }
    }
}
