package com.dip83287.floatingbubble

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ArrowKeyMovementMethod
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dip83287.floatingbubble.utils.EmergencyLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingBubbleService : Service() {

    private val BUBBLE_COLOR = "#808080"
    private val NOTEPAD_BG_COLOR = "#FFF8DC"
    private val BUBBLE_ICON = "📝"
    private val BUBBLE_SIZE = 80
    private val DELETE_ZONE_SIZE = 110
    private val HIDDEN_WIDTH = (BUBBLE_SIZE * 0.1f).toInt()

    private val NOTEPAD_TITLE = "Floating Notes"
    private val NOTEPAD_MIN_WIDTH = 380
    private val NOTEPAD_MIN_HEIGHT = 500
    private val NOTEPAD_MAX_WIDTH = 650
    private val NOTEPAD_MAX_HEIGHT = 850

    private val ANIMATION_DURATION = 200L

    private val STORAGE_NOTE_COUNT = "note_count"
    private val STORAGE_LAST_NOTE = "last_note"
    private val STORAGE_NOTES_LIST = "notes_list"

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
    private lateinit var titleInput: EditText
    private var currentNotepadWidth = NOTEPAD_MIN_WIDTH
    private var currentNotepadHeight = NOTEPAD_MIN_HEIGHT
    private var notepadPosX = 0
    private var notepadPosY = 0

    private var isResizing = false
    private var resizeStartX = 0
    private var resizeStartY = 0
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeTouchTime = 0L

    private var deleteZoneView: View? = null
    private var isInDeleteZone = false
    private var flingAnimator: ValueAnimator? = null

    private var velocityTracker: VelocityTracker? = null
    private var velocityX = 0f
    private var velocityY = 0f

    private val notesList = mutableListOf<NoteItem>()
    private lateinit var notesAdapter: NoteAdapter
    private lateinit var recyclerView: RecyclerView
    private var isInEditorMode = false
    private var currentEditingNoteId = -1L
    private val saveHandler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null

    data class NoteItem(
        val id: Long,
        var title: String,
        var content: String,
        val lastEdited: Long = System.currentTimeMillis()
    )

    override fun onCreate() {
        super.onCreate()
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            loadSavedPositions()
            loadNotes()
            createNotificationChannel()
            startForeground(1001, createNotification())
            createDeleteZone()
        } catch (e: Exception) {
            EmergencyLog.logException(e, "FloatingBubbleService.onCreate")
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
                "floating_bubble_channel",
                "Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps floating bubble alive"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "floating_bubble_channel")
            .setContentTitle("Floating Notes")
            .setContentText("${notesList.size} notes available")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
            params.y = 80
            zone.visibility = View.GONE
            deleteZoneView = zone
            windowManager.addView(deleteZoneView, params)
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createDeleteZone")
        }
    }

    private fun showDeleteZone() {
        if (deleteZoneView?.visibility != View.VISIBLE) {
            deleteZoneView?.visibility = View.VISIBLE
        }
    }

    private fun hideDeleteZone() {
        if (deleteZoneView?.visibility != View.GONE) {
            deleteZoneView?.visibility = View.GONE
        }
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
            val defaultX = prefs.getInt(KEY_BUBBLE_X, displayMetrics.widthPixels - BUBBLE_SIZE + HIDDEN_WIDTH)
            val defaultY = prefs.getInt(KEY_BUBBLE_Y, 150)
            params.x = defaultX
            params.y = defaultY

            setupBubbleTouchListener(params, displayMetrics)
            setupBubbleLongClickListener()

            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createBubble")
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

                velocityTracker ?: run {
                    velocityTracker = VelocityTracker.obtain()
                }

                velocityTracker?.addMovement(event)

                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {

                        flingAnimator?.cancel()

                        initialX = params.x
                        initialY = params.y

                        touchX = event.rawX
                        touchY = event.rawY

                        isInDeleteZone = false

                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {

                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY

                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()

                        val screenHeight = displayMetrics.heightPixels
                        val deleteZoneY = screenHeight - DELETE_ZONE_SIZE - 80

                        if (params.y + BUBBLE_SIZE > deleteZoneY) {

                            if (!isInDeleteZone) {
                                isInDeleteZone = true
                                showDeleteZone()
                            }

                        } else {

                            if (isInDeleteZone) {
                                isInDeleteZone = false
                                hideDeleteZone()
                            }
                        }

                        windowManager.updateViewLayout(bubbleView!!, params)

                        return true
                    }

                    MotionEvent.ACTION_UP -> {

                        hideDeleteZone()

                        if (isInDeleteZone) {
                            deleteBubble()
                            return true
                        }

                        val deltaX = abs(event.rawX - touchX)
                        val deltaY = abs(event.rawY - touchY)

                        if (deltaX < 10 && deltaY < 10) {
                            expandToNotePad()
                            return true
                        }

                        velocityTracker?.computeCurrentVelocity(1000)

                        velocityX = velocityTracker?.xVelocity ?: 0f
                        velocityY = velocityTracker?.yVelocity ?: 0f

                        velocityTracker?.recycle()
                        velocityTracker = null

                        applyStableDockPhysics(params, displayMetrics)

                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {

                        velocityTracker?.recycle()
                        velocityTracker = null
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

    private fun deleteBubble() {
        stopSelf()
    }

    private fun applyStableDockPhysics(
        params: WindowManager.LayoutParams,
        displayMetrics: android.util.DisplayMetrics
    ) {
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val startX = params.x.toFloat()
        val startY = params.y.toFloat()

        val targetX = if (
            params.x + (BUBBLE_SIZE / 2) < screenWidth / 2
        ) {
            -HIDDEN_WIDTH.toFloat()
        } else {
            (screenWidth - BUBBLE_SIZE + HIDDEN_WIDTH).toFloat()
        }

        val finalY = (
            startY + (velocityY * 0.08f)
        ).coerceIn(
            0f,
            (screenHeight - BUBBLE_SIZE - 120).toFloat()
        )

        flingAnimator?.cancel()

        flingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {

            duration = 240L
            interpolator = DecelerateInterpolator()

            addUpdateListener { animator ->

                val t = animator.animatedValue as Float

                params.x = (
                    startX + ((targetX - startX) * t)
                ).toInt()

                params.y = (
                    startY + ((finalY - startY) * t)
                ).toInt()

                windowManager.updateViewLayout(bubbleView!!, params)
            }

            addListener(object : Animator.AnimatorListener {

                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {

                    params.x = targetX.toInt()
                    windowManager.updateViewLayout(bubbleView!!, params)
                    applyTinySpringEffect(params, targetX.toInt())
                }
            })

            start()
        }
    }

    private fun applyTinySpringEffect(
        params: WindowManager.LayoutParams,
        targetX: Int
    ) {
        val startX = params.x.toFloat()
        val stretchX = if (targetX < 0) {
            targetX - 8f
        } else {
            targetX + 8f
        }

        val springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {

            duration = 140L
            interpolator = DecelerateInterpolator()

            addUpdateListener { animator ->

                val t = animator.animatedValue as Float

                val currentX = if (t < 0.7f) {
                    val localT = t / 0.7f
                    startX + ((stretchX - startX) * localT)
                } else {
                    val localT = (t - 0.7f) / 0.3f
                    stretchX + ((targetX - stretchX) * localT)
                }

                params.x = currentX.toInt()
                windowManager.updateViewLayout(bubbleView!!, params)
            }

            addListener(object : Animator.AnimatorListener {

                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {

                    params.x = targetX
                    windowManager.updateViewLayout(bubbleView!!, params)
                    saveBubblePosition(params.x, params.y)
                }
            })

            start()
        }
    }

    private fun expandToNotePad() {
        if (isExpanded) return

        try {
            createAndShowNotePad()

            val bubble = bubbleView ?: return
            val note = noteView ?: return

            bubble.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            note.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            note.alpha = 0f
            note.scaleX = 0.85f
            note.scaleY = 0.85f
            note.translationY = 40f

            note.doOnLayout {
                note.pivotX = (note.width / 2).toFloat()
                note.pivotY = 0f

                bubble.animate()
                    .alpha(0f)
                    .scaleX(0.6f)
                    .scaleY(0.6f)
                    .setDuration(140)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                note.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(0.6f))
                    .withEndAction {
                        try {
                            bubbleView?.let { windowManager.removeView(it) }
                        } catch (_: Exception) { }

                        bubbleView = null
                        isExpanded = true

                        bubble.setLayerType(View.LAYER_TYPE_NONE, null)
                        note.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    .start()
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "expandToNotePad")
        }
    }
    
    private fun createAndShowNotePad() {
        if (noteView != null) return
        
        try {
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
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createAndShowNotePad")
        }
    }

    private fun collapseToBubble() {
        if (!isExpanded) return

        try {
            val note = noteView ?: return
            val params = note.layoutParams as WindowManager.LayoutParams

            saveNotepadSizeAndPosition(
                currentNotepadWidth,
                currentNotepadHeight,
                params.x,
                params.y
            )

            createBubble()
            val bubble = bubbleView ?: return

            bubble.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            note.setLayerType(View.LAYER_TYPE_HARDWARE, null)

            bubble.alpha = 0f
            bubble.scaleX = 0.5f
            bubble.scaleY = 0.5f
            bubble.translationY = 30f

            bubble.doOnLayout {
                bubble.pivotX = (bubble.width / 2).toFloat()
                bubble.pivotY = (bubble.height / 2).toFloat()

                note.animate()
                    .alpha(0f)
                    .scaleX(0.88f)
                    .scaleY(0.88f)
                    .translationY(25f)
                    .setDuration(160)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                bubble.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(0.55f))
                    .withEndAction {
                        try {
                            noteView?.let { windowManager.removeView(it) }
                        } catch (_: Exception) { }

                        noteView = null
                        isExpanded = false

                        bubble.setLayerType(View.LAYER_TYPE_NONE, null)
                        note.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                    .start()
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "collapseToBubble")
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
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnTouchListener(TitleBarDragListener())
            setPadding(8, 12, 8, 12)
            setBackgroundColor(Color.parseColor("#F9E79F"))
        }

        val dragHandle = TextView(this).apply {
            text = "⋯"
            textSize = 24f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(dragHandle)

        val titleText = TextView(this).apply {
            text = NOTEPAD_TITLE
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        topBar.addView(titleText)

        val minimizeBtn = TextView(this).apply {
            text = "−"
            textSize = 28f
            setTextColor(Color.parseColor("#C0392B"))
            setPadding(16, 0, 8, 0)
            setOnClickListener { collapseToBubble() }
        }
        topBar.addView(minimizeBtn)
        container.addView(topBar)

        // Note count text
        val noteCountText = TextView(this).apply {
            text = "Note List (${notesList.size})"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            setPadding(12, 16, 12, 8)
        }
        container.addView(noteCountText)

        // RecyclerView
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@FloatingBubbleService)
            setPadding(8, 8, 8, 8)
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(20)
        }
        
        notesAdapter = NoteAdapter(notesList,
            onItemClick = { note ->
                openEditorForNote(note)
            },
            onDeleteClick = { note ->
                notesList.remove(note)
                saveNotesToPrefs()
                notesAdapter.updateList(notesList)
                updateBubbleCount()
                Toast.makeText(this@FloatingBubbleService, "Note deleted", Toast.LENGTH_SHORT).show()
            }
        )
        recyclerView.adapter = notesAdapter
        container.addView(recyclerView)

        // Add note button
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
                createNewNote()
            }
        }
        container.addView(addButton)

        // Resize handle
        val resizeHandleView = TextView(this).apply {
            text = "◢"
            textSize = 18f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.END or Gravity.BOTTOM
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 32)
            lp.topMargin = 4
            layoutParams = lp
            setOnTouchListener(ResizeTouchListener())
        }
        container.addView(resizeHandleView)

        return container
    }

    private fun createNewNote() {
        val newNote = NoteItem(
            id = System.currentTimeMillis(),
            title = "Untitled Note",
            content = ""
        )
        notesList.add(0, newNote)
        saveNotesToPrefs()
        notesAdapter.updateList(notesList)
        updateBubbleCount()
        openEditorForNote(newNote)
    }

    private fun openEditorForNote(note: NoteItem) {
        isInEditorMode = true
        currentEditingNoteId = note.id
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(NOTEPAD_BG_COLOR))
            setPadding(16, 16, 16, 16)
        }

        // Editor top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnTouchListener(TitleBarDragListener())
            setPadding(8, 12, 8, 12)
            setBackgroundColor(Color.parseColor("#F9E79F"))
        }

        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(Color.parseColor("#333333"))
            setPadding(8, 0, 16, 0)
            setOnClickListener {
                showNoteList()
            }
        }
        topBar.addView(backBtn)

        val editorTitle = TextView(this).apply {
            text = "Edit Note"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        topBar.addView(editorTitle)

        val minimizeBtn = TextView(this).apply {
            text = "−"
            textSize = 28f
            setTextColor(Color.parseColor("#C0392B"))
            setPadding(16, 0, 8, 0)
            setOnClickListener { collapseToBubble() }
        }
        topBar.addView(minimizeBtn)
        container.addView(topBar)

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
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextIsSelectable(true)
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

        // Optimized EditText - FIXED scrollbar issues
        editText = EditText(this).apply {
            setText(note.content)
            hint = "Write your note here..."
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            
            // Performance - REMOVED scrollbar configurations that cause crash
            setHorizontallyScrolling(false)
            maxLines = Int.MAX_VALUE
            minHeight = 300
            
            // Text input type
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            
            // Selection
            setTextIsSelectable(true)
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = true
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
            
            // Cursor movement
            movementMethod = ArrowKeyMovementMethod.getInstance()
            
            setOnClickListener {
                requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
            
            // Performance layer - NO scrollbar config
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
            // Text watcher with auto-save
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    saveRunnable?.let { saveHandler.removeCallbacks(it) }
                    val runnable = Runnable {
                        val index = notesList.indexOfFirst { it.id == note.id }
                        if (index != -1) {
                            val updatedNote = notesList[index].copy(
                                content = text.toString(),
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

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }

        val saveBtn = Button(this).apply {
            text = "Save"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener {
                saveCurrentNote(note.id)
            }
        }
        buttonRow.addView(saveBtn)

        val deleteBtn = Button(this).apply {
            text = "Delete"
            setBackgroundColor(Color.parseColor("#E74C3C"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                notesList.removeAll { it.id == note.id }
                saveNotesToPrefs()
                notesAdapter.updateList(notesList)
                updateBubbleCount()
                showNoteList()
                Toast.makeText(this@FloatingBubbleService, "Note deleted", Toast.LENGTH_SHORT).show()
            }
        }
        buttonRow.addView(deleteBtn)
        container.addView(buttonRow)

        // Share button
        val shareBtn = Button(this).apply {
            text = "Share"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
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
        container.addView(shareBtn)

        // Resize handle
        val resizeHandleView = TextView(this).apply {
            text = "◢"
            textSize = 18f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.END or Gravity.BOTTOM
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 32)
            lp.topMargin = 8
            layoutParams = lp
            setOnTouchListener(ResizeTouchListener())
        }
        container.addView(resizeHandleView)

        // Replace current view
        noteView?.let { windowManager.removeView(it) }
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

    private fun saveCurrentNote(noteId: Long) {
        val index = notesList.indexOfFirst { it.id == noteId }
        if (index != -1) {
            val updatedNote = notesList[index].copy(
                title = titleInput.text.toString().ifEmpty { "Untitled Note" },
                content = editText.text.toString(),
                lastEdited = System.currentTimeMillis()
            )
            notesList[index] = updatedNote
            saveNotesToPrefs()
            notesAdapter.updateList(notesList)
            updateBubbleCount()
            Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
            showNoteList()
        }
    }

    private fun showNoteList() {
        isInEditorMode = false
        
        val container = createFullNotePad()
        noteView?.let { windowManager.removeView(it) }
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

    private fun updateBubbleCount() {
        val countView = (bubbleView as? LinearLayout)?.getChildAt(1) as? TextView
        countView?.text = notesList.size.toString()
        countView?.visibility = if (notesList.size > 0) View.VISIBLE else View.GONE
    }

    inner class NoteAdapter(
        private var notes: List<NoteItem>,
        private val onItemClick: (NoteItem) -> Unit,
        private val onDeleteClick: (NoteItem) -> Unit
    ) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

        fun updateList(newNotes: List<NoteItem>) {
            notes = newNotes.toList()
            notifyItemRangeChanged(0, notes.size)
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
                titleView.text = note.title
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
                    resizeTouchTime = System.currentTimeMillis()
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
                    if (params != null && System.currentTimeMillis() - resizeTouchTime > 100) {
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

    override fun onDestroy() {
        super.onDestroy()
        saveRunnable?.let { saveHandler.removeCallbacks(it) }
        flingAnimator?.cancel()
        velocityTracker?.recycle()
        bubbleView?.let { windowManager.removeView(it) }
        noteView?.let { windowManager.removeView(it) }
        deleteZoneView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?) = null
}
