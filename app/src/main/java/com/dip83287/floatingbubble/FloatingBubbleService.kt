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
import android.widget.*
import kotlin.math.abs

class FloatingBubbleService : Service() {

    // ============================================================
    // 🔧 কাস্টমাইজেশন সেকশন
    // ============================================================
    private val BUBBLE_COLOR = "#2196F3"
    private val BUBBLE_ICON = "📝"
    private val BUBBLE_SIZE = 80

    private val NOTEPAD_BG_COLOR = "#FFF8DC"
    private val NOTEPAD_TITLE = "📝 Floating Note"
    private val NOTEPAD_MIN_WIDTH = 300
    private val NOTEPAD_MIN_HEIGHT = 400
    private val NOTEPAD_MAX_WIDTH = 600
    private val NOTEPAD_MAX_HEIGHT = 800

    private val ANIMATION_DURATION = 300L

    private val STORAGE_NOTE_COUNT = "note_count"
    private val STORAGE_LAST_NOTE = "last_note"

    // ============================================================
    // মেমরি ভেরিয়েবল (পজিশন, সাইজ সেভ করার জন্য)
    // ============================================================
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

    // রিসাইজ করার জন্য ভেরিয়েবল
    private var isResizing = false
    private var resizeStartX = 0
    private var resizeStartY = 0
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0

    // delete zone
    private var isDraggingToDelete = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadSavedPositions()
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

        // ডিফল্ট পজিশন (ডান পাশে, ১৫০px নিচে)
        val defaultX = prefs.getInt(KEY_BUBBLE_X, (resources.displayMetrics.widthPixels - BUBBLE_SIZE) / 2)
        val defaultY = prefs.getInt(KEY_BUBBLE_Y, 150)
        params.x = defaultX
        params.y = defaultY

        loadNoteCount(countView)

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

                        // delete zone detection (screen bottom)
                        val screenHeight = resources.displayMetrics.heightPixels
                        if (params.y + BUBBLE_SIZE > screenHeight - 100) {
                            isDraggingToDelete = true
                            showDeleteWarning()
                        } else {
                            isDraggingToDelete = false
                            hideDeleteWarning()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDraggingToDelete) {
                            deleteBubble()
                            return true
                        }
                        if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) {
                            expandToNotePad()
                        } else {
                            saveBubblePosition(params.x, params.y)
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

    private fun showDeleteWarning() {
        // এখানে ইচ্ছে করলে টোস্ট বা ভিউ দেখাতে পারেন
    }

    private fun hideDeleteWarning() {
        // warning hide
    }

    private fun deleteBubble() {
        stopSelf()
    }

    private fun expandToNotePad() {
        if (isExpanded) return

        bubbleView?.animate()
            ?.scaleX(0.5f)
            ?.scaleY(0.5f)
            ?.alpha(0f)
            ?.setDuration(ANIMATION_DURATION)
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
        noteView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(ANIMATION_DURATION)
            ?.start()
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
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val title = TextView(this).apply {
            text = NOTEPAD_TITLE
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
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
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 2)
            (layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 16
                bottomMargin = 16
            }
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
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            (layoutParams as LinearLayout.LayoutParams).topMargin = 16
        }

        val saveBtn = Button(this).apply {
            text = "Save"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 8
            setOnClickListener { saveNote() }
        }
        buttonRow.addView(saveBtn)

        val clearBtn = Button(this).apply {
            text = "Clear"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
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
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            (layoutParams as LinearLayout.LayoutParams).topMargin = 8
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
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 40)
            (layoutParams as LinearLayout.LayoutParams).topMargin = 8
            setOnTouchListener(ResizeTouchListener())
        }
        container.addView(resizeHandleView)

        return container
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
            ?.scaleX(0.5f)
            ?.scaleY(0.5f)
            ?.setDuration(ANIMATION_DURATION)
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
    }

    override fun onBind(intent: Intent?) = null
}