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
        EmergencyLog.log("MAIN ACTIVITY STARTED")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
        }

        val overlayBtn = Button(this).apply {
            text = "Grant Overlay Permission"

            setOnClickListener {

                EmergencyLog.log("Overlay permission button clicked")

                try {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )

                        startActivity(intent)

                        EmergencyLog.log("Overlay permission screen opened")
                    }

                } catch (e: Exception) {

                    EmergencyLog.logError(
                        "Overlay permission error: ${e.message}"
                    )
                }
            }
        }

        val startBtn = Button(this).apply {
            text = "Start Bubble Service"

            setOnClickListener {

                EmergencyLog.log("Start service clicked")

                try {

                    startService(
                        Intent(
                            this@MainActivity,
                            FloatingBubbleService::class.java
                        )
                    )

                    EmergencyLog.log("Bubble service start requested")

                    refreshLogs()

                } catch (e: Exception) {

                    EmergencyLog.logError(
                        "Service start failed: ${e.message}"
                    )
                }
            }
        }

        val refreshBtn = Button(this).apply {
            text = "Refresh Logs"

            setOnClickListener {

                EmergencyLog.log("Refresh button clicked")

                refreshLogs()
            }
        }

        val pathBtn = Button(this).apply {
            text = "Show Log Path"

            setOnClickListener {

                try {

                    val path = EmergencyLog.getLogPath()

                    logText.text =
                        "Log File Path:\n\n$path"

                    EmergencyLog.log(
                        "Displayed log path"
                    )

                } catch (e: Exception) {

                    EmergencyLog.logError(
                        "Path show error: ${e.message}"
                    )
                }
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
        root.addView(pathBtn)
        root.addView(scroll)

        setContentView(root)

        refreshLogs()
    }

    private fun refreshLogs() {

        try {

            val content = EmergencyLog.getLogContent()

            logText.text = content

            EmergencyLog.log("Logs refreshed")

        } catch (e: Exception) {

            logText.text = e.stackTraceToString()

            EmergencyLog.logError(
                "Refresh logs failed: ${e.message}"
            )
        }
    }
}