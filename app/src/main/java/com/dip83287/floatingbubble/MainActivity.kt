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

        EmergencyLog.log("MAIN ACTIVITY OPENED")

        val root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(30, 30, 30, 30)
        }

        val overlayBtn = Button(this).apply {

            text = "Grant Overlay Permission"

            setOnClickListener {

                try {

                    EmergencyLog.log(
                        "OVERLAY BUTTON CLICKED"
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )

                        startActivity(intent)
                    }

                } catch (e: Exception) {

                    EmergencyLog.logException(e)
                }
            }
        }

        val startBtn = Button(this).apply {

            text = "Start Bubble Service"

            setOnClickListener {

                try {

                    EmergencyLog.log(
                        "START SERVICE BUTTON CLICKED"
                    )

                    startService(
                        Intent(
                            this@MainActivity,
                            FloatingBubbleService::class.java
                        )
                    )

                    refreshLogs()

                } catch (e: Exception) {

                    EmergencyLog.logException(e)
                }
            }
        }

        val refreshBtn = Button(this).apply {

            text = "Refresh Logs"

            setOnClickListener {

                refreshLogs()
            }
        }

        val pathText = TextView(this).apply {

            textSize = 12f

            text = """
                
LOG FILE:
${EmergencyLog.getLogPath()}
                
            """.trimIndent()
        }

        logText = TextView(this).apply {

            textSize = 13f
        }

        val scroll = ScrollView(this).apply {

            addView(logText)
        }

        root.addView(overlayBtn)
        root.addView(startBtn)
        root.addView(refreshBtn)
        root.addView(pathText)
        root.addView(scroll)

        setContentView(root)

        refreshLogs()
    }

    private fun refreshLogs() {

        try {

            logText.text =
                EmergencyLog.getLogContent()

        } catch (e: Exception) {

            EmergencyLog.logException(e)

            logText.text = e.stackTraceToString()
        }
    }
}