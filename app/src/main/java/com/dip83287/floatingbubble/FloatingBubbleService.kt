package com.dip83287.floatingbubble

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.*
import android.widget.*
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

    private val ANIMATION_DURATION = 400L

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

    // স্প্রিং অ্যানিমেশনের জন্য
    private val springInterpolator = OvershootInterpolator(2f)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadSavedPositions()
        createDeleteZone()
    }

    private fun loadSavedPositions() {
        currentNotepadWidth = prefs.getInt(KEY_NOTEPAD_WIDTH, NOTEPAD_MIN_WIDTH)
        currentNotepadHeight = prefs.getInt(KEY_NOTEPAD_HEIGHT, NOTEPAD_MIN_HEIGHT)
        notepadPosX = prefs.getInt(KEY_NOTEPAD_X, 0)
        notepadPosY = prefs.getInt(KEY_NOTEPAD_Y, 0)
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
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
    }

    private fun createDeleteZone() {
        val zone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.RED)
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
            background = shape
            setPadding(20, 20, 20, 20)
        }

        val cross = TextView(this).apply {
            text = "✕"
            textSize = 40f
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
        params.y = 50
        zone.visibility = View.GONE
        deleteZoneView = zone
        windowManager.addView(deleteZoneView, params)
    }

    private fun showDeleteZone() {
        deleteZoneView?.visibility = View.VISIBLE
    }

    private fun hideDeleteZone() {
        deleteZoneView?.visibility = View.GONE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        // সার্ভিসকে স্টিকি করে রাখা
        return START_STICKY
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

        var isDragging = false
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        isDragging = false
                        isDraggingToDelete = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView!!, params)

                        val screenHeight = displayMetrics.heightPixels
                        if (params.y + BUBBLE_SIZE > screenHeight - 150) {
                            isDraggingToDelete = true
                            showDeleteZone()
                        } else {
                            isDraggingToDelete = false
                            hideDeleteZone()
                        }

                        if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        hideDeleteZone()
                        if (isDraggingToDelete) {
                            deleteBubble()
                            return true
                        }
                        if (isDragging) {
                            // স্প্রিং এফেক্ট সহ ডকিং
                            val finalX = params.x.coerceIn(0, displayMetrics.widthPixels - BUBBLE_SIZE)
                            val finalY = params.y.coerceIn(0, displayMetrics.heightPixels - BUBBLE_SIZE)
                            animateBubbleToPosition(finalX, finalY)
                            saveBubblePosition(finalX, finalY)
                        } else if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) {
                            expandToNotePad()
                        }
                        return true
                    }
                }
                return false
            }
        })

        bubbleView?.setOnLongClickListener {
            Toast.makeText(this, "Bubble closed", Toast.LENGTH_SHORT).show()
            stopSelf()
            true
        }

        windowManager.addView(bubbleView, params)
    }

    private fun animateBubbleToPosition(x: Int, y: Int) {
        val params = bubbleView?.layoutParams as WindowManager.LayoutParams
        val startX = params.x
        val startY = params.y
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION
            interpolator = springInterpolator
            addUpdateListener {
                val progress = it.animatedValue as Float
                params.x = (startX + (x - startX) * progress).toInt()
                params.y = (startY + (y - startY) * progress).toInt()
                windowManager.updateViewLayout(bubbleView, params)
            }
            start()
        }
    }

    private fun deleteBubble() {
        stopSelf()
    }

    private fun expandToNotePad() {
        if (isExpanded) return

        bubbleView?.animate()
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.alpha(0f)
            ?.setDuration(ANIMATION_DURATION)
            ?.interpolator = springInterpolator
        bubbleView?.animate()
            ?.withEndAction {
                bubbleView?.let { windowManager.removeView(it) }
                bubbleView = null
                showNotePad()
            }
            ?.start()
    }

    private fun showNotePad() {
        if (noteView != null) return
        isExpanded = true

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

        noteView?.alpha = 0f
        noteView?.scaleX = 0.7f
        noteView?.scaleY = 0.7f
        noteView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(ANIMATION_DURATION)
            ?.interpolator = springInterpolator
            ?.start()
    }

    private fun createResizableNotePad(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(NOTEPAD_BG_COLOR))
            setPadding(24, 24, 24, 24)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = 24f
        }

        // টাইটেল বার (ড্র্যাগ হ্যান্ডেল)
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
            setTextColor(Color.parseColor("#333333"))
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

        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        editText.setText(prefs.getString(STORAGE_LAST_NOTE, ""))

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
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(noteView, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    saveNotepadSizeAndPosition(
                        currentNotepadWidth,
                        currentNotepadHeight,
                        (noteView?.layoutParams as WindowManager.LayoutParams).x,
                        (noteView?.layoutParams as WindowManager.LayoutParams).y
                    )
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
                    saveNotepadSizeAndPosition(
                        currentNotepadWidth,
                        currentNotepadHeight,
                        (noteView?.layoutParams as WindowManager.LayoutParams).x,
                        (noteView?.layoutParams as WindowManager.LayoutParams).y
                    )
                    return true
                }
            }
            return false
        }
    }

    private fun collapseToBubble() {
        if (!isExpanded) return

        val currentNotepadX = (noteView?.layoutParams as WindowManager.LayoutParams).x
        val currentNotepadY = (noteView?.layoutParams as WindowManager.LayoutParams).y
        saveNotepadSizeAndPosition(currentNotepadWidth, currentNotepadHeight, currentNotepadX, currentNotepadY)

        noteView?.animate()
            ?.alpha(0f)
            ?.scaleX(0f)
            ?.scaleY(0f)
            ?.setDuration(ANIMATION_DURATION)
            ?.interpolator = springInterpolator
        noteView?.animate()
            ?.withEndAction {
                noteView?.let { windowManager.removeView(it) }
                noteView = null
                isExpanded = false
                createBubble()
                bubbleView?.alpha = 0f
                bubbleView?.scaleX = 0.5f
                bubbleView?.scaleY = 0.5f
                bubbleView?.animate()
                    ?.alpha(1f)
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(ANIMATION_DURATION)
                    ?.interpolator = springInterpolator
                    ?.start()
            }
            ?.start()
    }

    private fun loadNoteCount(countView: TextView) {
        val count = getSharedPreferences("notes_prefs", MODE_PRIVATE).getInt(STORAGE_NOTE_COUNT, 0)
        if (count > 0) {
            countView.text = count.toString()
            countView.visibility = View.VISIBLE
        } else {
            countView.visibility = View.GONE
        }
    }

    private fun updateNoteCount() {
        val count = getSharedPreferences("notes_prefs", MODE_PRIVATE).getInt(STORAGE_NOTE_COUNT, 0)
        (bubbleView as? LinearLayout)?.getChildAt(1)?.let {
            (it as TextView).apply {
                text = count.toString()
                visibility = if (count > 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun saveNote() {
        val noteContent = editText.text.toString()
        if (noteContent.isNotEmpty()) {
            val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
            val currentCount = prefs.getInt(STORAGE_NOTE_COUNT, 0)
            prefs.edit().putString(STORAGE_LAST_NOTE, noteContent)
                .putInt(STORAGE_NOTE_COUNT, currentCount + 1).apply()
            updateNoteCount()
            Toast.makeText(this, "Note saved! Total: ${currentCount + 1}", Toast.LENGTH_SHORT).show()
            editText.setText("")
        } else {
            Toast.makeText(this, "Please write something", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        noteView?.let { windowManager.removeView(it) }
        deleteZoneView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?) = null
}
