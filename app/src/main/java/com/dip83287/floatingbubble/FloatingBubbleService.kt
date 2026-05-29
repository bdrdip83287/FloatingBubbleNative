package com.dip83287.floatingbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

class FloatingBubbleService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_bubble_channel"
        
        // Reset flag untuk openFromBubble
        private var shouldResetPosition = false
        private var hasOpenedOnce = false
    }

    private val BUBBLE_COLOR = "#808080"
    private val NOTEPAD_BG_COLOR = "#FFF8DC"
    private val BUBBLE_ICON = "📝"
    private val BUBBLE_SIZE = 110
    private val DELETE_ZONE_SIZE = 110
    private val HIDDEN_WIDTH = (BUBBLE_SIZE * 0.1f).toInt()

    private val NOTEPAD_MIN_WIDTH = 380
    private val NOTEPAD_MIN_HEIGHT = 500
    private val NOTEPAD_MAX_WIDTH = 650
    private val NOTEPAD_MAX_HEIGHT = 850

    private val STORAGE_NOTES_LIST = "notes_list"
    private val KEY_FIRST_TIME_BUBBLE = "first_time_bubble"
    private val KEY_THEME = "theme"

    private lateinit var prefs: SharedPreferences
    private lateinit var securityManager: SecurityManager
    private lateinit var undoRedoManager: UndoRedoManager
    
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
    private lateinit var titleInput: EditText
    private lateinit var scrollView: ScrollView
    private var currentNotepadWidth = NOTEPAD_MIN_WIDTH
    private var currentNotepadHeight = NOTEPAD_MIN_HEIGHT
    private var notepadPosX = 0
    private var notepadPosY = 0
    private var isFullScreen = false
    
    private var isDraggingLeftHandle = false
    private var isDraggingRightHandle = false
    private var isActionBarVisible = false
    private var actionBarWindowManager: WindowManager? = null
    private var floatingActionBar: View? = null
    
    // Handle update debounce
    private val handleUpdateDebounceHandler = Handler(Looper.getMainLooper())
    private var handleUpdatePending = false
    private var isActionBarTemporarilyHidden = false
    private var currentSelectedText = ""

    private val notesList = mutableListOf<NoteItem>()
    private lateinit var notesAdapter: NoteAdapter
    private lateinit var recyclerView: RecyclerView
    private val saveHandler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null

    data class NoteItem(
        val id: Long,
        var title: String,
        var content: String,
        val lastEdited: Long = System.currentTimeMillis(),
        var isLocked: Boolean = false
    )

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            actionBarWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            securityManager = SecurityManager(this)
            undoRedoManager = UndoRedoManager()
            
            loadSavedPositions()
            loadNotes()
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            createDeleteZone()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadNotes() {
        val notesJson = prefs.getString(STORAGE_NOTES_LIST, "")
        if (!notesJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<NoteItem>>() {}.type
                val loaded: List<NoteItem> = Gson().fromJson(notesJson, type)
                notesList.clear()
                notesList.addAll(loaded)
            } catch (e: Exception) {
                if (notesList.isEmpty()) {
                    notesList.add(NoteItem(System.currentTimeMillis(), "Untitled Note", ""))
                }
            }
        } else {
            if (notesList.isEmpty()) {
                notesList.add(NoteItem(System.currentTimeMillis(), "Untitled Note", ""))
            }
        }
        saveNotesToPrefs()
    }

    private fun saveNotesToPrefs() {
        val notesJson = Gson().toJson(notesList)
        prefs.edit().putString(STORAGE_NOTES_LIST, notesJson).apply()
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
            .build()
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
        try {
            val zone = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.RED)
                }
                background = shape
                setPadding(30, 30, 30, 30)
            }

            val cross = TextView(this).apply {
                text = "✕"
                textSize = 35f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }
            zone.addView(cross)

            val params = WindowManager.LayoutParams(
                DELETE_ZONE_SIZE, DELETE_ZONE_SIZE,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = 150
            zone.visibility = View.GONE
            deleteZoneView = zone
            windowManager.addView(deleteZoneView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showDeleteZone() {
        deleteZoneView?.visibility = View.VISIBLE
    }

    private fun hideDeleteZone() {
        deleteZoneView?.visibility = View.GONE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (bubbleView == null) {
            Handler(Looper.getMainLooper()).post { createBubble() }
        }

        return START_STICKY
    }

    private fun getInitialBubblePosition(displayMetrics: android.util.DisplayMetrics): Pair<Int, Int> {
        val screenWidth = displayMetrics.widthPixels
        val isFirstTime = prefs.getBoolean(KEY_FIRST_TIME_BUBBLE, true)
        
        return if (isFirstTime) {
            val defaultX = screenWidth - BUBBLE_SIZE - 20
            val defaultY = 150
            Pair(defaultX, defaultY)
        } else {
            val savedX = prefs.getInt(KEY_BUBBLE_X, screenWidth - BUBBLE_SIZE + HIDDEN_WIDTH)
            val savedY = prefs.getInt(KEY_BUBBLE_Y, 150)
            Pair(savedX, savedY)
        }
    }
    
    private fun markBubbleCreated() {
        if (prefs.getBoolean(KEY_FIRST_TIME_BUBBLE, true)) {
            prefs.edit().putBoolean(KEY_FIRST_TIME_BUBBLE, false).apply()
        }
    }

    private fun createBubble() {
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
                text = notesList.size.toString()
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.RED)
                setPadding(6, 3, 6, 3)
                gravity = Gravity.CENTER
                visibility = if (notesList.size > 0) View.VISIBLE else View.GONE
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
            val (defaultX, defaultY) = getInitialBubblePosition(displayMetrics)
            params.x = defaultX
            params.y = defaultY

            setupBubbleTouchListener(params, displayMetrics)
            setupBubbleLongClickListener()

            windowManager.addView(bubbleView, params)
            markBubbleCreated()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupBubbleTouchListener(
        params: WindowManager.LayoutParams,
        displayMetrics: android.util.DisplayMetrics
    ) {
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        showDeleteZone()
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(bubbleView!!, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        hideDeleteZone()
                        
                        val deltaX = abs(event.rawX - touchX)
                        val deltaY = abs(event.rawY - touchY)
                        
                        if (deltaX < 10 && deltaY < 10) {
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
    }

    private fun setupBubbleLongClickListener() {
        bubbleView?.setOnLongClickListener {
            stopSelf()
            true
        }
    }

    private fun expandToNotePad() {
        if (isExpanded) return

        try {
            createAndShowNotePad()
            
            bubbleView?.let {
                windowManager.removeView(it)
                bubbleView = null
            }
            isExpanded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun createAndShowNotePad() {
        if (noteView != null) return
        
        val container = createFullNotePad()
        noteView = container
        
        val params = WindowManager.LayoutParams(
            currentNotepadWidth, currentNotepadHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = notepadPosX
        params.y = notepadPosY
        
        windowManager.addView(noteView, params)
    }

    private fun collapseToBubble() {
        if (!isExpanded) return

        try {
            noteView?.let {
                val params = it.layoutParams as WindowManager.LayoutParams
                saveNotepadSizeAndPosition(currentNotepadWidth, currentNotepadHeight, params.x, params.y)
                windowManager.removeView(it)
                noteView = null
            }
            
            createBubble()
            isExpanded = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createFullNotePad(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(NOTEPAD_BG_COLOR))
            setPadding(16, 16, 16, 16)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = 16f
        }

        // Top bar
        val topBar = createTopBar()
        container.addView(topBar)

        // Note list / Editor will be added dynamically
        showNoteList(container)
        
        return container
    }

    private fun createTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 12, 8, 12)
            setBackgroundColor(Color.parseColor("#F9E79F"))
            
            val dragHandle = TextView(context).apply {
                text = "⋯"
                textSize = 24f
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(dragHandle)
            
            val titleText = TextView(context).apply {
                text = "Floating Notes"
                textSize = 16f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
            }
            addView(titleText)
            
            // Settings button
            val settingsBtn = TextView(context).apply {
                text = "⚙️"
                textSize = 22f
                setPadding(16, 0, 8, 0)
                setOnClickListener { showSettingsDialog() }
            }
            addView(settingsBtn)
            
            // Minimize button
            val minimizeBtn = TextView(context).apply {
                text = "−"
                textSize = 28f
                setTextColor(Color.parseColor("#C0392B"))
                setPadding(8, 0, 8, 0)
                setOnClickListener { collapseToBubble() }
            }
            addView(minimizeBtn)
        }
    }

    private fun showNoteList(container: LinearLayout) {
        container.removeAllViews()
        container.addView(createTopBar())
        
        val noteCountText = TextView(this).apply {
            text = "Note List (${notesList.size})"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            setPadding(12, 16, 12, 8)
        }
        container.addView(noteCountText)

        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@FloatingBubbleService)
            setPadding(8, 8, 8, 8)
            setHasFixedSize(true)
        }
        
        notesAdapter = NoteAdapter(notesList,
            onItemClick = { note ->
                openEditorForNote(note, container)
            },
            onDeleteClick = { note ->
                notesList.remove(note)
                saveNotesToPrefs()
                notesAdapter.updateList(notesList)
                updateBubbleCount()
                Toast.makeText(this@FloatingBubbleService, "Note deleted", Toast.LENGTH_SHORT).show()
            },
            onLockClick = { note ->
                toggleNoteLock(note, container)
            }
        )
        recyclerView.adapter = notesAdapter
        container.addView(recyclerView)

        val addButton = Button(this).apply {
            text = "+ New Note"
            setBackgroundColor(Color.parseColor("#F9E79F"))
            setTextColor(Color.parseColor("#333333"))
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            setOnClickListener {
                val newNote = NoteItem(
                    id = System.currentTimeMillis(),
                    title = "Untitled Note",
                    content = ""
                )
                notesList.add(0, newNote)
                saveNotesToPrefs()
                notesAdapter.updateList(notesList)
                updateBubbleCount()
                openEditorForNote(newNote, container)
            }
        }
        container.addView(addButton)

        val resizeHandleView = TextView(this).apply {
            text = "◢"
            textSize = 18f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.END or Gravity.BOTTOM
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 32)
            lp.topMargin = 4
            layoutParams = lp
        }
        container.addView(resizeHandleView)
    }

    private fun openEditorForNote(note: NoteItem, container: LinearLayout) {
        // Check if note is locked
        if (note.isLocked && !isNoteTemporarilyUnlocked(note.id)) {
            showPasswordDialog(note, container)
            return
        }
        
        container.removeAllViews()
        container.addView(createTopBar())
        
        // Back button
        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 28f
            setTextColor(Color.parseColor("#333333"))
            setPadding(16, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                showNoteList(container)
            }
        }
        (container.getChildAt(0) as LinearLayout).addView(backBtn, 0)
        
        // Title input
        titleInput = EditText(this).apply {
            setText(note.title)
            hint = "Title"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 16, 8, 8)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        container.addView(titleInput)
        
        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            )
        }
        container.addView(divider)
        
        // EditText for content
        editText = EditText(this).apply {
            setText(note.content)
            hint = "Write your note here..."
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setHorizontallyScrolling(false)
            maxLines = Int.MAX_VALUE
            minHeight = 300
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            
            setTextIsSelectable(true)
            isLongClickable = true
            
            undoRedoManager.pushState(text.toString())
            
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    undoRedoManager.pushState(s.toString())
                    saveRunnable?.let { saveHandler.removeCallbacks(it) }
                    val runnable = Runnable {
                        val index = notesList.indexOfFirst { it.id == note.id }
                        if (index != -1) {
                            val updatedNote = notesList[index].copy(
                                content = s.toString(),
                                title = titleInput.text.toString(),
                                lastEdited = System.currentTimeMillis()
                            )
                            notesList[index] = updatedNote
                            saveNotesToPrefs()
                        }
                    }
                    saveRunnable = runnable
                    saveHandler.postDelayed(runnable, 600)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        container.addView(editText)
        
        // Bottom toolbar
        val toolbar = createBottomToolbar(note)
        container.addView(toolbar)
        
        // Resize handle
        val resizeHandleView = TextView(this).apply {
            text = "◢"
            textSize = 18f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.END or Gravity.BOTTOM
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 32)
            lp.topMargin = 8
            layoutParams = lp
        }
        container.addView(resizeHandleView)
    }

    private fun createBottomToolbar(note: NoteItem): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#F9E79F"))
            
            // Undo
            val undoBtn = TextView(context).apply {
                text = "↩️ Undo"
                textSize = 12f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    undoRedoManager.undo()?.let { editText.setText(it) }
                }
            }
            addView(undoBtn)
            
            // Redo
            val redoBtn = TextView(context).apply {
                text = "↪️ Redo"
                textSize = 12f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    undoRedoManager.redo()?.let { editText.setText(it) }
                }
            }
            addView(redoBtn)
            
            // Copy
            val copyBtn = TextView(context).apply {
                text = "📋 Copy"
                textSize = 12f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = android.content.ClipData.newPlainText("text", editText.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
            addView(copyBtn)
            
            // Paste
            val pasteBtn = TextView(context).apply {
                text = "📄 Paste"
                textSize = 12f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val pastedText = clip.getItemAt(0).text
                        val start = editText.selectionStart
                        val end = editText.selectionEnd
                        editText.text.replace(start, end, pastedText)
                    }
                }
            }
            addView(pasteBtn)
            
            // Lock/Unlock
            val lockBtn = TextView(context).apply {
                text = if (note.isLocked) "🔒 Locked" else "🔓 Unlock"
                textSize = 12f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    toggleNoteLock(note, this@LinearLayout.parent as LinearLayout)
                }
            }
            addView(lockBtn)
            
            // Share
            val shareBtn = TextView(context).apply {
                text = "📤 Share"
                textSize = 12f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, "${titleInput.text}\n\n${editText.text}")
                    }
                    val chooser = android.content.Intent.createChooser(shareIntent, "Share Note")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(chooser)
                }
            }
            addView(shareBtn)
            
            // Delete
            val deleteBtn = TextView(context).apply {
                text = "🗑️ Delete"
                textSize = 12f
                setTextColor(Color.parseColor("#C0392B"))
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("Delete Note")
                        .setMessage("Are you sure you want to delete this note?")
                        .setPositiveButton("Delete") { _, _ ->
                            notesList.removeAll { it.id == note.id }
                            saveNotesToPrefs()
                            notesAdapter.updateList(notesList)
                            updateBubbleCount()
                            showNoteList(this@LinearLayout.parent as LinearLayout)
                            Toast.makeText(context, "Note deleted", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            addView(deleteBtn)
            
            // Full Screen
            val fullScreenBtn = TextView(context).apply {
                text = if (isFullScreen) "📱 Exit" else "🖥️ Full"
                textSize = 12f
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    toggleFullScreen()
                }
            }
            addView(fullScreenBtn)
        }
    }

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        val params = noteView?.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            if (isFullScreen) {
                val displayMetrics = resources.displayMetrics
                params.width = displayMetrics.widthPixels - 40
                params.height = displayMetrics.heightPixels - 100
                params.x = 20
                params.y = 50
            } else {
                params.width = currentNotepadWidth
                params.height = currentNotepadHeight
                params.x = notepadPosX
                params.y = notepadPosY
            }
            windowManager.updateViewLayout(noteView, params)
        }
        // Update button text in toolbar
        updateFullScreenButtonText()
    }

    private fun updateFullScreenButtonText() {
        // Find and update full screen button text
        val toolbar = (noteView as? LinearLayout)?.getChildAt(2) as? LinearLayout
        toolbar?.let {
            for (i in 0 until it.childCount) {
                val child = it.getChildAt(i)
                if (child is TextView && child.text.toString().contains("Full") || child.text.toString().contains("Exit")) {
                    child.text = if (isFullScreen) "📱 Exit" else "🖥️ Full"
                    break
                }
            }
        }
    }

    private fun showSettingsDialog() {
        val items = arrayOf(
            "Dark Mode: ${if (ThemeManager.isDarkMode(this)) "On" else "Off"}",
            "Security Settings",
            "About"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleDarkMode()
                    1 -> showSecuritySettings()
                    2 -> showAboutDialog()
                }
            }
            .show()
    }

    private fun toggleDarkMode() {
        val isDark = !ThemeManager.isDarkMode(this)
        ThemeManager.setDarkMode(this, isDark)
        val colors = ThemeManager.getThemeColors(isDark)
        
        // Update colors
        (noteView as? LinearLayout)?.setBackgroundColor(Color.parseColor(colors.mainBgColor))
        updateBubbleCount()
        
        Toast.makeText(this, if (isDark) "Dark Mode Enabled" else "Light Mode Enabled", Toast.LENGTH_SHORT).show()
    }

    private fun showSecuritySettings() {
        val items = mutableListOf<String>()
        
        if (securityManager.isPasswordSet()) {
            items.add("Change Password")
            items.add("Update Security Question")
        } else {
            items.add("Set Password")
            items.add("Setup Security Question")
        }
        
        AlertDialog.Builder(this)
            .setTitle("Security Settings")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Set Password", "Change Password" -> showChangePasswordDialog()
                    "Setup Security Question", "Update Security Question" -> showSecurityQuestionSetupDialog()
                }
            }
            .show()
    }

    private fun showChangePasswordDialog() {
        val oldPasswordInput = EditText(this).apply {
            hint = "Enter current password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newPasswordInput = EditText(this).apply {
            hint = "Enter new password (min 3 chars)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
            addView(oldPasswordInput)
            addView(newPasswordInput)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Change Password")
            .setView(layout)
            .setPositiveButton("Change") { _, _ ->
                val oldPassword = oldPasswordInput.text.toString()
                val newPassword = newPasswordInput.text.toString()
                
                if (securityManager.verifyMasterPassword(oldPassword)) {
                    if (newPassword.length >= 3) {
                        securityManager.setMasterPassword(newPassword)
                        Toast.makeText(this, "Password changed successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "New password must be at least 3 characters", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Incorrect current password", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSecurityQuestionSetupDialog() {
        val questions = arrayOf(
            "What is your mother's maiden name?",
            "What was your first pet's name?",
            "What city were you born in?",
            "What was your childhood nickname?",
            "What is your favorite book?",
            "What is the name of your first school?"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Security Question")
            .setItems(questions) { _, which ->
                val selectedQuestion = questions[which]
                val answerInput = EditText(this)
                answerInput.hint = "Your answer"
                
                AlertDialog.Builder(this)
                    .setTitle("Security Question")
                    .setMessage(selectedQuestion)
                    .setView(answerInput)
                    .setPositiveButton("Save") { _, _ ->
                        val answer = answerInput.text.toString()
                        if (answer.isNotEmpty()) {
                            val securityQuestion = SecurityQuestion(selectedQuestion, answer)
                            securityManager.setSecurityQuestion(securityQuestion)
                            Toast.makeText(this, "Security question saved!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Answer cannot be empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .show()
            }
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Floating Notes")
            .setMessage("Version 1.0.0\n\nFloating Notes - A floating notepad app that stays on top of other apps.\n\nFeatures:\n• Create and manage notes\n• Lock notes with password\n• Dark/Light mode\n• Undo/Redo\n• Copy/Paste\n• Share notes\n• Resizable window\n• Full screen mode")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPasswordDialog(note: NoteItem, container: LinearLayout) {
        val input = EditText(this)
        input.hint = "Enter master password"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        
        AlertDialog.Builder(this)
            .setTitle("Note Locked")
            .setMessage("This note is locked. Enter your master password to view it.")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (securityManager.verifyMasterPassword(input.text.toString())) {
                    temporaryUnlockNote(note.id)
                    openEditorForNote(note, container)
                } else {
                    Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleNoteLock(note: NoteItem, container: LinearLayout) {
        if (!securityManager.isPasswordSet()) {
            Toast.makeText(this, "Please set a master password first in Settings", Toast.LENGTH_SHORT).show()
            return
        }
        
        val newLockState = !note.isLocked
        val index = notesList.indexOfFirst { it.id == note.id }
        if (index != -1) {
            notesList[index] = note.copy(isLocked = newLockState)
            saveNotesToPrefs()
            notesAdapter.updateList(notesList)
            updateBubbleCount()
            
            if (newLockState && isNoteTemporarilyUnlocked(note.id)) {
                clearTemporaryUnlock(note.id)
            }
            
            Toast.makeText(this, if (newLockState) "Note locked" else "Note unlocked", Toast.LENGTH_SHORT).show()
            
            // Refresh current view if this note is open
            if (noteView != null && isExpanded) {
                openEditorForNote(notesList[index], container)
            }
        }
    }

    private val temporarilyUnlockedNotes = mutableSetOf<Long>()
    
    private fun temporaryUnlockNote(id: Long) {
        temporarilyUnlockedNotes.add(id)
        Handler(Looper.getMainLooper()).postDelayed({
            temporarilyUnlockedNotes.remove(id)
        }, 300000) // 5 minutes
    }
    
    private fun isNoteTemporarilyUnlocked(id: Long): Boolean = temporarilyUnlockedNotes.contains(id)
    
    private fun clearTemporaryUnlock(id: Long) {
        temporarilyUnlockedNotes.remove(id)
    }

    private fun updateBubbleCount() {
        val bubbleLayout = bubbleView as? LinearLayout
        val countView = bubbleLayout?.getChildAt(1) as? TextView
        countView?.text = notesList.size.toString()
        countView?.visibility = if (notesList.size > 0) View.VISIBLE else View.GONE
    }

    private fun showNoteList(container: LinearLayout) {
        showNoteList(container)
    }

    inner class NoteAdapter(
        private var notes: List<NoteItem>,
        private val onItemClick: (NoteItem) -> Unit,
        private val onDeleteClick: (NoteItem) -> Unit,
        private val onLockClick: (NoteItem) -> Unit
    ) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

        fun updateList(newNotes: List<NoteItem>) {
            notes = newNotes.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(notes[position])
        }

        override fun getItemCount(): Int = notes.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView = itemView.findViewById<TextView>(android.R.id.text1)
            private val contentView = itemView.findViewById<TextView>(android.R.id.text2)

            fun bind(note: NoteItem) {
                titleView.text = "${if (note.isLocked) "🔒 " else ""}${note.title}"
                val preview = if (note.content.length > 50) note.content.take(50) + "..." else note.content
                contentView.text = preview.ifEmpty { "No content" }
                
                itemView.setOnClickListener { onItemClick(note) }
                itemView.setOnLongClickListener {
                    onDeleteClick(note)
                    true
                }
            }
        }
    }

    private var deleteZoneView: View? = null

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        noteView?.let { windowManager.removeView(it) }
        deleteZoneView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?) = null
}
