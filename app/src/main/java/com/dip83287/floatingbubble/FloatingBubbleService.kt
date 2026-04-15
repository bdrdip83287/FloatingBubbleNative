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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dip83287.floatingbubble.data.Note
import com.dip83287.floatingbubble.repository.NoteRepository

class FloatingBubbleService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_bubble"
        private var bubbleView: View? = null
        private var isExpanded = false
        private var noteRepository: NoteRepository? = null
        private var notesList = mutableListOf<Note>()
    }
    
    private lateinit var windowManager: WindowManager
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
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
            .setContentText("${notesList.size} notes available")
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
        // Create circular bubble
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
        
        // Icon
        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(50, 50)
        }
        bubbleLayout.addView(iconView)
        
        // Note count
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
        
        // Remove bubble
        bubbleView?.let { windowManager.removeView(it) }
        
        // Create expanded note pad
        val notePadLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8DC"))
            setPadding(16, 16, 16, 16)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 16f
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
        }
        
        // Title bar with close button
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
        
        // Close/Condense button (X)
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
                topMargin = 12
                bottomMargin = 12
            }
        }
        notePadLayout.addView(divider)
        
        // Notes list
        val notesTitle = TextView(this).apply {
            text = "Your Notes:"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        notePadLayout.addView(notesTitle)
        
        // RecyclerView for notes
        val recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            layoutManager = LinearLayoutManager(this@FloatingBubbleService)
        }
        
        // Simple adapter for notes
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = android.view.View.inflate(parent.context, android.R.layout.simple_list_item_2, null)
                return object : RecyclerView.ViewHolder(view) {}
            }
            
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val note = notesList[position]
                val titleView = holder.itemView.findViewById<android.widget.TextView>(android.R.id.text1)
                val contentView = holder.itemView.findViewById<android.widget.TextView>(android.R.id.text2)
                titleView?.text = note.title
                contentView?.text = note.content.take(50)
                holder.itemView.setOnClickListener {
                    val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    collapseToBubble()
                }
            }
            
            override fun getItemCount(): Int = notesList.size
        }
        recyclerView.adapter = adapter
        notePadLayout.addView(recyclerView)
        
        // Add note button
        val addButton = Button(this).apply {
            text = "+ New Note"
            setBackgroundColor(Color.parseColor("#27AE60"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
            setOnClickListener {
                val intent = Intent(this@FloatingBubbleService, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                collapseToBubble()
            }
        }
        notePadLayout.addView(addButton)
        
        bubbleView = notePadLayout
        
        val expandedParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                350,
                500,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                350,
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
        
        // Remove expanded view
        bubbleView?.let { windowManager.removeView(it) }
        
        // Recreate bubble view
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
