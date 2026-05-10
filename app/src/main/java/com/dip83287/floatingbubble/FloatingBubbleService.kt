package com.dip83287.floatingbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.dip83287.floatingbubble.utils.SimpleLog
import com.dip83287.floatingbubble.utils.SystemLogger
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private lateinit var log: SimpleLog

    private var bubbleParams: WindowManager.LayoutParams? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_bubble_channel"
    }

    override fun onCreate() {
        super.onCreate()

        SystemLogger.logRuntime("Service onCreate")

        log = SimpleLog.getInstance(this)
        log.i("Service", "onCreate called")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Notes")
            .setContentText("Bubble Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        SystemLogger.logRuntime("onStartCommand")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (bubbleView == null) {
            Handler(Looper.getMainLooper()).post {
                createBubble()
            }
        }

        return START_STICKY
    }

    private fun createBubble() {

        SystemLogger.safe("createBubble") {

            SystemLogger.logRuntime("Creating Bubble UI")

            val bubbleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(20, 20, 20, 20)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#808080"))
                }
            }

            val iconView = TextView(this).apply {
                text = "📝"
                textSize = 27f
                setTextColor(Color.WHITE)
            }

            bubbleLayout.addView(iconView)
            bubbleView = bubbleLayout

            val params = WindowManager.LayoutParams(
                80,
                80,
                if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.TOP or Gravity.START
            params.x = 100
            params.y = 150

            bubbleParams = params

            addDragSupport()

            SystemLogger.safe("addBubbleView") {
                windowManager.addView(bubbleView, params)
            }

            SystemLogger.logRuntime("Bubble Added x=${params.x}, y=${params.y}")

        }
    }

    // ✅ DRAG SYSTEM (IMPORTANT FIX)
    private fun addDragSupport() {

        var initialX = 0f
        var initialY = 0f

        bubbleView?.setOnTouchListener { _, event ->

            val params = bubbleParams ?: return@setOnTouchListener false

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val dx = event.rawX - initialX
                    val dy = event.rawY - initialY

                    params.x = (params.x + dx).toInt()
                    params.y = (params.y + dy).toInt()

                    SystemLogger.safe("updateBubblePosition") {
                        bubbleView?.let {
                            windowManager.updateViewLayout(it, params)
                        }
                    }

                    SystemLogger.logRuntime(
                        "Bubble moved x=${params.x}, y=${params.y}"
                    )

                    initialX = event.rawX
                    initialY = event.rawY

                    true
                }

                MotionEvent.ACTION_UP -> {
                    true
                }

                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            bubbleView?.let { windowManager.removeView(it) }
            SystemLogger.logRuntime("Service Destroyed")
        } catch (e: Exception) {
            SystemLogger.logError("onDestroy error", e)
        }
    }

    override fun onBind(intent: Intent?) = null
}