package com.dip83287.floatingbubble

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
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private val CHANNEL_ID = "floating_bubble_channel"
    private val NOTIFICATION_ID = 1001

    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var noteView: View? = null
    private var isExpanded = false
    private lateinit var editText: EditText
    private var currentNotepadWidth = 300
    private var currentNotepadHeight = 400
    private var notepadPosX = 0
    private var notepadPosY = 0

    private var isResizing = false
    private var resizeStartX = 0
    private var resizeStartY = 0
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0

    private var deleteZoneView: View? = null
    private var isDraggingToDelete = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE)
        loadSavedPositions()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        createDeleteZone()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps floating bubble alive"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Notes")
            .setContentText("Tap bubble to write note")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun loadSavedPositions() {
        currentNotepadWidth = prefs.getInt("notepad_width", 300)
        currentNotepadHeight = prefs.getInt("notepad_height", 400)
        notepadPosX = prefs.getInt("notepad_x", 0)
        notepadPosY = prefs.getInt("notepad_y", 0)
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        prefs.edit().putInt("bubble_x", x).putInt("bubble_y", y).apply()
    }

    private fun saveNotepadSizeAndPosition(width: Int, height: Int, x: Int, y: Int) {
        prefs.edit()
            .putInt("notepad_width", width)
            .putInt("notepad_height", height)
            .putInt("notepad_x", x)
            .putInt("notepad_y", y).apply()
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

        return START_STICKY
    }

    private fun createBubble() {
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
            80, 80,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val displayMetrics = resources.displayMetrics
        val defaultX = prefs.getInt("bubble_x", displayMetrics.widthPixels - 100)
        val defaultY = prefs.getInt("bubble_y", 150)
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

                        val screenHeight = displayMetrics.heightPixels
                        if (params.y + 80 > screenHeight - 150) {
                            isDraggingToDelete = true
                            showDeleteZone()
                        } else {
                            isDraggingToDelete = false
                            hideDeleteZone()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        hideDeleteZone()
                        if (isDraggingToDelete) {
                            deleteBubble()
                            return true
                        }
                        if (abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) {
                            expandToNotePad()
                        } else {
                            saveBubblePosition(params.x, params.y)
                        }
                        springToEdge(params)
                        return true
                    }
                }
                return false
            }
        })

        bubbleView?.setOnLongClickListener {
            stopSelf()
            true
        }

        windowManager.addView(bubbleView, params)
    }

    private fun springToEdge(params: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val edgeDistance = 50
        var newX = params.x

        if (params.x + 40 > screenWidth / 2) {
            newX = screenWidth - 80 - edgeDistance
        } else {
            newX = edgeDistance
        }

        if (newX != params.x) {
            params.x = newX
            windowManager.updateViewLayout(bubbleView, params)
            saveBubblePosition(params.x, params.y)
        }
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
            ?.setDuration(250)
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
            ?.setDuration(250)
            ?.start()
    }

    private fun createResizableNotePad(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#808080"))
            setPadding(24, 24, 24, 24)
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
            text = "📝 Floating Note"
            textSize = 18f
            setTextColor(Color.WHITE)
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
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
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

        editText.setText(prefs.getString("last_note", ""))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
            }
        }
        buttonRow.addView(clearBtn)
        container.addView(buttonRow)

        val openAppBtn = Button(this).apply {
            text = "Open Full App"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40)
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
                        val newWidth = (resizeStartWidth + dx).coerceIn(300, 600)
                        val newHeight = (resizeStartHeight + dy).coerceIn(400, 800)
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

        val currentX = (noteView?.layoutParams as WindowManager.LayoutParams).x
        val currentY = (noteView?.layoutParams as WindowManager.LayoutParams).y
        saveNotepadSizeAndPosition(currentNotepadWidth, currentNotepadHeight, currentX, currentY)

        noteView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.5f)
            ?.scaleY(0.5f)
            ?.setDuration(250)
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
                    ?.setDuration(250)
                    ?.start()
            }
            ?.start()
    }

    private fun loadNoteCount(countView: TextView) {
        val count = getSharedPreferences("notes_prefs", MODE_PRIVATE).getInt("note_count", 0)
        if (count > 0) {
            countView.text = count.toString()
            countView.visibility = View.VISIBLE
        } else {
            countView.visibility = View.GONE
        }
    }

    private fun updateNoteCount() {
        val count = getSharedPreferences("notes_prefs", MODE_PRIVATE).getInt("note_count", 0)
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
            val currentCount = prefs.getInt("note_count", 0)
            prefs.edit()
                .putString("last_note", noteContent)
                .putInt("note_count", currentCount + 1).apply()
            updateNoteCount()
            editText.setText("")
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
