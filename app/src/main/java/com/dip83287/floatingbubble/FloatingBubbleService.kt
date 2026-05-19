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
        
        log = SimpleLog.getInstance(this)
        log.i("FloatingBubbleService", "onCreate called")
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        log.i("FloatingBubbleService", "Foreground service started")
        
        // ডিলিট জোন তৈরি করুন
        createDeleteZone()
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

    // ডিলিট জোন তৈরি করুন
    private lateinit var deleteZoneView: View
    private var isInDeleteZone = false
    private val DELETE_ZONE_SIZE = 110

    private fun createDeleteZone() {
        try {
            val zone = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.RED)
                }
                background = shape
                setPadding(30, 30, 30, 30)
            }

            val cross = TextView(this).apply {
                text = "✕"
                textSize = 35f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }
            zone.addView(cross)

            val params = WindowManager.LayoutParams(
                DELETE_ZONE_SIZE, DELETE_ZONE_SIZE,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = 80
            zone.visibility = View.GONE
            deleteZoneView = zone
            windowManager.addView(deleteZoneView, params)
            log.i("FloatingBubbleService", "Delete zone created")
        } catch (e: Exception) {
            log.e("FloatingBubbleService", "Failed to create delete zone", e)
        }
    }

    private fun showDeleteZone() {
        if (deleteZoneView.visibility != View.VISIBLE) {
            deleteZoneView.visibility = View.VISIBLE
            log.d("FloatingBubbleService", "Delete zone shown")
        }
    }

    private fun hideDeleteZone() {
        if (deleteZoneView.visibility != View.GONE) {
            deleteZoneView.visibility = View.GONE
            log.d("FloatingBubbleService", "Delete zone hidden")
        }
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
                gravity = Gravity.CENTER
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

            // টাচ লিসেনার - ড্র্যাগ এবং ডিলিট জোনের জন্য
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            bubbleView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isInDeleteZone = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        
                        // ডিলিট জোন চেক (স্ক্রিনের নিচের অংশ)
                        val screenHeight = resources.displayMetrics.heightPixels
                        if (params.y + 80 > screenHeight - DELETE_ZONE_SIZE) {
                            if (!isInDeleteZone) {
                                isInDeleteZone = true
                                showDeleteZone()
                                log.d("FloatingBubbleService", "Entered delete zone")
                            }
                        } else {
                            if (isInDeleteZone) {
                                isInDeleteZone = false
                                hideDeleteZone()
                                log.d("FloatingBubbleService", "Left delete zone")
                            }
                        }
                        
                        windowManager.updateViewLayout(bubbleView!!, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        hideDeleteZone()
                        
                        // ডিলিট জোনে ছাড়লে বাবল ডিলিট
                        if (isInDeleteZone) {
                            log.i("FloatingBubbleService", "Bubble deleted via delete zone")
                            stopSelf()
                            return@setOnTouchListener true
                        }
                        
                        // ক্লিক চেক (ড্র্যাগ না হলে)
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) < 10 && abs(dy) < 10) {
                            expandToNotePad()
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager.addView(bubbleView, params)
            log.i("FloatingBubbleService", "Bubble created successfully")
            
        } catch (e: Exception) {
            log.e("FloatingBubbleService", "Failed to create bubble", e)
        }
    }

    private fun expandToNotePad() {
        // TODO: নোটপ্যাড খোলার কোড এখানে আসবে
        Toast.makeText(this, "Opening notepad...", Toast.LENGTH_SHORT).show()
        log.i("FloatingBubbleService", "Expand to notepad requested")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bubbleView?.let { windowManager.removeView(it) }
            if (::deleteZoneView.isInitialized) {
                windowManager.removeView(deleteZoneView)
            }
            log.i("FloatingBubbleService", "Service destroyed")
        } catch (e: Exception) {
            log.e("FloatingBubbleService", "Error in onDestroy", e)
        }
    }

    override fun onBind(intent: Intent?) = null
}
