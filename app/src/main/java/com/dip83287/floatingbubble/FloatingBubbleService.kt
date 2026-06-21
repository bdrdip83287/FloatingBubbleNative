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

class FloatingBubbleService : Service() {

    private val BUBBLE_COLOR = "#808080"
    private val NOTEPAD_BG_COLOR = "#FFF8DC"
    private val BUBBLE_ICON = "📝"
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
    
    // ✅ Custom selection handles
    private var leftHandleView: View? = null
    private var rightHandleView: View? = null
    private var isDraggingLeftHandle = false
    private var isDraggingRightHandle = false
    private var areHandlesVisible = false
    
    private var handleContainer: FrameLayout? = null
    
    private val HANDLE_SIZE = 40

    private var scrollHideHandler: Handler? = null
    private var scrollHideRunnable: Runnable? = null
    private var isActionBarTemporarilyHidden = false
    private var currentSelectedText = ""

    private val handleUpdateDebounceHandler = Handler(Looper.getMainLooper())
    private var handleUpdatePending = false
    
    private var isScrolling = false
    private var scrollStopHandler: Handler? = null
    private val SCROLL_STOP_DELAY = 500L
    private var lastScrollTime = 0L

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
                        
                        if (editText.hasSelection() && !isScrolling) {
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
                        
                        resetHandleReferences()
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
            
            handleContainer = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                isClickable = false
                isFocusable = false
            }
            
            (noteView as? ViewGroup)?.addView(handleContainer)
            
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
    
    private fun resetHandleReferences() {
        leftHandleView = null
        rightHandleView = null
        areHandlesVisible = false
        EmergencyLog.log("Handle references reset")
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
                        
                        resetHandleReferences()
                    }
                    .start()
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "collapseToBubble")
        }
    }

    // ✅ Create Android-style selection handle drawable
    private fun createHandleDrawable(color: Int): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
            private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            override fun draw(canvas: Canvas) {
                val cx = bounds.width() / 2f
                val cy = bounds.height() / 2f
                val radius = bounds.width().coerceAtMost(bounds.height()) / 2f - 2f
                canvas.drawCircle(cx, cy, radius, paint)
                canvas.drawCircle(cx, cy, radius, borderPaint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }
    
    // ✅ Create selection handles
    private fun createSelectionHandles(): Pair<View, View> {
        val leftHandle = ImageView(this).apply {
            setImageDrawable(createHandleDrawable(Color.parseColor("#2196F3")))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            setOnTouchListener(HandleTouchListener(isLeft = true))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(HANDLE_SIZE, HANDLE_SIZE)
        }
        
        val rightHandle = ImageView(this).apply {
            setImageDrawable(createHandleDrawable(Color.parseColor("#2196F3")))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            setOnTouchListener(HandleTouchListener(isLeft = false))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(HANDLE_SIZE, HANDLE_SIZE)
        }
        
        return Pair(leftHandle, rightHandle)
    }
    
    // ✅ Handle touch listener for dragging
    inner class HandleTouchListener(private val isLeft: Boolean) : View.OnTouchListener {
        private var initialTouchX = 0f
        private var initialSelectionStart = 0
        private var initialSelectionEnd = 0
        private var lastUpdateTime = 0L
        
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
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
                        val editLocation = IntArray(2)
                        editText.getLocationOnScreen(editLocation)
                        
                        val textX = event.rawX - editLocation[0] + editText.scrollX
                        val textY = event.rawY - editLocation[1] + editText.scrollY
                        
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
                        
                        if (!isScrolling) {
                            updateHandlePositionsSafe()
                        }
                        
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
    
    // ✅ Update handle positions with debounce
    private fun updateHandlePositionsSafe() {
        if (handleUpdatePending) return
        handleUpdatePending = true
        handleUpdateDebounceHandler.post {
            try {
                updateHandlePositions()
            } finally {
                handleUpdatePending = false
            }
        }
    }
    
    // ✅ Force immediate handle position update
    private fun updateHandlePositionsImmediate() {
        try {
            updateHandlePositions()
        } catch (e: Exception) {
            EmergencyLog.logException(e, "updateHandlePositionsImmediate")
        }
    }
    
    // ✅ Update handle positions based on selection
    private fun updateHandlePositions() {
        if (isScrolling) return
        
        try {
            val currentLayout = editText.layout ?: return
            if (leftHandleView == null || rightHandleView == null) {
                recreateHandlesIfNeeded()
                return
            }

            val start = editText.selectionStart
            val end = editText.selectionEnd
            
            if (start == end || start < 0 || end < 0 || start > editText.text.length || end > editText.text.length) {
                return
            }

            val editLocation = IntArray(2)
            editText.getLocationOnScreen(editLocation)
            
            val containerLocation = IntArray(2)
            handleContainer?.getLocationOnScreen(containerLocation) ?: return
            
            val relativeX = editLocation[0] - containerLocation[0]
            val relativeY = editLocation[1] - containerLocation[1]

            val startLine = currentLayout.getLineForOffset(start)
            val endLine = currentLayout.getLineForOffset(end)
            
            val startX = currentLayout.getPrimaryHorizontal(start) + relativeX
            val endX = currentLayout.getPrimaryHorizontal(end) + relativeX
            
            val startY = currentLayout.getLineBottom(startLine) + relativeY
            val endY = currentLayout.getLineBottom(endLine) + relativeY

            val halfHandle = HANDLE_SIZE / 2

            leftHandleView?.let { handle ->
                val params = handle.layoutParams as? FrameLayout.LayoutParams
                if (params != null) {
                    params.leftMargin = (startX - halfHandle).toInt()
                    params.topMargin = (startY - HANDLE_SIZE).toInt()
                    handle.layoutParams = params
                    handle.visibility = View.VISIBLE
                    handle.alpha = 1f
                }
            }
            
            rightHandleView?.let { handle ->
                val params = handle.layoutParams as? FrameLayout.LayoutParams
                if (params != null) {
                    params.leftMargin = (endX - halfHandle).toInt()
                    params.topMargin = (endY - HANDLE_SIZE).toInt()
                    handle.layoutParams = params
                    handle.visibility = View.VISIBLE
                    handle.alpha = 1f
                }
            }
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "updateHandlePositions")
        }
    }
    
    // ✅ Recreate handles if needed
    private fun recreateHandlesIfNeeded() {
        if (leftHandleView == null || rightHandleView == null) {
            val handles = createSelectionHandles()
            leftHandleView = handles.first
            rightHandleView = handles.second
            
            handleContainer?.removeAllViews()
            
            handleContainer?.addView(leftHandleView)
            handleContainer?.addView(rightHandleView)
            areHandlesVisible = true
            EmergencyLog.log("Handles recreated")
            
            updateHandlePositionsImmediate()
        }
    }
    
    // ✅ Show selection handles
    private fun showSelectionHandles() {
        try {
            val (start, end) = getSelection()
            if (start == end || start < 0 || end < 0) {
                hideSelectionHandles()
                return
            }
            
            if (leftHandleView == null || rightHandleView == null) {
                val handles = createSelectionHandles()
                leftHandleView = handles.first
                rightHandleView = handles.second
                
                handleContainer?.removeAllViews()
                handleContainer?.addView(leftHandleView)
                handleContainer?.addView(rightHandleView)
                areHandlesVisible = true
                EmergencyLog.log("Handles created and added to container")
            }
            
            // ✅ Force immediate position update
            updateHandlePositionsImmediate()
            
            leftHandleView?.visibility = View.VISIBLE
            leftHandleView?.alpha = 1f
            rightHandleView?.visibility = View.VISIBLE
            rightHandleView?.alpha = 1f
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "showSelectionHandles")
        }
    }
    
    // ✅ Hide selection handles with animation
    private fun hideSelectionHandles() {
        try {
            leftHandleView?.let { handle ->
                handle.animate()
                    ?.alpha(0f)
                    ?.setDuration(150)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.withEndAction {
                        handle.visibility = View.GONE
                    }
                    ?.start()
            }
            rightHandleView?.let { handle ->
                handle.animate()
                    ?.alpha(0f)
                    ?.setDuration(150)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.withEndAction {
                        handle.visibility = View.GONE
                    }
                    ?.start()
            }
            areHandlesVisible = false
        } catch (e: Exception) { }
    }

    private fun showFloatingActionBar(selectedText: String) {
        if (!isExpanded) return
        if (isActionBarTemporarilyHidden) return
        
        hideFloatingActionBar()
        
        val actionBarView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#333333"))
            setPadding(8, 6, 8, 6)
            
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
                setColor(Color.parseColor("#333333"))
            }
            background = shape
        }
        
        val chromeBtn = TextView(this).apply {
            text = "🌐"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(selectedText)}"))
                searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(searchIntent)
                hideFloatingActionBar()
            }
        }
        actionBarView.addView(chromeBtn)
        
        actionBarView.addView(createDivider())
        
        val cutBtn = TextView(this).apply {
            text = "Cut"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            setOnClickListener {
                val (start, end) = getSelection()
                if (start != end) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = android.content.ClipData.newPlainText("text", selectedText)
                    clipboard.setPrimaryClip(clip)
                    editText.text.delete(start, end)
                    hideFloatingActionBar()
                    hideSelectionHandles()
                    Toast.makeText(this@FloatingBubbleService, "Cut", Toast.LENGTH_SHORT).show()
                }
            }
        }
        actionBarView.addView(cutBtn)
        
        actionBarView.addView(createDivider())
        
        val copyBtn = TextView(this).apply {
            text = "Copy"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("text", selectedText)
                clipboard.setPrimaryClip(clip)
                hideFloatingActionBar()
                Toast.makeText(this@FloatingBubbleService, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        actionBarView.addView(copyBtn)
        
        actionBarView.addView(createDivider())
        
        val pasteBtn = TextView(this).apply {
            text = "Paste"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val pastedText = clip.getItemAt(0).text.toString()
                    val (start, end) = getSelection()
                    editText.text.replace(start, end, pastedText)
                    hideFloatingActionBar()
                    Toast.makeText(this@FloatingBubbleService, "Pasted", Toast.LENGTH_SHORT).show()
                }
            }
        }
        actionBarView.addView(pasteBtn)
        
        actionBarView.addView(createDivider())
        
        val selectAllBtn = TextView(this).apply {
            text = "Select all"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            setOnClickListener {
                editText.selectAll()
                val allText = editText.text.toString()
                currentSelectedText = allText
                showFloatingActionBar(allText)
                showSelectionHandles()
            }
        }
        actionBarView.addView(selectAllBtn)
        
        actionBarView.addView(createDivider())
        
        val shareBtn = TextView(this).apply {
            text = "Share"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(14, 8, 14, 8)
            setOnClickListener {
                hideFloatingActionBar()
                hideSelectionHandles()
                shareLargeText(selectedText)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isExpanded) {
                        collapseToBubble()
                    }
                }, 500)
            }
        }
        actionBarView.addView(shareBtn)
        
        floatingActionBar = actionBarView
        
        val location = IntArray(2)
        editText.getLocationOnScreen(location)
        
        val currentLayout = editText.layout
        if (currentLayout != null) {
            val start = editText.selectionStart
            val startLine = currentLayout.getLineForOffset(start)
            val x = currentLayout.getPrimaryHorizontal(start) + location[0]
            val y = currentLayout.getLineTop(startLine) + location[1]
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = x.toInt() - 50
            params.y = (y - 55).toInt()
            
            try {
                actionBarWindowManager?.addView(floatingActionBar, params)
                isActionBarVisible = true
            } catch (e: Exception) { }
        }
    }
    
    private fun shareLargeText(text: String) {
        try {
            if (text.length > 500000) {
                val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                val fileName = "shared_note_$timeStamp.txt"
                val cacheFile = java.io.File(cacheDir, fileName)
                
                cacheFile.writeText(text)
                
                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    cacheFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = Intent.createChooser(shareIntent, "Share Note")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(chooser)
                
                Handler(Looper.getMainLooper()).postDelayed({
                    try { if (cacheFile.exists()) cacheFile.delete() } catch (e: Exception) { }
                }, 60000)
            } else {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                val chooser = Intent.createChooser(shareIntent, "Share Note")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(chooser)
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "shareLargeText")
            Toast.makeText(this, "Failed to share text", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 24)
            setBackgroundColor(Color.parseColor("#666666"))
        }
    }
    
    private fun hideFloatingActionBar() {
        try {
            floatingActionBar?.let {
                actionBarWindowManager?.removeView(it)
                floatingActionBar = null
            }
        } catch (e: Exception) { }
        isActionBarVisible = false
    }
    
    private fun temporarilyHideActionBar() {
        if (isActionBarVisible && !isActionBarTemporarilyHidden) {
            isActionBarTemporarilyHidden = true
            hideFloatingActionBar()
        }
    }
    
    private fun scheduleActionBarShow() {
        scrollHideRunnable?.let { scrollHideHandler?.removeCallbacks(it) }
        
        val runnable = Runnable {
            if (isActionBarTemporarilyHidden && editText.hasSelection()) {
                val (start, end) = getSelection()
                if (start != end) {
                    val selected = editText.text.substring(start, end)
                    if (selected.isNotEmpty()) {
                        currentSelectedText = selected
                        isActionBarTemporarilyHidden = false
                        showFloatingActionBar(selected)
                    }
                } else {
                    isActionBarTemporarilyHidden = false
                }
            }
        }
        scrollHideRunnable = runnable
        scrollHideHandler?.postDelayed(runnable, 2000)
    }
    
    private fun getSelection(): Pair<Int, Int> {
        return Pair(editText.selectionStart, editText.selectionEnd)
    }

    private fun createFullNotePad(): View {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor(NOTEPAD_BG_COLOR))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = 16f
        }
        
        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

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
            setOnClickListener { 
                collapseToBubble()
            }
        }
        topBar.addView(minimizeBtn)
        contentContainer.addView(topBar)

        val noteCountText = TextView(this).apply {
            text = "Note List (${notesList.size})"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            setPadding(12, 16, 12, 8)
        }
        contentContainer.addView(noteCountText)

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
        contentContainer.addView(recyclerView)

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
        contentContainer.addView(addButton)

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
        contentContainer.addView(resizeHandleView)
        
        container.addView(contentContainer)
        
        handleContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
            bringToFront()
        }
        container.addView(handleContainer)

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

    // ✅ selectWordAtPosition with immediate handle positioning
    private fun selectWordAtPosition(editText: EditText, x: Float, y: Float, clearPrevious: Boolean = true) {
        try {
            val currentLayout = editText.layout
            if (currentLayout != null) {
                val line = currentLayout.getLineForVertical(editText.scrollY + y.toInt())
                val offset = currentLayout.getOffsetForHorizontal(line, x)
                
                val text = editText.text.toString()
                if (offset >= 0 && offset <= text.length) {
                    var wordStart = offset
                    var wordEnd = offset
                    
                    while (wordStart > 0 && text[wordStart - 1].isLetterOrDigit()) {
                        wordStart--
                    }
                    while (wordEnd < text.length && text[wordEnd].isLetterOrDigit()) {
                        wordEnd++
                    }
                    
                    if (wordStart < wordEnd) {
                        editText.setSelection(wordStart, wordEnd)
                        val selectedWord = text.substring(wordStart, wordEnd)
                        currentSelectedText = selectedWord
                        isActionBarTemporarilyHidden = false
                        
                        showFloatingActionBar(selectedWord)
                        showSelectionHandles()
                        updateHandlePositionsImmediate()
                        
                        EmergencyLog.log("Selected word: '$selectedWord' at offset $offset - handles positioned immediately")
                    }
                }
            }
        } catch (e: Exception) {
            EmergencyLog.logException(e, "selectWordAtPosition")
        }
    }

    private fun openEditorForNote(note: NoteItem) {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor(NOTEPAD_BG_COLOR))
        }
        
        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

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
                hideSelectionHandles()
                hideFloatingActionBar()
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
            setOnClickListener { 
                collapseToBubble()
            }
        }
        topBar.addView(minimizeBtn)
        contentContainer.addView(topBar)

        titleInput = EditText(this).apply {
            setText(note.title)
            hint = "Title"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 16, 8, 8)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextIsSelectable(true)
        }
        contentContainer.addView(titleInput)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            )
        }
        contentContainer.addView(divider)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
            setPadding(0, 0, 0, 0)
            isFocusable = false
            isFocusableInTouchMode = false
            
            setOnScrollChangeListener { _, _, _, _, _ ->
                val currentTime = System.currentTimeMillis()
                lastScrollTime = currentTime
                
                if (!isScrolling) {
                    isScrolling = true
                    EmergencyLog.log("Scrolling started - hiding handles")
                    
                    if (areHandlesVisible) {
                        hideSelectionHandles()
                    }
                    
                    if (editText.hasSelection() && isActionBarVisible) {
                        hideFloatingActionBar()
                        isActionBarTemporarilyHidden = true
                    }
                }
                
                scrollStopHandler?.removeCallbacksAndMessages(null)
                
                scrollStopHandler?.postDelayed({
                    if (lastScrollTime == currentTime) {
                        isScrolling = false
                        EmergencyLog.log("Scrolling stopped - showing handles with fade")
                        
                        if (editText.hasSelection()) {
                            updateHandlePositionsSafe()
                            val (start, end) = getSelection()
                            if (start != end) {
                                val selected = editText.text.substring(start, end)
                                if (selected.isNotEmpty()) {
                                    currentSelectedText = selected
                                    isActionBarTemporarilyHidden = false
                                    showFloatingActionBar(selected)
                                    showSelectionHandles()
                                }
                            }
                        }
                    }
                }, SCROLL_STOP_DELAY)
            }
        }
        
        editText = EditText(this).apply {
            setText(note.content)
            hint = "Write your note here..."
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            
            setLineSpacing(0f, 1.15f)
            setHorizontallyScrolling(false)
            maxLines = Int.MAX_VALUE
            minHeight = 400
            
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            
            setTextIsSelectable(true)
            isLongClickable = true
            customInsertionActionModeCallback = null
            customSelectionActionModeCallback = null
            
            isClickable = true
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            
            setOnSelectionChangedListener { _, _ ->
                if (!isScrolling) {
                    updateHandlePositionsSafe()
                }
            }
            
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (!isScrolling) {
                        updateHandlePositionsSafe()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            
            setOnTouchListener(object : View.OnTouchListener {
                private var lastTouchTime = 0L
                private var lastTouchX = 0f
                private var lastTouchY = 0f
                private var longPressRunnable: Runnable? = null
                private val longPressHandler = Handler(Looper.getMainLooper())
                private var isSelecting = false
                
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            val currentTime = System.currentTimeMillis()
                            val x = event.x
                            val y = event.y
                            
                            cancelLongPress()
                            
                            if (currentTime - lastTouchTime < 300 && 
                                Math.abs(x - lastTouchX) < 50 && 
                                Math.abs(y - lastTouchY) < 50) {
                                isSelecting = true
                                selectWordAtPosition(this@apply, x, y, true)
                            } else {
                                val runnable = Runnable {
                                    isSelecting = true
                                    selectWordAtPosition(this@apply, x, y, true)
                                }
                                longPressRunnable = runnable
                                longPressHandler.postDelayed(runnable, 300)
                            }
                            
                            lastTouchTime = currentTime
                            lastTouchX = x
                            lastTouchY = y
                            v.parent.requestDisallowInterceptTouchEvent(false)
                        }
                        
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            cancelLongPress()
                            
                            if (!isSelecting && this@apply.hasSelection()) {
                                val selected = this@apply.text.substring(this@apply.selectionStart, this@apply.selectionEnd)
                                if (selected.isNotEmpty()) {
                                    currentSelectedText = selected
                                    isActionBarTemporarilyHidden = false
                                    showFloatingActionBar(selected)
                                    showSelectionHandles()
                                    updateHandlePositionsImmediate()
                                }
                            } else if (!isSelecting && !this@apply.hasSelection()) {
                                hideSelectionHandles()
                                hideFloatingActionBar()
                            }
                            isSelecting = false
                        }
                        
                        MotionEvent.ACTION_MOVE -> {
                            val dx = Math.abs(event.x - lastTouchX)
                            val dy = Math.abs(event.y - lastTouchY)
                            if (dx > 20 || dy > 20) {
                                cancelLongPress()
                                if (this@apply.hasSelection() && !isScrolling) {
                                    updateHandlePositionsSafe()
                                }
                            }
                        }
                    }
                    return false
                }
                
                private fun cancelLongPress() {
                    val runnable = longPressRunnable
                    if (runnable != null) {
                        longPressHandler.removeCallbacks(runnable)
                        longPressRunnable = null
                    }
                }
            })
            
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!this@apply.hasSelection()) {
                        hideSelectionHandles()
                        hideFloatingActionBar()
                    } else {
                        val selected = s?.substring(selectionStart, selectionEnd) ?: ""
                        if (selected.isNotEmpty()) {
                            currentSelectedText = selected
                            showFloatingActionBar(selected)
                            showSelectionHandles()
                            updateHandlePositionsImmediate()
                        }
                    }
                    
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
        
        scrollView.addView(editText)
        contentContainer.addView(scrollView)

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
                hideSelectionHandles()
                hideFloatingActionBar()
                showNoteList()
                Toast.makeText(this@FloatingBubbleService, "Note deleted", Toast.LENGTH_SHORT).show()
            }
        }
        buttonRow.addView(deleteBtn)
        contentContainer.addView(buttonRow)

        val shareBtn = Button(this).apply {
            text = "Share Note"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
            setOnClickListener {
                shareLargeText("${titleInput.text}\n\n${editText.text}")
            }
        }
        contentContainer.addView(shareBtn)

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
        contentContainer.addView(resizeHandleView)
        
        container.addView(contentContainer)
        
        handleContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
            bringToFront()
        }
        container.addView(handleContainer)
        
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
        
        scrollView.postDelayed({
            editText.isFocusable = true
            editText.isFocusableInTouchMode = true
            editText.requestFocus()
            
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
        }, 300)
    }

    private fun EditText.hasSelection(): Boolean {
        return selectionStart != selectionEnd
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
            hideSelectionHandles()
            hideFloatingActionBar()
            showNoteList()
        }
    }

    private fun showNoteList() {
        hideSelectionHandles()
        hideFloatingActionBar()
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
        hideSelectionHandles()
        hideFloatingActionBar()
        scrollHideRunnable?.let { scrollHideHandler?.removeCallbacks(it) }
        scrollStopHandler?.removeCallbacksAndMessages(null)
        configCheckRunnable?.let { configCheckHandler.removeCallbacks(it) }
        EmergencyLog.log("FloatingBubbleService destroyed")
    }

    override fun onBind(intent: Intent?) = null
}