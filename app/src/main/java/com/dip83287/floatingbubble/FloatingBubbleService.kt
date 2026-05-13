package com.dip83287.floatingbubble

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import com.dip83287.floatingbubble.utils.EmergencyLog
import kotlin.math.abs

class FloatingBubbleService : Service() {

    // ============================================================
    // 🔧 কাস্টমাইজেশন সেকশন
    // ============================================================
    private val BUBBLE_COLOR = "#808080"
    private val NOTEPAD_BG_COLOR = "#808080"
    private val BUBBLE_ICON = "📝"
    private val BUBBLE_SIZE = 80

    private val NOTEPAD_TITLE = "📝 Floating Note"
    private val NOTEPAD_MIN_WIDTH = 300
    private val NOTEPAD_MIN_HEIGHT = 400
    private val NOTEPAD_MAX_WIDTH = 600
    private val NOTEPAD_MAX_HEIGHT = 800

    private val ANIMATION_DURATION = 250L
    private val SPRING_ANIMATION_DURATION = 300L

    private val STORAGE_NOTE_COUNT = "note_count"
    private val STORAGE_LAST_NOTE = "last_note"

    // মেমরি ভেরিয়েবল
    private lateinit var prefs: SharedPreferences
    private val PREFS_NAME = "bubble_prefs"
    private val KEY_BUBBLE_X = "bubble_x"
    private val KEY_BUBBLE_Y = "bubble_y"
    private val KEY_NOTEPAD_WIDTH = "notepad_width"
    private val KEY_NOTEPAD_HEIGHT = "notepad_height"
    private val KEY_NOTEPAD_X = "notepad_x"
    private val KEY_NOTEPAD_Y = "notepad_y"

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var noteView: View? = null
    private var isExpanded = false
    private lateinit var editText: EditText
    private var currentNotepadWidth = NOTEPAD_MIN_WIDTH
    private var currentNotepadHeight = NOTEPAD_MIN_HEIGHT
    private var notepadPosX = 0
    private var notepadPosY = 0

    private var isResizing = false
    private var resizeStartX = 0
    private var resizeStartY = 0
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0

    private var deleteZoneView: View? = null
    private var isDraggingToDelete = false
    private var springAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        EmergencyLog.logFlow("FloatingBubbleService", "onCreate", "START")
        
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            loadSavedPositions()
            createNotificationChannel()
            startForeground(1001, createNotification())
            createDeleteZone()
            EmergencyLog.logFlow("FloatingBubbleService", "onCreate", "SUCCESS")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "FloatingBubbleService.onCreate")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    "floating_bubble_channel",
                    "Floating Bubble",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps floating bubble alive"
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
                EmergencyLog.log("Notification channel created")
            } catch (e: Exception) {
                EmergencyLog.logException(e, "createNotificationChannel")
            }
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "floating_bubble_channel")
            .setContentTitle("Floating Notes")
            .setContentText("Tap bubble to write note")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun loadSavedPositions() {
        currentNotepadWidth = prefs.getInt(KEY_NOTEPAD_WIDTH, NOTEPAD_MIN_WIDTH)
        currentNotepadHeight = prefs.getInt(KEY_NOTEPAD_HEIGHT, NOTEPAD_MIN_HEIGHT)
        notepadPosX = prefs.getInt(KEY_NOTEPAD_X, 0)
        notepadPosY = prefs.getInt(KEY_NOTEPAD_Y, 0)
        EmergencyLog.log("Positions loaded")
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
        EmergencyLog.log("Bubble position saved: ($x,$y)")
    }

    private fun saveNotepadSizeAndPosition(width: Int, height: Int, x: Int, y: Int) {
        prefs.edit().putInt(KEY_NOTEPAD_WIDTH, width)
            .putInt(KEY_NOTEPAD_HEIGHT, height)
            .putInt(KEY_NOTEPAD_X, x)
            .putInt(KEY_NOTEPAD_Y, y).apply()
        currentNotepadWidth = width
        currentNotepadHeight = height
        notepadPosX = x
        notepadPosY = y
        EmergencyLog.log("Notepad saved: ${width}x${height}")
    }

    private fun createDeleteZone() {
        try {
            val zone = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.RED)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.RED)
                }
                background = shape
                setPadding(25, 25, 25, 25)
            }

            val cross = TextView(this).apply {
                text = "✕"
                textSize = 45f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            zone.addView(cross)

            val params = WindowManager.LayoutParams(
                100, 100,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = 40
            zone.visibility = View.GONE
            deleteZoneView = zone
            windowManager.addView(deleteZoneView, params)
            EmergencyLog.log("Delete zone created at bottom +40px")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createDeleteZone")
        }
    }

    private fun showDeleteZone() {
        deleteZoneView?.visibility = View.VISIBLE
        EmergencyLog.logFlow("DeleteZone", "shown")
    }

    private fun hideDeleteZone() {
        deleteZoneView?.visibility = View.GONE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EmergencyLog.logFlow("FloatingBubbleService", "onStartCommand", "START")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                EmergencyLog.logError("Overlay permission missing", null)
                stopSelf()
                return START_NOT_STICKY
            }

            if (bubbleView == null) {
                Handler(Looper.getMainLooper()).post { createBubble() }
            }
            EmergencyLog.logFlow("FloatingBubbleService", "onStartCommand", "SUCCESS")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "onStartCommand")
        }

        return START_STICKY
    }

    private fun createBubble() {
        EmergencyLog.logFlow("createBubble", "START")
        
        try {
            val bubbleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(20, 20, 20, 20)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(BUBBLE_COLOR))
                }
            }

            val iconView = TextView(this).apply {
                text = BUBBLE_ICON
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            bubbleLayout.addView(iconView)

            val countView = TextView(this).apply {
                text = "0"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.RED)
                setPadding(6, 3, 6, 3)
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            bubbleLayout.addView(countView)

            bubbleView = bubbleLayout

            val params = WindowManager.LayoutParams(
                BUBBLE_SIZE, BUBBLE_SIZE,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START

            val displayMetrics = resources.displayMetrics
            val defaultX = prefs.getInt(KEY_BUBBLE_X, displayMetrics.widthPixels - BUBBLE_SIZE - 20)
            val defaultY = prefs.getInt(KEY_BUBBLE_Y, 150)
            params.x = defaultX
            params.y = defaultY

            loadNoteCount(countView)
            setupBubbleTouchListener(params, displayMetrics)
            setupBubbleLongClickListener()

            windowManager.addView(bubbleView, params)
            EmergencyLog.logFlow("createBubble", "SUCCESS")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createBubble")
        }
    }

    private fun setupBubbleTouchListener(params: WindowManager.LayoutParams, displayMetrics: android.util.DisplayMetrics) {
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        isDraggingToDelete = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(bubbleView!!, params)

                        // Delete zone detection (40px from bottom)
                        val screenHeight = displayMetrics.heightPixels
                        if (params.y + BUBBLE_SIZE > screenHeight - 100) {
                            if (!isDraggingToDelete) {
                                isDraggingToDelete = true
                                showDeleteZone()
                            }
                        } else {
                            if (isDraggingToDelete) {
                                isDraggingToDelete = false
                                hideDeleteZone()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        hideDeleteZone()
                        if (isDraggingToDelete) {
                            EmergencyLog.log("Bubble deleted via delete zone")
                            deleteBubble()
                            return true
                        }
                        if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) {
                            expandToNotePad()
                        } else {
                            saveBubblePosition(params.x, params.y)
                            springToEdge(params)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupBubbleLongClickListener() {
        bubbleView?.setOnLongClickListener {
            EmergencyLog.log("Bubble closed by long press")
            stopSelf()
            true
        }
    }

    private fun springToEdge(params: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val edgeDistance = 50
        val targetX: Int
        val startX = params.x

        if (params.x + BUBBLE_SIZE / 2 > screenWidth / 2) {
            targetX = screenWidth - BUBBLE_SIZE - edgeDistance
        } else {
            targetX = edgeDistance
        }

        if (targetX != startX) {
            springAnimator?.cancel()
            springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SPRING_ANIMATION_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    params.x = (startX + (targetX - startX) * fraction).toInt()
                    windowManager.updateViewLayout(bubbleView!!, params)
                }
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {}
                    override fun onAnimationEnd(animation: Animator) {
                        saveBubblePosition(params.x, params.y)
                    }
                    override fun onAnimationCancel(animation: Animator) {}
                    override fun onAnimationRepeat(animation: Animator) {}
                })
                start()
            }
        }
    }

    private fun deleteBubble() {
        EmergencyLog.log("Bubble service stopping")
        stopSelf()
    }

    // 🔥 Smooth Expand - NO SPLASH effect
    private fun expandToNotePad() {
        if (isExpanded) return
        EmergencyLog.logFlow("expandToNotePad", "START")

        // First, smoothly fade out and scale down bubble
        bubbleView?.animate()
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.alpha(0f)
            ?.setDuration(ANIMATION_DURATION)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.withEndAction {
                bubbleView?.let { windowManager.removeView(it) }
                bubbleView = null
                showNotePad()
                EmergencyLog.logFlow("expandToNotePad", "COMPLETE")
            }
            ?.start()
    }

    private fun showNotePad() {
        if (noteView != null) return
        isExpanded = true
        EmergencyLog.logFlow("showNotePad", "START")

        try {
            val container = createResizableNotePad()
            noteView = container

            val params = WindowManager.LayoutParams(
                currentNotepadWidth, currentNotepadHeight,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = notepadPosX
            params.y = notepadPosY

            windowManager.addView(noteView, params)

            // Smooth fade-in + scale animation from center
            noteView?.scaleX = 0.3f
            noteView?.scaleY = 0.3f
            noteView?.alpha = 0f
            noteView?.pivotX = (noteView?.width?.div(2f) ?: 0f)
            noteView?.pivotY = (noteView?.height?.div(2f) ?: 0f)
            noteView?.animate()
                ?.alpha(1f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(ANIMATION_DURATION)
                ?.setInterpolator(AccelerateDecelerateInterpolator())
                ?.start()

            EmergencyLog.logFlow("showNotePad", "SUCCESS")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "showNotePad")
        }
    }

    private fun createResizableNotePad(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(NOTEPAD_BG_COLOR))
            setPadding(24, 24, 24, 24)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = 24f
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnTouchListener(TitleBarDragListener())
        }

        val title = TextView(this).apply {
            text = NOTEPAD_TITLE
            textSize = 18f
            setTextColor(Color.parseColor("#FFFFFF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(title)

        val minimizeBtn = TextView(this).apply {
            text = "−"
            textSize = 28f
            setTextColor(Color.parseColor("#C0392B"))
            setPadding(16, 0, 8, 0)
            setOnClickListener { collapseToBubble() }
        }
        titleBar.addView(minimizeBtn)
        container.addView(titleBar)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            )
            lp.topMargin = 16
            lp.bottomMargin = 16
            layoutParams = lp
        }
        container.addView(divider)

        editText = EditText(this).apply {
            hint = "Write your note here..."
            minHeight = 250
            gravity = Gravity.TOP
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        container.addView(editText)

        editText.setText(getSharedPreferences("notes_prefs", MODE_PRIVATE).getString(STORAGE_LAST_NOTE, ""))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 16
            layoutParams = lp
        }

        val saveBtn = Button(this).apply {
            text = "Save"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = 8
            layoutParams = lp
            setOnClickListener { saveNote() }
        }
        buttonRow.addView(saveBtn)

        val clearBtn = Button(this).apply {
            text = "Clear"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnClickListener {
                editText.setText("")
                Toast.makeText(this@FloatingBubbleService, "Cleared", Toast.LENGTH_SHORT).show()
            }
        }
        buttonRow.addView(clearBtn)
        container.addView(buttonRow)

        val openAppBtn = Button(this).apply {
            text = "Open Full App"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 8
            layoutParams = lp
            setOnClickListener {
                val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
        container.addView(openAppBtn)

        val resizeHandleView = TextView(this).apply {
            text = "◢"
            textSize = 20f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.END or Gravity.BOTTOM
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 40
            )
            lp.topMargin = 8
            layoutParams = lp
            setOnTouchListener(ResizeTouchListener())
        }
        container.addView(resizeHandleView)

        return container
    }

    inner class TitleBarDragListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = (noteView?.layoutParams as WindowManager.LayoutParams).x
                    initialY = (noteView?.layoutParams as WindowManager.LayoutParams).y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    val params = noteView?.layoutParams as WindowManager.LayoutParams
                    if (params != null) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(noteView, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val params = noteView?.layoutParams as WindowManager.LayoutParams
                    if (params != null) {
                        saveNotepadSizeAndPosition(
                            currentNotepadWidth, currentNotepadHeight,
                            params.x, params.y
                        )
                    }
                    return true
                }
            }
            return false
        }
    }

    inner class ResizeTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isResizing = true
                    resizeStartX = event.rawX.toInt()
                    resizeStartY = event.rawY.toInt()
                    resizeStartWidth = currentNotepadWidth
                    resizeStartHeight = currentNotepadHeight
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) {
                        val dx = event.rawX.toInt() - resizeStartX
                        val dy = event.rawY.toInt() - resizeStartY
                        val newWidth = (resizeStartWidth + dx).coerceIn(NOTEPAD_MIN_WIDTH, NOTEPAD_MAX_WIDTH)
                        val newHeight = (resizeStartHeight + dy).coerceIn(NOTEPAD_MIN_HEIGHT, NOTEPAD_MAX_HEIGHT)
                        if (newWidth != currentNotepadWidth || newHeight != currentNotepadHeight) {
                            currentNotepadWidth = newWidth
                            currentNotepadHeight = newHeight
                            noteView?.layoutParams?.width = currentNotepadWidth
                            noteView?.layoutParams?.height = currentNotepadHeight
                            windowManager.updateViewLayout(noteView, noteView?.layoutParams)
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isResizing = false
                    val params = noteView?.layoutParams as WindowManager.LayoutParams
                    if (params != null) {
                        saveNotepadSizeAndPosition(
                            currentNotepadWidth, currentNotepadHeight,
                            params.x, params.y
                        )
                    }
                    return true
                }
            }
            return false
        }
    }

    // 🔥 Smooth Collapse - NO SPLASH effect
    private fun collapseToBubble() {
        if (!isExpanded) return
        EmergencyLog.logFlow("collapseToBubble", "START")

        val params = noteView?.layoutParams as WindowManager.LayoutParams
        if (params != null) {
            saveNotepadSizeAndPosition(currentNotepadWidth, currentNotepadHeight, params.x, params.y)
        }

        // First, smoothly fade out and scale down notepad
        noteView?.animate()
            ?.alpha(0f)
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.setDuration(ANIMATION_DURATION)
            ?.setInterpolator(AccelerateDecelerateInterpolator())
            ?.withEndAction {
                noteView?.let { windowManager.removeView(it) }
                noteView = null
                isExpanded = false
                createBubble()
                // Bubble appears with smooth scale animation
                bubbleView?.alpha = 0f
                bubbleView?.scaleX = 0.3f
                bubbleView?.scaleY = 0.3f
                bubbleView?.animate()
                    ?.alpha(1f)
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(ANIMATION_DURATION)
                    ?.setInterpolator(AccelerateDecelerateInterpolator())
                    ?.start()
                EmergencyLog.logFlow("collapseToBubble", "COMPLETE")
            }
            ?.start()
    }

    private fun loadNoteCount(countView: TextView) {
        val count = getSharedPreferences("notes_prefs", MODE_PRIVATE).getInt(STORAGE_NOTE_COUNT, 0)
        if (count > 0) {
            countView.text = count.toString()
            countView.visibility = View.VISIBLE
        }
    }

    private fun updateNoteCount() {
        val count = getSharedPreferences("notes_prefs", MODE_PRIVATE).getInt(STORAGE_NOTE_COUNT, 0)
        (bubbleView as? LinearLayout)?.getChildAt(1)?.let { (it as TextView).text = count.toString() }
    }

    private fun saveNote() {
        val noteContent = editText.text.toString()
        if (noteContent.isNotEmpty()) {
            val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
            val currentCount = prefs.getInt(STORAGE_NOTE_COUNT, 0)
            prefs.edit().putString(STORAGE_LAST_NOTE, noteContent).putInt(STORAGE_NOTE_COUNT, currentCount + 1).apply()
            updateNoteCount()
            Toast.makeText(this, "Note saved! Total: ${currentCount + 1}", Toast.LENGTH_SHORT).show()
            editText.setText("")
            EmergencyLog.log("Note saved, total count: ${currentCount + 1}")
        } else {
            Toast.makeText(this, "Please write something", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        EmergencyLog.logFlow("FloatingBubbleService", "onDestroy", "Service shutting down")
        springAnimator?.cancel()
        bubbleView?.let { windowManager.removeView(it) }
        noteView?.let { windowManager.removeView(it) }
        deleteZoneView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
