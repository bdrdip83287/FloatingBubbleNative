package com.dip83287.floatingbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "floating_notes_channel"
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var notePadView: View? = null
    private var isExpanded = false
    private lateinit var editText: EditText
    
    private var bubbleX = 0
    private var bubbleY = 0
    private var notePadX = 0
    private var notePadY = 0
    private var screenWidth = 0
    private var screenHeight = 0
    
    private var deleteOverlay: View? = null
    private var isDeleting = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        loadPositions()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Notes",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating notes bubble service"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Notes")
            .setContentText("Bubble is active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (bubbleView == null) {
            Handler(Looper.getMainLooper()).post {
                createBubbleView()
            }
        }

        return START_STICKY
    }

    private fun loadPositions() {
        val prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE)
        bubbleX = prefs.getInt("bubble_x", screenWidth - 100)
        bubbleY = prefs.getInt("bubble_y", 300)
        notePadX = prefs.getInt("notepad_x", 0)
        notePadY = prefs.getInt("notepad_y", 0)
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        getSharedPreferences("bubble_prefs", MODE_PRIVATE).edit().apply {
            putInt("bubble_x", x)
            putInt("bubble_y", y)
            apply()
        }
    }

    private fun saveNotePadPosition(x: Int, y: Int) {
        getSharedPreferences("bubble_prefs", MODE_PRIVATE).edit().apply {
            putInt("notepad_x", x)
            putInt("notepad_y", y)
            apply()
        }
    }

    private fun createBubbleView() {
        val bubble = TextView(this).apply {
            text = "📝"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(25, 25, 25, 25)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#808080"))
            }
        }

        val params = WindowManager.LayoutParams(
            100, 100,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = bubbleX
        params.y = bubbleY

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    showDeleteZone()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    windowManager.updateViewLayout(bubble, params)
                    checkDeleteZone(params.y)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    hideDeleteZone()
                    if (!isDragging) {
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

        windowManager.addView(bubble, params)
        bubbleView = bubble
    }

    private fun showDeleteZone() {
        if (deleteOverlay != null) return
        
        val zone = TextView(this).apply {
            text = "🗑️"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E74C3C"))
            }
        }
        
        val params = WindowManager.LayoutParams(
            100, 100,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 50
        
        windowManager.addView(zone, params)
        deleteOverlay = zone
        isDeleting = false
    }

    private fun hideDeleteZone() {
        deleteOverlay?.let {
            windowManager.removeView(it)
            deleteOverlay = null
        }
        isDeleting = false
    }

    private fun checkDeleteZone(y: Int) {
        isDeleting = y > screenHeight - 200
        val bg = deleteOverlay?.background as? GradientDrawable
        if (isDeleting) bg?.setColor(Color.parseColor("#C0392B"))
        else bg?.setColor(Color.parseColor("#E74C3C"))
    }

    private fun expandToNotePad() {
        if (isExpanded) return
        
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
        }
        
        showNotePad()
    }

    private fun showNotePad() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#808080"))
            setPadding(20, 20, 20, 20)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 20f
            }
        }

        // Title bar with drag handle
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#666666"))
            setPadding(15, 12, 15, 12)
        }

        val title = TextView(this).apply {
            text = "📝 Floating Note"
            textSize = 16f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(title)

        val minimizeBtn = TextView(this).apply {
            text = "−"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(15, 0, 5, 0)
            setOnClickListener {
                collapseToBubble()
            }
        }
        titleBar.addView(minimizeBtn)
        container.addView(titleBar)

        // Make title bar draggable
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        
        titleBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val params = notePadView?.layoutParams as? WindowManager.LayoutParams
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    val params = notePadView?.layoutParams as? WindowManager.LayoutParams
                    params?.x = startX + dx.toInt()
                    params?.y = startY + dy.toInt()
                    notePadView?.let { windowManager.updateViewLayout(it, it.layoutParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val params = notePadView?.layoutParams as? WindowManager.LayoutParams
                    saveNotePadPosition(params?.x ?: 0, params?.y ?: 0)
                    true
                }
            }
            false
        }

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply {
                topMargin = 15
                bottomMargin = 15
            }
        }
        container.addView(divider)

        // Edit text
        editText = EditText(this).apply {
            hint = "Write your note..."
            minHeight = 300
            gravity = Gravity.TOP
            setPadding(15, 15, 15, 15)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        container.addView(editText)

        // Buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 15 }
        }

        val saveBtn = Button(this).apply {
            text = "Save"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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

        val openAppBtn = Button(this).apply {
            text = "Open App"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
            setOnClickListener {
                val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
        container.addView(openAppBtn)

        val params = WindowManager.LayoutParams(
            350, 450,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = if (notePadX == 0) (screenWidth - 350) / 2 else notePadX
        params.y = if (notePadY == 0) (screenHeight - 450) / 2 else notePadY

        windowManager.addView(container, params)
        notePadView = container
        isExpanded = true
        
        // Load saved note
        val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
        editText.setText(prefs.getString("last_note", ""))
    }

    private fun collapseToBubble() {
        if (!isExpanded) return
        
        val params = notePadView?.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            saveNotePadPosition(params.x, params.y)
        }
        
        notePadView?.let {
            windowManager.removeView(it)
            notePadView = null
        }
        isExpanded = false
        
        createBubbleView()
    }

    private fun saveNote() {
        val note = editText.text.toString()
        if (note.isNotEmpty()) {
            val prefs = getSharedPreferences("notes_prefs", MODE_PRIVATE)
            val count = prefs.getInt("note_count", 0)
            prefs.edit().apply {
                putString("last_note", note)
                putInt("note_count", count + 1)
                apply()
            }
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
            editText.setText("")
        } else {
            Toast.makeText(this, "Write something", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        notePadView?.let { windowManager.removeView(it) }
        deleteOverlay?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?) = null
}
