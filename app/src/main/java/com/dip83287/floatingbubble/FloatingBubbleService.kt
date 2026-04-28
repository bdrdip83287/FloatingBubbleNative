package com.dip83287.floatingbubble

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlin.math.abs

class FloatingBubbleService : Service() {

    // ================= CUSTOM =================
    private val BUBBLE_COLOR = "#808080"
    private val NOTEPAD_BG_COLOR = "#808080"
    private val NOTEPAD_TITLE = "📝 Floating Note"

    private val NOTEPAD_MIN_WIDTH = 300
    private val NOTEPAD_MIN_HEIGHT = 400
    private val NOTEPAD_MAX_WIDTH = 600
    private val NOTEPAD_MAX_HEIGHT = 800

    private val ANIMATION_DURATION = 300L

    private val STORAGE_NOTE_COUNT = "note_count"
    private val STORAGE_LAST_NOTE = "last_note"

    private val STORAGE_BUBBLE_X = "bubble_x"
    private val STORAGE_BUBBLE_Y = "bubble_y"
    private val STORAGE_NOTEPAD_X = "notepad_x"
    private val STORAGE_NOTEPAD_Y = "notepad_y"
    private val STORAGE_NOTEPAD_WIDTH = "notepad_width"
    private val STORAGE_NOTEPAD_HEIGHT = "notepad_height"
    // ==========================================

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var noteView: View? = null
    private var isExpanded = false
    private lateinit var editText: EditText

    private var bubbleX = 0
    private var bubbleY = 300
    private var notepadX = 0
    private var notepadY = 0
    private var notepadWidth = NOTEPAD_MIN_WIDTH
    private var notepadHeight = NOTEPAD_MIN_HEIGHT

    private var deleteOverlay: View? = null
    private var isDeleting = false

    private var screenWidth = 0
    private var screenHeight = 0

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        loadSavedPositions()

        createNotificationChannel()
        startForegroundServiceSafe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        if (bubbleView == null) {
            Handler(Looper.getMainLooper()).post { createBubble() }
        }

        return START_STICKY
    }

    // ================= NOTIFICATION FIX =================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "bubble_channel",
                "Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notification = Notification.Builder(this, "bubble_channel")
                .setContentTitle("Floating Notes")
                .setContentText("Active")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()

            startForeground(1001, notification)
        }
    }

    // ===================================================

    private fun loadSavedPositions() {
        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        bubbleX = prefs.getInt(STORAGE_BUBBLE_X, screenWidth - 120)
        bubbleY = prefs.getInt(STORAGE_BUBBLE_Y, 300)
        notepadX = prefs.getInt(STORAGE_NOTEPAD_X, 0)
        notepadY = prefs.getInt(STORAGE_NOTEPAD_Y, 0)
        notepadWidth = prefs.getInt(STORAGE_NOTEPAD_WIDTH, NOTEPAD_MIN_WIDTH)
        notepadHeight = prefs.getInt(STORAGE_NOTEPAD_HEIGHT, NOTEPAD_MIN_HEIGHT)
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        getSharedPreferences("notes_prefs", MODE_PRIVATE)
            .edit().putInt(STORAGE_BUBBLE_X, x).putInt(STORAGE_BUBBLE_Y, y).apply()
    }

    private fun createBubble() {

        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(BUBBLE_COLOR))
            }
        }

        val icon = TextView(this).apply {
            text = "📝"
            textSize = 28f
            setTextColor(Color.WHITE)
        }

        bubbleLayout.addView(icon)
        bubbleView = bubbleLayout

        val params = WindowManager.LayoutParams(
            120,
            120,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = bubbleX
        params.y = bubbleY

        try {
            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ================= DRAG =================
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY

                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()

                    try {
                        bubbleView?.let {
                            windowManager.updateViewLayout(it, params)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    saveBubblePosition(params.x, params.y)
                    expandToNotePad()
                    true
                }

                else -> false
            }
        }
    }

    // ================= NOTEPAD =================

    private fun expandToNotePad() {
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(NOTEPAD_BG_COLOR))
            setPadding(20, 20, 20, 20)
        }

        editText = EditText(this)
        layout.addView(editText)

        val params = WindowManager.LayoutParams(
            notepadWidth,
            notepadHeight,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.CENTER

        noteView = layout
        windowManager.addView(layout, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        noteView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?) = null
}