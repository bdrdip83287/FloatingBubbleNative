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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize logger
        EmergencyLog.init(this)

        EmergencyLog.log("========== MAIN ACTIVITY STARTED ==========")

        // Runtime storage permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
                    100
                )
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val overlayBtn = Button(this).apply {

            text = "Grant Overlay Permission"

            setOnClickListener {

                EmergencyLog.log("Overlay permission button clicked")

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

                EmergencyLog.log("Start Bubble Service button clicked")

                try {

                    startService(
                        Intent(
                            this@MainActivity,
                            FloatingBubbleService::class.java
                        )
                    )

                    EmergencyLog.log("FloatingBubbleService started successfully")

                } catch (e: Exception) {

                    EmergencyLog.logError(
                        "SERVICE_START_ERROR",
                        e
                    )
                }

                refreshLogs()
            }
        }

        val refreshBtn = Button(this).apply {

            text = "Refresh Logs"

            setOnClickListener {

                EmergencyLog.log("Refresh Logs button clicked")

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

        EmergencyLog.log(
            "Log File Path: ${EmergencyLog.getLogPath()}"
        )

        refreshLogs()
    }

    private fun refreshLogs() {

        try {

            logText.text = EmergencyLog.getLogContent()

        } catch (e: Exception) {

            logText.text = e.stackTraceToString()

            EmergencyLog.logError(
                "REFRESH_LOGS_ERROR",
                e
            )
        }
    }
}