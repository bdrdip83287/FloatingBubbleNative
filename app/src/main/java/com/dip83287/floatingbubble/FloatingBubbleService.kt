package com.dip83287.floatingbubble
import com.dip83287.floatingbubble.utils.EmergencyLog

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.dip83287.floatingbubble.utils.SystemLogger

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null

    override fun onCreate() {
        super.onCreate()
        EmergencyLog.init(this)
        EmergencyLog.write("SERVICE CREATED")

        com.dip83287.floatingbubble.utils.EmergencyLog.write("FLOATING SERVICE CREATED")


        com.dip83287.floatingbubble.utils.EmergencyLog.write("FLOATING SERVICE CREATED")


        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        SystemLogger.init(this)
        SystemLogger.logRuntime("SERVICE CREATED")
        SystemLogger.logRuntime("LOG PATH = ${SystemLogger.getPath()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        SystemLogger.logRuntime("onStartCommand")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            SystemLogger.logError("Overlay permission missing", null)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (bubbleView == null) {
                Handler(Looper.getMainLooper()).post {
                    createBubble()
                }
            }
        } catch (e: Exception) {
            SystemLogger.logError("Bubble create failed", e)
        }

        return START_STICKY
    }

    private fun createBubble() {
        SystemLogger.flow("createBubble", "START")

        try {
            val bubble = TextView(this).apply {
                text = "📝"
                textSize = 26f

                setOnClickListener {
                    SystemLogger.flow("bubble", "clicked")
                }
            }

            bubbleView = bubble

            val params = WindowManager.LayoutParams(
                120,
                120,
                if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(bubbleView, params)

            SystemLogger.flow("createBubble", "SUCCESS")

        } catch (e: Exception) {
            SystemLogger.logError("createBubble crash", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            bubbleView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            SystemLogger.logError("onDestroy error", e)
        }

        SystemLogger.logRuntime("SERVICE DESTROYED")
    }

    override fun onBind(intent: Intent?) = null
}
