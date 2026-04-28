package com.dip83287.floatingbubble

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "floating_channel"
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Notes",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Notes")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (bubbleView == null) {
            Handler(Looper.getMainLooper()).post {
                createSimpleBubble()
            }
        }

        return START_STICKY
    }

    private fun createSimpleBubble() {
        // Create a simple text view as bubble
        val bubble = TextView(this).apply {
            text = "📝"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(25, 25, 25, 25)
            setBackgroundColor(Color.parseColor("#2196F3"))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = screenWidth - 150
        params.y = 300

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                    }
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Click - expand to notepad (will add later)
                        Toast.makeText(this@FloatingBubbleService, "Bubble clicked!", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
            }
            false
        }

        windowManager.addView(bubble, params)
        bubbleView = bubble
        
        Toast.makeText(this, "Bubble created successfully!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { 
            windowManager.removeView(it)
            bubbleView = null
        }
    }

    override fun onBind(intent: Intent?) = null
}
