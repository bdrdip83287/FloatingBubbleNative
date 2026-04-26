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
    // 🔧 কাস্টমাইজেশন সেকশন - এখানে পরিবর্তন করলেই হবে
    // ============================================================
    
    // বাবল সেটিংস
    private val BUBBLE_COLOR = "#2196F3"        // বাবলের রং (নীল)
    private val BUBBLE_ICON = "📝"               // বাবলের আইকন
    private val BUBBLE_SIZE = 140                // বাবলের সাইজ (px)
    
    // নোটপ্যাড সেটিংস
    private val NOTEPAD_BG_COLOR = "#FFF8DC"     // নোটপ্যাডের ব্যাকগ্রাউন্ড (ক্রিম)
    private val NOTEPAD_TITLE = "📝 Floating Note"
    private val NOTEPAD_MIN_WIDTH = 300          // নোটপ্যাডের ন্যূনতম প্রস্থ
    private val NOTEPAD_MIN_HEIGHT = 400         // নোটপ্যাডের ন্যূনতম উচ্চতা
    private val NOTEPAD_MAX_WIDTH = 600          // নোটপ্যাডের সর্বোচ্চ প্রস্থ
    private val NOTEPAD_MAX_HEIGHT = 800         // নোটপ্যাডের সর্বোচ্চ উচ্চতা
    
    // অ্যানিমেশন ডিউরেশন
    private val ANIMATION_DURATION = 300L        // মিলিসেকেন্ড
    
    // স্টোরেজ কী
    private val STORAGE_NOTE_COUNT = "note_count"
    private val STORAGE_LAST_NOTE = "last_note"
    
    // ============================================================
    // কোডের বাকি অংশ
    // ============================================================
    
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var noteView: View? = null
    private var isExpanded = false
    private lateinit var editText: EditText
    private var currentWidth = NOTEPAD_MIN_WIDTH
    private var currentHeight = NOTEPAD_MIN_HEIGHT
    
    // রিসাইজ করার জন্য ভেরিয়েবল
    private var isResizing = false
    private var resizeStartX = 0
    private var resizeStartY = 0
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = -12
            }
        }
        bubbleLayout.addView(countView)

        bubbleView = bubbleLayout

        val params = WindowManager.LayoutParams(
            BUBBLE_SIZE,
            BUBBLE_SIZE,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        loadNoteCount(countView)

        // ড্র্যাগ লজিক
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
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(bubbleView!!, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) {
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

    // 🎬 এক্সপান্ড (স্মুথ ট্রানজিশন সহ)
    private fun expandToNotePad() {
        if (isExpanded) return
        
        // স্মুথ হাইড বাবল এনিমেশন
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
            currentWidth,
            currentHeight,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.CENTER
        params.x = 0
        params.y = 0

        windowManager.addView(noteView, params)
        
        // ফেড ইন এনিমেশন
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

        // Title bar with minimize button
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = NOTEPAD_TITLE
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
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

        // Load saved note
        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        editText.setText(prefs.getString(STORAGE_LAST_NOTE, ""))

        // Buttons row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        val saveBtn = Button(this).apply {
            text = "Save"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener { saveNote() }
        }
        buttonRow.addView(saveBtn)

        val clearBtn = Button(this).apply {
            text = "Clear"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                editText.setText("")
                Toast.makeText(this@FloatingBubbleService, "Cleared", Toast.LENGTH_SHORT).show()
            }
        }
        buttonRow.addView(clearBtn)
        container.addView(buttonRow)

        // Open app button
        val openAppBtn = Button(this).apply {
            text = "Open Full App"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            setOnClickListener {
                val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
        container.addView(openAppBtn)

        // রিসাইজ হ্যান্ডেল (নিচের ডান কোণায়)
        val resizeHandleView = TextView(this).apply {
            text = "◢"
            textSize = 20f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.END or Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                40
            ).apply {
                topMargin = 8
            }
            setOnTouchListener(ResizeTouchListener())
        }
        container.addView(resizeHandleView)

        return container
    }

    // 🔄 রিসাইজ টাচ লিসেনার
    inner class ResizeTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isResizing = true
                    resizeStartX = event.rawX.toInt()
                    resizeStartY = event.rawY.toInt()
                    resizeStartWidth = currentWidth
                    resizeStartHeight = currentHeight
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) {
                        val dx = event.rawX.toInt() - resizeStartX
                        val dy = event.rawY.toInt() - resizeStartY
                        
                        var newWidth = (resizeStartWidth + dx).coerceIn(NOTEPAD_MIN_WIDTH, NOTEPAD_MAX_WIDTH)
                        var newHeight = (resizeStartHeight + dy).coerceIn(NOTEPAD_MIN_HEIGHT, NOTEPAD_MAX_HEIGHT)
                        
                        if (newWidth != currentWidth || newHeight != currentHeight) {
                            currentWidth = newWidth
                            currentHeight = newHeight
                            noteView?.layoutParams?.width = currentWidth
                            noteView?.layoutParams?.height = currentHeight
                            windowManager.updateViewLayout(noteView, noteView?.layoutParams)
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isResizing = false
                    return true
                }
            }
            return false
        }
    }

    // 🎬 কোলাপস (স্মুথ ট্রানজিশন সহ)
    private fun collapseToBubble() {
        if (!isExpanded) return
        
        // ফেড আউট এনিমেশন
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
                
                // বাবল ফেড ইন এনিমেশন
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
    }

    override fun onBind(intent: Intent?) = null
}
