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
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private lateinit var log: SimpleLog
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_bubble_channel"
    }

    override fun onCreate() {
        super.onCreate()
        
        // First, log to file
        log = SimpleLog.getInstance(this)
        log.i("FloatingBubbleService", "onCreate called")
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Create notification channel and start foreground
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        log.i("FloatingBubbleService", "Foreground service started")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating notes bubble"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            log.i("FloatingBubbleService", "Notification channel created")
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Notes")
            .setContentText("Bubble is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.i("FloatingBubbleService", "onStartCommand called")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            log.w("FloatingBubbleService", "Overlay permission not granted")
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
        try {
            log.d("FloatingBubbleService", "Creating bubble")
            
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
                textSize = 28f
                setTextColor(Color.WHITE)
            }
            bubbleLayout.addView(iconView)

            bubbleView = bubbleLayout

            val params = WindowManager.LayoutParams(
                80, 80,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 100
            params.y = 150

            windowManager.addView(bubbleView, params)
            log.i("FloatingBubbleService", "Bubble created successfully")
            
        } catch (e: Exception) {
            log.e("FloatingBubbleService", "Failed to create bubble", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bubbleView?.let { windowManager.removeView(it) }
            log.i("FloatingBubbleService", "Service destroyed")
        } catch (e: Exception) {
            log.e("FloatingBubbleService", "Error in onDestroy", e)
        }
    }

    override fun onBind(intent: Intent?) = null
}
