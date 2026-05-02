package com.dip83287.floatingbubble

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.dip83287.floatingbubble.utils.SimpleLog
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var noteView: View? = null
    private var isExpanded = false
    private lateinit var editText: EditText
    private lateinit var log: SimpleLog

    override fun onCreate() {
        super.onCreate()
        log = SimpleLog.getInstance(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        log.i("FloatingBubbleService", "onCreate - Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.i("FloatingBubbleService", "onStartCommand called")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
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
