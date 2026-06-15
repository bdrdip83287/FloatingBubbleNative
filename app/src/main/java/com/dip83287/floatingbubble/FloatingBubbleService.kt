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
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
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
import kotlin.math.min
import kotlin.math.max

class FloatingBubbleService : Service() {

    private val BUBBLE_COLOR = "#808080"
    private val NOTEPAD_BG_COLOR = "#FFF8DC"
    private val BUBBLE_ICON = "����"
    private val BUBBLE_SIZE = 110
    private val DELETE_ZONE_SIZE = 110
    private val HIDDEN_WIDTH = (BUBBLE_SIZE * 0.1f).toInt()

    private val NOTEPAD_TITLE = "Floating Notes"
    private val NOTEPAD_MIN_WIDTH = 380
    private val NOTEPAD_MIN_HEIGHT = 500
    private val NOTEPAD_MAX_WIDTH = 650
    private val NOTEPAD_MAX_HEIGHT = 850

    private val STORAGE_NOTES_LIST = "notes_list"
    private val KEY_FIRST_TIME_BUBBLE = "first_time_bubble"

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
    private lateinit var scrollView: ScrollView
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
    private var velocityY = 0f

    private var floatingActionBar: View? = null
    private var isActionBarVisible = false
    private var actionBarWindowManager: WindowManager? = null
    
    // Selection handles
    private var leftHandleView: View? = null
    private var rightHandleView: View? = null
    private var isDraggingLeftHandle = false
    private var isDraggingRightHandle = false

    private var scrollHideHandler: Handler? = null
    private var scrollHideRunnable: Runnable? = null
    private var isActionBarTemporarilyHidden = false
    private var currentSelectedText = ""

    private val handleUpdateDebounceHandler = Handler(Looper.getMainLooper())
    private var handleUpdatePending = false
    
    private var isScrolling = false
    private var scrollStopHandler: Handler? = null
    private val SCROLL_STOP_DELAY = 500L
    
    private var lastFontScale = 0f
    private var lastScreenWidth = 0
    private var lastScreenHeight = 0
    private val configCheckHandler = Handler(Looper.getMainLooper())
    private var configCheckRunnable: Runnable? = null

    private val notesList = mutableListOf<NoteItem>()
    private lateinit var notesAdapter: NoteAdapter
    private lateinit var recyclerView: RecyclerView
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
            actionBarWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            loadSavedPositions()
            loadNotes()
            createNotificationChannel()
            startForeground(1001, createNotification())
            createDeleteZone()
            scrollHideHandler = Handler(Looper.getMainLooper())
            scrollStopHandler = Handler(Looper.getMainLooper())
            
            lastFontScale = resources.configuration.fontScale
            lastScreenWidth = resources.displayMetrics.widthPixels
            lastScreenHeight = resources.displayMetrics.heightPixels
            
            startConfigurationCheck()
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "FloatingBubbleService.onCreate")
        }
    }
    
    private fun startConfigurationCheck() {
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val currentFontScale = resources.configuration.fontScale
                    val currentScreenWidth = resources.displayMetrics.widthPixels
                    val currentScreenHeight = resources.displayMetrics.heightPixels
                    
                    if (currentFontScale != lastFontScale || 
                        currentScreenWidth != lastScreenWidth || 
                        currentScreenHeight != lastScreenHeight) {
                        
                        EmergencyLog.log("Configuration changed")
                        
                        lastFontScale = currentFontScale
                        lastScreenWidth = currentScreenWidth
                        lastScreenHeight = currentScreenHeight
                        
                        if (!isScrolling && editText.hasSelection()) {
                            updateHandlePositionsSafe()
                        }
                    }
                } catch (e: Exception) {
                    EmergencyLog.logException(e, "Configuration check")
                }
                configCheckHandler.postDelayed(this, 500)
            }
        }
        configCheckRunnable = runnable
        configCheckHandler.postDelayed(runnable, 500)
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
            params.y = 150
            zone.visibility = View.GONE
            deleteZoneView = zone
            windowManager.addView(deleteZoneView, params)
            EmergencyLog.log("Delete zone created")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createDeleteZone")
        }
    }

    private fun showDeleteZone() {
        if (deleteZoneView?.visibility != View.VISIBLE) {
            deleteZoneView?.visibility = View.VISIBLE
            EmergencyLog.log("Delete zone shown")
        }
    }

    private fun hideDeleteZone() {
        if (deleteZoneView?.visibility != View.GONE) {
            deleteZoneView?.visibility = View.GONE
            EmergencyLog.log("Delete zone hidden")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            EmergencyLog.logError("Overlay permission not granted")
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
            EmergencyLog.log("First time bubble flag cleared")
        }
    }
    
    private fun resetFirstTimeFlag() {
        prefs.edit().putBoolean(KEY_FIRST_TIME_BUBBLE, true).apply()
        EmergencyLog.log("First time bubble flag reset (bubble deleted)")
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
            
            EmergencyLog.log("Bubble created at position: x=${params.x}, y=${params.y}")
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
                        showDeleteZone()
                        
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
                                EmergencyLog.log("Entered delete zone")
                            }
                        } else {
                            if (isInDeleteZone) {
                                isInDeleteZone = false
                                EmergencyLog.log("Left delete zone")
                            }
                        }

                        windowManager.updateViewLayout(bubbleView!!, params)

                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        hideDeleteZone()

                        if (isInDeleteZone) {
                            EmergencyLog.log("Bubble deleted via delete zone")
                            resetFirstTimeFlag()
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

                        velocityY = velocityTracker?.yVelocity ?: 0f

                        velocityTracker?.recycle()
                        velocityTracker = null

                        applyStableDockPhysics(params, displayMetrics)

                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        hideDeleteZone()
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
                    
                    saveBubblePosition(params.x, params.y)
                    EmergencyLog.log("Bubble position saved: x=${params.x}, y=${params.y}")
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
            
            // Setup scroll listener to track scrolling state
            scrollView.setOnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
                isScrolling = true
                scrollStopHandler?.removeCallbacks(scrollStopRunnable!!)
                scrollStopHandler?.postDelayed(scrollStopRunnable!!, SCROLL_STOP_DELAY)
            }
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createAndShowNotePad")
        }
    }

    private fun collapseToBubble() {
        if (!isExpanded) return

        hideSelectionHandles()
        hideFloatingActionBar()

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

    private fun createCircleHandleDrawable(): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2196F3")
                style = Paint.Style.FILL
            }
            override fun draw(canvas: Canvas) {
                val cx = bounds.width() / 2f
                val cy = bounds.height() / 2f
                val radius = bounds.width().coerceAtMost(bounds.height()) / 2f
                canvas.drawCircle(cx, cy, radius, paint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }
    
    private fun createSelectionHandles(): Pair<View, View> {
        val handleSize = 40
        
        val leftHandle = ImageView(this).apply {
            setImageDrawable(createCircleHandleDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            setOnTouchListener(HandleTouchListener(isLeft = true))
        }
        
        val rightHandle = ImageView(this).apply {
            setImageDrawable(createCircleHandleDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            setOnTouchListener(HandleTouchListener(isLeft = false))
        }
        
        return Pair(leftHandle, rightHandle)
    }
    
    inner class HandleTouchListener(private val isLeft: Boolean) : View.OnTouchListener {
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var initialSelectionStart = 0
        private var initialSelectionEnd = 0
        private var lastUpdateTime = 0L
        
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialSelectionStart = editText.selectionStart
                    initialSelectionEnd = editText.selectionEnd
                    lastUpdateTime = System.currentTimeMillis()
                    
                    if (isLeft) {
                        isDraggingLeftHandle = true
                    } else {
                        isDraggingRightHandle = true
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime < 16) {
                        return true
                    }
                    lastUpdateTime = currentTime
                    
                    val currentLayout = editText.layout
                    
                    if (currentLayout != null) {
                        val location = IntArray(2)
                        editText.getLocationOnScreen(location)
                        val textX = event.rawX - location[0]
                        val textY = event.rawY - location[1] + editText.scrollY
                        
                        val line = currentLayout.getLineForVertical(textY.toInt().coerceIn(0, currentLayout.height - 1))
                        val offset = currentLayout.getOffsetForHorizontal(line, textX)
                        val newOffset = offset.coerceIn(0, editText.text.length)
                        
                        if (isLeft) {
                            if (newOffset < initialSelectionEnd) {
                                editText.setSelection(newOffset, initialSelectionEnd)
                            } else {
                                editText.setSelection(initialSelectionEnd, newOffset)
                            }
                        } else {
                            if (newOffset > initialSelectionStart) {
                                editText.setSelection(initialSelectionStart, newOffset)
                            } else {
                                editText.setSelection(newOffset, initialSelectionStart)
                            }
                        }
                        
                        // Update handle positions while dragging
                        updateHandlePositionsSafe()
                        
                        val (start, end) = getSelection()
                        if (start != end && start >= 0 && end <= editText.text.length) {
                            val selected = editText.text.substring(start, end)
                            if (selected.isNotEmpty()) {
                                currentSelectedText = selected
                                showFloatingActionBar(selected)
                            }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingLeftHandle = false
                    isDraggingRightHandle = false
                    return true
                }
            }
            return false
        }
    }
    
    private fun updateHandlePositionsSafe() {
        // Don't update handles if currently scrolling
        if (isScrolling || handleUpdatePending) return
        handleUpdatePending = true
        handleUpdateDebounceHandler.post {
            try {
                updateHandlePositions()
            } finally {
                handleUpdatePending = false
            }
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    // Check if position is within viewport
    private fun isPositionInViewport(y: Int, height: Int): Boolean {
        val scrollLocation = IntArray(2)
        scrollView.getLocationOnScreen(scrollLocation)
        val viewportTop = scrollLocation[1]
        val viewportBottom = scrollLocation[1] + scrollView.height
        
        return (y + height > viewportTop && y < viewportBottom)
    }
    
    private fun updateHandlePositions() {
        try {
            // Don't update if scrolling
            if (isScrolling) return
            
            val currentLayout = editText.layout ?: return

            val start = editText.selectionStart
            val end = editText.selectionEnd
            
            if (start == end || start < 0 || end < 0 || start > editText.text.length || end > editText.text.length) {
                if (leftHandleView != null || rightHandleView != null) {
                    hideSelectionHandles()
                }
                return
            }

            val editLocation = IntArray(2)
            editText.getLocationOnScreen(editLocation)
            val editScreenX = editLocation[0]
            val editScreenY = editLocation[1]

            val startLine = currentLayout.getLineForOffset(start)
            val endLine = currentLayout.getLineForOffset(end)
            
            val startXRaw = currentLayout.getPrimaryHorizontal(start)
            val endXRaw = currentLayout.getPrimaryHorizontal(end)
            val scrollX = editText.scrollX
            val paddingLeft = editText.paddingLeft
            
            val startX = startXRaw - scrollX + paddingLeft
            val endX = endXRaw - scrollX + paddingLeft
            
            val startYRaw = currentLayout.getLineTop(startLine)
            val endYRaw = currentLayout.getLineTop(endLine)
            val scrollY = editText.scrollY
            val paddingTop = editText.paddingTop
            
            val startY = startYRaw - scrollY + paddingTop
            val endY = endYRaw - scrollY + paddingTop

            val handleSize = 40
            val halfHandle = handleSize / 2
            val upwardShift = dpToPx(15)

            val leftHandleScreenX = editScreenX + startX - halfHandle
            val leftHandleScreenY = editScreenY + startY - handleSize - upwardShift
            val rightHandleScreenX = editScreenX + endX - halfHandle
            val rightHandleScreenY = editScreenY + endY - handleSize - upwardShift
            
            // Create handles if they don't exist
            if (leftHandleView == null || rightHandleView == null) {
                val handles = createSelectionHandles()
                leftHandleView = handles.first
                rightHandleView = handles.second
            }
            
            // Update handle positions without recreating them
            updateHandlePosition(leftHandleView!!, leftHandleScreenX, leftHandleScreenY, handleSize)
            updateHandlePosition(rightHandleView!!, rightHandleScreenX, rightHandleScreenY, handleSize)
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "updateHandlePositions")
        }
    }
    
    private fun updateHandlePosition(handle: View, x: Int, y: Int, size: Int) {
        try {
            if (handle.parent == null) {
                val params = WindowManager.LayoutParams(
                    size, size,
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                params.x = x
                params.y = y
                windowManager.addView(handle, params)
            } else {
                val params = handle.layoutParams as WindowManager.LayoutParams
                params.x = x
                params.y = y
                windowManager.updateViewLayout(handle, params)
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "updateHandlePosition")
        }
    }
    
    private fun showSelectionHandles() {
        try {
            if (leftHandleView?.visibility != View.VISIBLE) {
                leftHandleView?.visibility = View.VISIBLE
            }
            if (rightHandleView?.visibility != View.VISIBLE) {
                rightHandleView?.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "showSelectionHandles")
        }
    }
    
    private fun hideSelectionHandles() {
        try {
            if (leftHandleView != null) {
                if (leftHandleView?.parent != null) {
                    windowManager.removeView(leftHandleView)
                }
                leftHandleView = null
            }
            if (rightHandleView != null) {
                if (rightHandleView?.parent != null) {
                    windowManager.removeView(rightHandleView)
                }
                rightHandleView = null
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "hideSelectionHandles")
        }
    }
    
    private fun getSelection(): Pair<Int, Int> {
        return Pair(editText.selectionStart, editText.selectionEnd)
    }
    
    private fun showFloatingActionBar(selectedText: String) {
        try {
            if (floatingActionBar == null) {
                createFloatingActionBar()
            }
            if (!isActionBarVisible) {
                floatingActionBar?.visibility = View.VISIBLE
                isActionBarVisible = true
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "showFloatingActionBar")
        }
    }
    
    private fun hideFloatingActionBar() {
        try {
            floatingActionBar?.visibility = View.GONE
            isActionBarVisible = false
        } catch (e: Exception) {
            EmergencyLog.logException(e, "hideFloatingActionBar")
        }
    }
    
    private fun createFloatingActionBar() {
        try {
            // Placeholder - implement as needed
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createFloatingActionBar")
        }
    }
    
    private fun createFullNotePad(): View {
        // Placeholder - implement as needed
        return LinearLayout(this)
    }
}
