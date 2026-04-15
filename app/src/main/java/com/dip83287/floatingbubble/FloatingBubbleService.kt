package com.dip83287.floatingbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.dip83287.floatingbubble.data.Note
import com.dip83287.floatingbubble.repository.NoteRepository

class FloatingBubbleService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_bubble"
        private var bubbleView: View? = null
        private var isExpanded = false
    }
    
    private lateinit var windowManager: WindowManager
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var noteRepository: NoteRepository? = null
    private var notesList = mutableListOf<Note>()
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        noteRepository = NoteRepository(this)
        createNotificationChannel()
        loadNotes()
    }
    
    private fun loadNotes() {
        try {
            notesList = noteRepository?.getAllNotes()?.toMutableList() ?: mutableListOf()
            if (notesList.isEmpty()) {
                notesList.add(Note(title = "Welcome!", content = "Tap + to add a note"))
                noteRepository?.saveNotes(notesList)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Notes",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating notes bubble"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
            .setContentText("${notesList.size} notes")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        loadNotes()
        
        if (bubbleView == null) {
            createBubbleView()
        }
        
        return START_STICKY
    }
    
    private fun createBubbleView() {
        // Circular bubble
        val bubbleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(15, 15, 15, 15)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F9E79F"))
                setStroke(2, Color.parseColor("#F1C40F"))
            }
        }
        
        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(50, 50)
        }
        bubbleLayout.addView(iconView)
        
        val countView = TextView(this).apply {
            text = notesList.size.toString()
            textSize = 10f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(4, 2, 4, 2)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = -8
            }
        }
        bubbleLayout.addView(countView)
        
        bubbleView = bubbleLayout
        
        val layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                100,
                100,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                100,
                100,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        }
        
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 100
        layoutParams.y = 300
        
        var isDragging = false
        var startX = 0
        var startY = 0
        
        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    layoutParams.x = startX + dx.toInt()
                    layoutParams.y = startY + dy.toInt()
                    windowManager.updateViewLayout(bubbleView!!, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        expandToNotePad()
                    }
                    true
                }
                else -> false
            }
        }
        
        bubbleView?.setOnLongClickListener {
            Toast.makeText(this, "Floating bubble closed", Toast.LENGTH_SHORT).show()
            stopSelf()
            true
        }
        
        windowManager.addView(bubbleView, layoutParams)
    }
    
    private fun expandToNotePad() {
        if (isExpanded) return
        isExpanded = true
        
        bubbleView?.let { windowManager.removeView(it) }
        
        // Simple note pad layout
        val notePadLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8DC"))
            setPadding(20, 20, 20, 20)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 16f
            }
        }
        
        // Title bar
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        val titleText = TextView(this).apply {
            text = "Floating Notes"
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleBar.addView(titleText)
        
        val closeButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#C0392B"))
            layoutParams = LinearLayout.LayoutParams(40, 40)
            setOnClickListener {
                collapseToBubble()
            }
        }
        titleBar.addView(closeButton)
        
        notePadLayout.addView(titleBar)
        
        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                topMargin = 15
                bottomMargin = 15
            }
        }
        notePadLayout.addView(divider)
        
        // Notes list (simple TextViews)
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        
        val notesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        for (note in notesList) {
            val noteItem = TextView(this).apply {
                text = "📝 ${note.title}\n${note.content.take(60)}"
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
                setPadding(10, 10, 10, 10)
                setBackgroundColor(Color.parseColor("#FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                }
                setOnClickListener {
                    val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    collapseToBubble()
                }
            }
            notesContainer.addView(noteItem)
        }
        
        scrollView.addView(notesContainer)
        notePadLayout.addView(scrollView)
        
        // Open full app button
        val openButton = Button(this).apply {
            text = "Open Full App"
            setBackgroundColor(Color.parseColor("#F9E79F"))
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 15
            }
            setOnClickListener {
                val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                collapseToBubble()
            }
        }
        notePadLayout.addView(openButton)
        
        bubbleView = notePadLayout
        
        val expandedParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                380,
                500,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                380,
                500,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        }
        
        expandedParams.gravity = Gravity.CENTER
        expandedParams.x = 0
        expandedParams.y = 0
        
        windowManager.addView(bubbleView, expandedParams)
    }
    
    private fun collapseToBubble() {
        if (!isExpanded) return
        isExpanded = false
        
        bubbleView?.let { windowManager.removeView(it) }
        createBubbleView()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            bubbleView?.let {
                windowManager.removeView(it)
                bubbleView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
