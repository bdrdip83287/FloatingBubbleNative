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
    
    // 📍 স্টোরেজ কী - পজিশন এবং সাইজ মনে রাখার জন্য
    private val STORAGE_BUBBLE_X = "bubble_x"
    private val STORAGE_BUBBLE_Y = "bubble_y"
    private val STORAGE_NOTEPAD_X = "notepad_x"
    private val STORAGE_NOTEPAD_Y = "notepad_y"
    private val STORAGE_NOTEPAD_WIDTH = "notepad_width"
    private val STORAGE_NOTEPAD_HEIGHT = "notepad_height"
    
    // ============================================================
    
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var noteView: View? = null
    private var isExpanded = false
    private lateinit var editText: EditText
    
    // বাবল পজিশন ট্র্যাকিং
    private var bubbleX = 0
    private var bubbleY = 0
    private var notepadX = 0
    private var notepadY = 0
    private var notepadWidth = NOTEPAD_MIN_WIDTH
    private var notepadHeight = NOTEPAD_MIN_HEIGHT
    
    // ডিলিট এর জন্য
    private var deleteOverlay: View? = null
    private var isDeleting = false
    
    // স্ক্রিন ডাইমেনশন
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        loadSavedPositions()
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

    // 📍 সেভ করা পজিশন লোড করুন
    private fun loadSavedPositions() {
        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        bubbleX = prefs.getInt(STORAGE_BUBBLE_X, getDefaultBubbleX())
        bubbleY = prefs.getInt(STORAGE_BUBBLE_Y, 300)
        notepadX = prefs.getInt(STORAGE_NOTEPAD_X, 0)
        notepadY = prefs.getInt(STORAGE_NOTEPAD_Y, 0)
        notepadWidth = prefs.getInt(STORAGE_NOTEPAD_WIDTH, NOTEPAD_MIN_WIDTH)
        notepadHeight = prefs.getInt(STORAGE_NOTEPAD_HEIGHT, NOTEPAD_MIN_HEIGHT)
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        prefs.edit().putInt(STORAGE_BUBBLE_X, x).putInt(STORAGE_BUBBLE_Y, y).apply()
        bubbleX = x
        bubbleY = y
    }

    private fun saveNotepadPosition(x: Int, y: Int, width: Int, height: Int) {
        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putInt(STORAGE_NOTEPAD_X, x)
            putInt(STORAGE_NOTEPAD_Y, y)
            putInt(STORAGE_NOTEPAD_WIDTH, width)
            putInt(STORAGE_NOTEPAD_HEIGHT, height)
            apply()
        }
        notepadX = x
        notepadY = y
        notepadWidth = width
        notepadHeight = height
    }

    private fun getDefaultBubbleX(): Int {
        return screenWidth - BUBBLE_SIZE - 20 // ডান পাশে
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
            BUBBLE_SIZE,
            BUBBLE_SIZE,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = bubbleX
        params.y = bubbleY

        loadNoteCount(countView)

        // 🖱️ ড্র্যাগ এবং ডিলিট এর জন্য টাচ লিসেনার
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    showDeleteOverlay()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                    }
                    params.x = (params.x + dx).toInt()
                    params.y = (params.y + dy).toInt()
                    windowManager.updateViewLayout(bubbleView!!, params)
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    
                    // চেক করুন নিচের দিকে নিয়ে যাচ্ছে কিনা
                    checkDeleteArea(params.y)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideDeleteOverlay()
                    if (!isDragging && abs(event.rawX - initialTouchX) < 10 && abs(event.rawY - initialTouchY) < 10) {
                        expandToNotePad()
                    } else {
                        if (isDeleting) {
                            stopSelf()
                        } else {
                            saveBubblePosition(params.x, params.y)
                        }
                    }
                    true
                }
            }
            false
        }

        bubbleView?.setOnLongClickListener {
            Toast.makeText(this, "Bubble closed", Toast.LENGTH_SHORT).show()
            stopSelf()
            true
        }

        windowManager.addView(bubbleView, params)
    }

    // 🗑️ ডিলিট এরিয়া দেখানোর জন্য
    private fun showDeleteOverlay() {
        if (deleteOverlay != null) return
        
        val overlay = TextView(this).apply {
            text = "🗑️ Drag here to delete"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#E74C3C"))
            gravity = Gravity.CENTER
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            100,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.BOTTOM
        params.y = 0
        
        windowManager.addView(overlay, params)
        deleteOverlay = overlay
        isDeleting = false
    }

    private fun hideDeleteOverlay() {
        deleteOverlay?.let {
            windowManager.removeView(it)
            deleteOverlay = null
        }
        isDeleting = false
    }

    private fun checkDeleteArea(y: Int) {
        isDeleting = (y + BUBBLE_SIZE) > (screenHeight - 150)
        
        // ভিজুয়াল ফিডব্যাক
        deleteOverlay?.setBackgroundColor(
            if (isDeleting) Color.parseColor("#C0392B")
            else Color.parseColor("#E74C3C")
        )
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
            notepadWidth,
            notepadHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = notepadX
        params.y = notepadY

        if (notepadX == 0 && notepadY == 0) {
            params.gravity = Gravity.CENTER
            params.x = 0
            params.y = 0
        }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 24f
            }
        }

        // Title bar
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val title = TextView(this).apply {
            text = NOTEPAD_TITLE
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(title)

        val minimizeBtn = TextView(this).apply {
            text = "−"
            textSize = 28f
            setTextColor(Color.parseColor("#C0392B"))
            setPadding(16, 0, 8, 0)
            setOnClickListener {
                collapseToBubble()
            }
        }
        titleBar.addView(minimizeBtn)
        container.addView(titleBar)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2).apply {
                topMargin = 16
                bottomMargin = 16
            }
        }
        container.addView(divider)

        // Edit Text
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

        // Buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
        }

        val saveBtn = Button(this).apply {
            text = "Save"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener { saveNote() }
        }
        buttonRow.addView(saveBtn)

        val clearBtn = Button(this).apply {
            text = "Clear"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
            setOnClickListener {
                val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
        container.addView(openAppBtn)

        // 🖱️ নোটপ্যাড ড্র্যাগ হ্যান্ডেল
        val dragHandle = TextView(this).apply {
            text = "⋮⋮"
            textSize = 24f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40).apply { topMargin = 8 }
            setOnTouchListener(NotepadDragListener())
        }
        container.addView(dragHandle)

        // Resize handle
        val resizeHandleView = TextView(this).apply {
            text = "◢"
            textSize = 20f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.END or Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40).apply { topMargin = 8 }
            setOnTouchListener(ResizeTouchListener())
        }
        container.addView(resizeHandleView)

        return container
    }

    // 🖱️ নোটপ্যাড ড্র্যাগ করার জন্য
    inner class NotepadDragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = noteView?.layoutParams?.x ?: 0
                    startY = noteView?.layoutParams?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    noteView?.layoutParams?.x = startX + dx.toInt()
                    noteView?.layoutParams?.y = startY + dy.toInt()
                    noteView?.let { windowManager.updateViewLayout(it, it.layoutParams) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    saveNotepadPosition(
                        noteView?.layoutParams?.x ?: 0,
                        noteView?.layoutParams?.y ?: 0,
                        notepadWidth,
                        notepadHeight
                    )
                    return true
                }
            }
            return false
        }
    }

    inner class ResizeTouchListener : View.OnTouchListener {
        private var startWidth = 0
        private var startHeight = 0
        private var startX = 0f
        private var startY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = notepadWidth
                    startHeight = notepadHeight
                    startX = event.rawX
                    startY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    
                    notepadWidth = (startWidth + dx).toInt().coerceIn(NOTEPAD_MIN_WIDTH, NOTEPAD_MAX_WIDTH)
                    notepadHeight = (startHeight + dy).toInt().coerceIn(NOTEPAD_MIN_HEIGHT, NOTEPAD_MAX_HEIGHT)
                    
                    noteView?.layoutParams?.width = notepadWidth
                    noteView?.layoutParams?.height = notepadHeight
                    noteView?.let { windowManager.updateViewLayout(it, it.layoutParams) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    saveNotepadPosition(
                        noteView?.layoutParams?.x ?: 0,
                        noteView?.layoutParams?.y ?: 0,
                        notepadWidth,
                        notepadHeight
                    )
                    return true
                }
            }
            return false
        }
    }

    private fun collapseToBubble() {
        if (!isExpanded) return
        
        // নোটপ্যাডের শেষ পজিশন সেভ করুন
        noteView?.let {
            saveNotepadPosition(
                it.layoutParams.x,
                it.layoutParams.y,
                notepadWidth,
                notepadHeight
            )
        }
        
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
        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        val count = prefs.getInt(STORAGE_NOTE_COUNT, 0)
        if (count > 0) {
            countView.text = count.toString()
            countView.visibility = View.VISIBLE
        } else {
            countView.visibility = View.GONE
        }
    }

    private fun updateNoteCount() {
        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        val count = prefs.getInt(STORAGE_NOTE_COUNT, 0)
        if (bubbleView != null) {
            val countView = (bubbleView as? LinearLayout)?.getChildAt(1) as? TextView
            if (countView != null && count > 0) {
                countView.text = count.toString()
                countView.visibility = View.VISIBLE
            } else if (countView != null) {
                countView.visibility = View.GONE
            }
        }
    }

    private fun saveNote() {
        val noteContent = editText.text.toString()
        if (noteContent.isNotEmpty()) {
            val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
            val currentCount = prefs.getInt(STORAGE_NOTE_COUNT, 0)
            prefs.edit().apply {
                putString(STORAGE_LAST_NOTE, noteContent)
                putInt(STORAGE_NOTE_COUNT, currentCount + 1)
                apply()
            }
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
        deleteOverlay?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?) = null
}
