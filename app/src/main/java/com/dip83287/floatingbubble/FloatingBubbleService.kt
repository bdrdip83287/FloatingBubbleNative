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
import kotlin.math.sqrt

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

    // বর্তমানে কোন Child Note editor খোলা আছে তা মনে রাখে।
    // Bubble-এ minimize করার পর আবার Bubble চাপলে একই editor পুনরায় খুলবে।
    private var activeNoteId: Long? = null
    private var isChildNoteOpen = false

    // Child Note minimize করার সময় editor-এর transient state সংরক্ষণ করে।
    // এতে cursor/selection এবং ScrollView position হুবহু restore করা যায়।
    private var hasSavedChildEditorState = false
    private var savedChildEditorNoteId: Long? = null
    private var savedChildSelectionStart = -1
    private var savedChildSelectionEnd = -1
    private var savedChildScrollY = 0

    private lateinit var editText: EditText
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
    private var deleteZoneAnimator: ValueAnimator? = null
private var deleteZoneHovered = false

private val DELETE_ZONE_HOVER_SCALE = 1.35f
    private var flingAnimator: ValueAnimator? = null

    private var velocityTracker: VelocityTracker? = null
    private var velocityY = 0f

    private var floatingActionBar: View? = null
    private var isActionBarVisible = false
    private var actionBarWindowManager: WindowManager? = null
    
    private var leftHandleView: View? = null
    private var rightHandleView: View? = null
    private var isDraggingLeftHandle = false
    private var isDraggingRightHandle = false
    private var areHandlesVisible = false
    
    private var handleContainer: FrameLayout? = null
    
    private val HANDLE_SIZE = 44

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
    
    private var wereHandlesVisibleBeforeScroll = false

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
            pivotX = DELETE_ZONE_SIZE / 2f
            pivotY = DELETE_ZONE_SIZE / 2f

            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }

            background = shape
            setPadding(0, 0, 0, 0)
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
            DELETE_ZONE_SIZE,
            DELETE_ZONE_SIZE,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity =
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

        params.y = 150

        zone.visibility = View.GONE
        deleteZoneView = zone

        windowManager.addView(
            deleteZoneView,
            params
        )

        EmergencyLog.log("Delete zone created")

    } catch (e: Exception) {
        EmergencyLog.logException(
            e,
            "createDeleteZone"
        )
    }
}

    private fun showDeleteZone() {

    deleteZoneAnimator?.cancel()

    deleteZoneView?.apply {

        visibility = View.VISIBLE

        if (!deleteZoneHovered) {
            scaleX = 1f
            scaleY = 1f
        }
    }

    EmergencyLog.log("Delete zone shown")
}

private fun hideDeleteZone() {

    deleteZoneAnimator?.cancel()
    deleteZoneAnimator = null

    deleteZoneHovered = false
    isInDeleteZone = false

    deleteZoneView?.animate()?.cancel()

    deleteZoneView?.apply {

        val lp = layoutParams

        lp.width = DELETE_ZONE_SIZE
        lp.height = DELETE_ZONE_SIZE

        layoutParams = lp

        visibility = View.GONE
    }

    EmergencyLog.log(
        "Delete zone hidden and reset"
    )
}


private fun setDeleteZoneHovered(hovered: Boolean) {

    if (deleteZoneHovered == hovered) return

    deleteZoneHovered = hovered
    isInDeleteZone = hovered

    deleteZoneAnimator?.cancel()

    val zone = deleteZoneView ?: return

    val normalSize = DELETE_ZONE_SIZE

    val expandedSize =
        (DELETE_ZONE_SIZE * DELETE_ZONE_HOVER_SCALE).toInt()

    val currentSize =
        zone.layoutParams?.width ?: normalSize

    val targetSize =
        if (hovered) expandedSize else normalSize

    deleteZoneAnimator =
        ValueAnimator.ofInt(
            currentSize,
            targetSize
        ).apply {

            duration = 180L
            interpolator = DecelerateInterpolator()

            addUpdateListener { animator ->

                try {
                    val size =
                        animator.animatedValue as Int

                    val lp =
                        zone.layoutParams as WindowManager.LayoutParams

                    lp.width = size
                    lp.height = size

                    /*
                     * গুরুত্বপূর্ণ:
                     * শুধু View-এর layoutParams নয়,
                     * WindowManager-এর actual overlay window-ও resize হবে।
                     */
                    windowManager.updateViewLayout(
                        zone,
                        lp
                    )

                    zone.requestLayout()
                    zone.invalidate()

                } catch (e: Exception) {
                    EmergencyLog.logException(
                        e,
                        "DeleteZone resize"
                    )
                }
            }

            addListener(
                object : Animator.AnimatorListener {

                    override fun onAnimationStart(
                        animation: Animator
                    ) {
                    }

                    override fun onAnimationEnd(
                        animation: Animator
                    ) {
                        try {
                            val lp =
                                zone.layoutParams as WindowManager.LayoutParams

                            lp.width = targetSize
                            lp.height = targetSize

                            windowManager.updateViewLayout(
                                zone,
                                lp
                            )

                        } catch (e: Exception) {
                            EmergencyLog.logException(
                                e,
                                "DeleteZone resize end"
                            )
                        }

                        deleteZoneAnimator = null
                    }

                    override fun onAnimationCancel(
                        animation: Animator
                    ) {
                    }

                    override fun onAnimationRepeat(
                        animation: Animator
                    ) {
                    }
                }
            )

            start()
        }

    EmergencyLog.log(
        if (hovered) {
            "Delete zone HOVER ON - actual size=$targetSize"
        } else {
            "Delete zone HOVER OFF - actual size=$targetSize"
        }
    )
}

private fun checkBubbleDeleteZoneHover(
    bubbleParams: WindowManager.LayoutParams
) {
    val zone = deleteZoneView ?: return
    val bubble = bubbleView ?: return

    if (zone.visibility != View.VISIBLE) {
        return
    }

    val bubbleLocation = IntArray(2)
    bubble.getLocationOnScreen(bubbleLocation)

    val bubbleCenterX =
        bubbleLocation[0] + bubble.width / 2f

    val bubbleCenterY =
        bubbleLocation[1] + bubble.height / 2f

    val zoneLocation = IntArray(2)
    zone.getLocationOnScreen(zoneLocation)

    val zoneWidth =
        zone.layoutParams?.width
            ?: DELETE_ZONE_SIZE

    val zoneHeight =
        zone.layoutParams?.height
            ?: DELETE_ZONE_SIZE

    val zoneCenterX =
        zoneLocation[0] + zoneWidth / 2f

    val zoneCenterY =
        zoneLocation[1] + zoneHeight / 2f

    val dx =
        bubbleCenterX - zoneCenterX

    val dy =
        bubbleCenterY - zoneCenterY

    val distance = sqrt(
        (dx.toDouble() * dx.toDouble()) +
        (dy.toDouble() * dy.toDouble())
    ).toFloat()

    val bubbleRadius =
        bubble.width / 2f

    val normalRadius =
        DELETE_ZONE_SIZE / 2f

    val expandedRadius =
        normalRadius * DELETE_ZONE_HOVER_SCALE

    /*
     * প্রথমবার hover শুরু করার area।
     */
    val hoverTriggerDistance =
        normalRadius +
        bubbleRadius * 0.35f

    /*
     * Hover হওয়ার পর বড় actual detection area।
     */
    val expandedDetectionDistance =
        expandedRadius +
        bubbleRadius * 0.35f

    val inside =
        if (deleteZoneHovered) {
            distance <= expandedDetectionDistance
        } else {
            distance <= hoverTriggerDistance
        }

    setDeleteZoneHovered(inside)
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

    private fun getInitialBubblePosition(): Pair<Int, Int> {
    val screenWidth = resources.displayMetrics.widthPixels
    val isFirstTime = prefs.getBoolean(KEY_FIRST_TIME_BUBBLE, true)

    return if (isFirstTime) {
        val defaultX = screenWidth - BUBBLE_SIZE - 20
        val defaultY = 150
        Pair(defaultX, defaultY)
    } else {
        val savedX = prefs.getInt(
            KEY_BUBBLE_X,
            screenWidth - BUBBLE_SIZE + HIDDEN_WIDTH
        )
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

val (defaultX, defaultY) = getInitialBubblePosition()
params.x = defaultX
params.y = defaultY

setupBubbleTouchListener(params)
            setupBubbleLongClickListener()

            windowManager.addView(bubbleView, params)
            markBubbleCreated()
            
            EmergencyLog.log("Bubble created at position: x=${params.x}, y=${params.y}")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "createBubble")
        }
    }

    private fun setupBubbleTouchListener(
    params: WindowManager.LayoutParams
) {

    bubbleView?.setOnTouchListener(
        object : View.OnTouchListener {

            private var initialX = 0
            private var initialY = 0

            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(
                v: View,
                event: MotionEvent
            ): Boolean {

                velocityTracker ?: run {
                    velocityTracker =
                        VelocityTracker.obtain()
                }

                velocityTracker?.addMovement(event)

                when (event.actionMasked) {

                    MotionEvent.ACTION_DOWN -> {

                        showDeleteZone()

                        flingAnimator?.cancel()

                        initialX = params.x
                        initialY = params.y

                        touchX = event.rawX
                        touchY = event.rawY

                        isInDeleteZone = false
                        deleteZoneHovered = false

                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {

                        val dx =
                            event.rawX - touchX

                        val dy =
                            event.rawY - touchY

                        params.x =
                            initialX + dx.toInt()

                        params.y =
                            initialY + dy.toInt()

                        /*
                         * প্রকৃত Delete Zone circle
                         * অনুযায়ী hover detection।
                         */
                        checkBubbleDeleteZoneHover(params)

                        try {

                            windowManager.updateViewLayout(
                                bubbleView!!,
                                params
                            )

                        } catch (e: Exception) {

                            EmergencyLog.logException(
                                e,
                                "Bubble move update"
                            )
                        }

                        return true
                    }

                    MotionEvent.ACTION_UP -> {

                        val wasInDeleteZone =
                            isInDeleteZone

                        hideDeleteZone()

                        /*
                         * Finger তোলার মুহূর্তে যদি Bubble
                         * Delete Zone-এর ভিতরে থাকে,
                         * তখনই delete হবে।
                         */
                        if (wasInDeleteZone) {

                            EmergencyLog.log(
                                "Bubble deleted via delete zone"
                            )

                            resetFirstTimeFlag()
                            deleteBubble()

                            return true
                        }

                        val deltaX =
                            abs(
                                event.rawX - touchX
                            )

                        val deltaY =
                            abs(
                                event.rawY - touchY
                            )

                        /*
                         * Normal tap.
                         */
                        if (
                            deltaX < 10 &&
                            deltaY < 10
                        ) {

                            expandToNotePad()

                            return true
                        }

                        velocityTracker
                            ?.computeCurrentVelocity(1000)

                        velocityY =
                            velocityTracker?.yVelocity
                                ?: 0f

                        velocityTracker
                            ?.recycle()

                        velocityTracker = null

                        applyStableDockPhysics(params)

                        return true
                    }

                    MotionEvent.ACTION_CANCEL -> {

                        hideDeleteZone()

                        velocityTracker
                            ?.recycle()

                        velocityTracker = null

                        return true
                    }
                }

                return false
            }
        }
    )
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
    params: WindowManager.LayoutParams
) {
    val screenWidth = resources.displayMetrics.widthPixels
    val screenHeight = resources.displayMetrics.heightPixels

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
            // যদি Bubble-এ minimize করার সময় কোনো Child Note editor খোলা ছিল,
            // তাহলে Note List নয়, সেই একই Child Note editor-ই পুনরায় খুলবে।
            if (isChildNoteOpen && activeNoteId != null) {
                val activeNote = notesList.firstOrNull { it.id == activeNoteId }
                if (activeNote != null) {
                    openEditorForNote(activeNote)
                    return
                } else {
                    // Note আর না থাকলে নিরাপদভাবে Note List-এ fallback করবে।
                    activeNoteId = null
                    isChildNoteOpen = false
                }
            }

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
        wereHandlesVisibleBeforeScroll = false
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

    private fun createCircleHandleDrawable(): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2196F3")
                style = Paint.Style.FILL
            }
            override fun draw(canvas: Canvas) {
                val cx = bounds.width() / 2f
                val cy = bounds.height() / 2f
                val radius = bounds.width().coerceAtMost(bounds.height()) / 2f - 2f
                canvas.drawCircle(cx, cy, radius, paint)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }
    
    private fun createSelectionHandles(): Pair<View, View> {
        val leftHandle = ImageView(this).apply {
            setImageDrawable(createCircleHandleDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            setOnTouchListener(HandleTouchListener(isLeft = true))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(HANDLE_SIZE, HANDLE_SIZE)
        }
        
        val rightHandle = ImageView(this).apply {
            setImageDrawable(createCircleHandleDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            setOnTouchListener(HandleTouchListener(isLeft = false))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(HANDLE_SIZE, HANDLE_SIZE)
        }
        
        return Pair(leftHandle, rightHandle)
    }
    
    inner class HandleTouchListener(
    private val isLeft: Boolean
) : View.OnTouchListener {

    private var initialSelectionStart = 0
    private var initialSelectionEnd = 0

    private var lastUpdateTime = 0L
    private var lastMagnifierTime = 0L

    private var magnifier: Magnifier? = null

    private val frameInterval = 16L

    private fun createHandleMagnifier() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        if (magnifier == null) {

            val density = resources.displayMetrics.density

            magnifier =
                Magnifier.Builder(editText)
                    .setSize(
                        (130f * density).toInt(),
                        (50f * density).toInt()
                    )
                    .setCornerRadius(
                        15f * density
                    )
                    .build()
        }
    }

    private fun showHandleMagnifier(
        rawX: Float,
        rawY: Float,
        force: Boolean = false
    ) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        val now =
            System.currentTimeMillis()

        if (
            !force &&
            now - lastMagnifierTime < frameInterval
        ) {
            return
        }

        lastMagnifierTime = now

        try {

            createHandleMagnifier()

            val location =
                IntArray(2)

            editText.getLocationOnScreen(
                location
            )

            val localX =
                (
                    rawX - location[0]
                ).coerceIn(
                    0f,
                    editText.width.toFloat()
                )

            val localY =
                (
                    rawY - location[1]
                ).coerceIn(
                    0f,
                    editText.height.toFloat()
                )

            magnifier?.show(
                localX,
                localY
            )

        } catch (e: Exception) {

            EmergencyLog.logException(
                e,
                "showHandleMagnifier"
            )
        }
    }

    private fun hideHandleMagnifier() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        try {

            magnifier?.dismiss()

        } catch (e: Exception) {

            EmergencyLog.logException(
                e,
                "hideHandleMagnifier"
            )
        }

        lastMagnifierTime = 0L
    }

    override fun onTouch(
        v: View,
        event: MotionEvent
    ): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                initialSelectionStart =
                    editText.selectionStart

                initialSelectionEnd =
                    editText.selectionEnd

                lastUpdateTime = 0L

                if (isLeft) {
                    isDraggingLeftHandle = true
                } else {
                    isDraggingRightHandle = true
                }

                editText.requestFocus()

                showHandleMagnifier(
                    event.rawX,
                    event.rawY,
                    true
                )

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                val now =
                    System.currentTimeMillis()

                if (
                    now - lastUpdateTime <
                    frameInterval
                ) {

                    showHandleMagnifier(
                        event.rawX,
                        event.rawY
                    )

                    return true
                }

                lastUpdateTime = now

                val layout =
                    editText.layout ?: return true

                val location =
                    IntArray(2)

                editText.getLocationOnScreen(
                    location
                )

                val textX =
                    event.rawX -
                    location[0] +
                    editText.scrollX

                val textY =
                    event.rawY -
                    location[1] +
                    editText.scrollY

                val safeY =
                    textY.toInt().coerceIn(
                        0,
                        (layout.height - 1)
                            .coerceAtLeast(0)
                    )

                val line =
                    layout.getLineForVertical(
                        safeY
                    )

                val offset =
                    layout.getOffsetForHorizontal(
                        line,
                        textX
                    )

                val newOffset =
                    offset.coerceIn(
                        0,
                        editText.text.length
                    )

                if (isLeft) {

                    if (
                        newOffset <
                        initialSelectionEnd
                    ) {

                        editText.setSelection(
                            newOffset,
                            initialSelectionEnd
                        )

                    } else {

                        editText.setSelection(
                            initialSelectionEnd,
                            newOffset
                        )
                    }

                } else {

                    if (
                        newOffset >
                        initialSelectionStart
                    ) {

                        editText.setSelection(
                            initialSelectionStart,
                            newOffset
                        )

                    } else {

                        editText.setSelection(
                            newOffset,
                            initialSelectionStart
                        )
                    }
                }

                /*
                 * Magnifier update throttled.
                 */
                showHandleMagnifier(
                    event.rawX,
                    event.rawY
                )

                /*
                 * Handle position update throttled.
                 */
                if (!isScrolling) {
                    updateHandlePositionsSafe()
                }

                /*
                 * শুধু selected text update করা হচ্ছে।
                 * FloatingActionBar এখানে recreate করা হচ্ছে না।
                 */
                val start =
                    editText.selectionStart

                val end =
                    editText.selectionEnd

                if (
                    start >= 0 &&
                    end > start &&
                    end <= editText.text.length
                ) {

                    currentSelectedText =
                        editText.text.substring(
                            start,
                            end
                        )

                    isActionBarTemporarilyHidden =
                        true
                }

                return true
            }

            MotionEvent.ACTION_UP -> {

                isDraggingLeftHandle = false
                isDraggingRightHandle = false

                hideHandleMagnifier()

                if (editText.hasSelection()) {

                    val start =
                        editText.selectionStart

                    val end =
                        editText.selectionEnd

                    if (
                        start >= 0 &&
                        end > start &&
                        end <= editText.text.length
                    ) {

                        val selected =
                            editText.text.substring(
                                start,
                                end
                            )

                        if (selected.isNotEmpty()) {

                            currentSelectedText =
                                selected

                            isActionBarTemporarilyHidden =
                                false

                            showFloatingActionBar(
                                selected
                            )

                            showSelectionHandles()

                            updateHandlePositionsImmediate()
                        }
                    }
                }

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                isDraggingLeftHandle = false
                isDraggingRightHandle = false

                hideHandleMagnifier()

                return true
            }
        }

        return false
    }
}
    
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
    
    private fun updateHandlePositionsImmediate() {
        try {
            updateHandlePositions()
        } catch (e: Exception) {
            EmergencyLog.logException(e, "updateHandlePositionsImmediate")
        }
    }
    
    private fun dpToPx(dp: Int): Int {
    return (dp.toFloat() * resources.displayMetrics.density).toInt()
}
    
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
                    params.topMargin = (startY - halfHandle).toInt()
                    handle.layoutParams = params
                    handle.visibility = View.VISIBLE
                    handle.alpha = 1f
                }
            }
            
            rightHandleView?.let { handle ->
                val params = handle.layoutParams as? FrameLayout.LayoutParams
                if (params != null) {
                    val gap = 14
                    params.leftMargin = (endX + gap).toInt()
                    params.topMargin = (endY - halfHandle).toInt()
                    handle.layoutParams = params
                    handle.visibility = View.VISIBLE
                    handle.alpha = 1f
                }
            }
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "updateHandlePositions")
        }
    }
    
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
    
    private fun EditText.setOnSelectionChangedListener(callback: (selStart: Int, selEnd: Int) -> Unit) {
        this.setCustomSelectionActionModeCallback(object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
            override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
            override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
        })
        
        val watcher = object : TextWatcher {
            private var prevStart = 0
            private var prevEnd = 0
            override fun afterTextChanged(s: Editable?) {
                val start = selectionStart
                val end = selectionEnd
                if (start != prevStart || end != prevEnd) {
                    prevStart = start
                    prevEnd = end
                    callback(start, end)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        addTextChangedListener(watcher)
    }
    
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
            
            updateHandlePositionsImmediate()
            
            leftHandleView?.visibility = View.VISIBLE
            leftHandleView?.alpha = 1f
            rightHandleView?.visibility = View.VISIBLE
            rightHandleView?.alpha = 1f
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "showSelectionHandles")
        }
    }
    
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
    
    private fun fadeOutHandlesDuringScroll() {
        try {
            leftHandleView?.let { handle ->
                if (handle.visibility == View.VISIBLE && handle.alpha > 0f) {
                    handle.animate()
                        ?.alpha(0f)
                        ?.setDuration(150)
                        ?.setInterpolator(DecelerateInterpolator())
                        ?.start()
                }
            }
            rightHandleView?.let { handle ->
                if (handle.visibility == View.VISIBLE && handle.alpha > 0f) {
                    handle.animate()
                        ?.alpha(0f)
                        ?.setDuration(150)
                        ?.setInterpolator(DecelerateInterpolator())
                        ?.start()
                }
            }
        } catch (e: Exception) { }
    }
    
    private fun fadeInHandlesAfterScroll() {
        try {
            if (editText.hasSelection()) {
                val (start, end) = getSelection()
                if (start != end) {
                    updateHandlePositionsImmediate()
                    leftHandleView?.let { handle ->
                        if (handle.visibility == View.VISIBLE) {
                            handle.animate()
                                ?.alpha(1f)
                                ?.setDuration(200)
                                ?.setInterpolator(DecelerateInterpolator())
                                ?.start()
                        }
                    }
                    rightHandleView?.let { handle ->
                        if (handle.visibility == View.VISIBLE) {
                            handle.animate()
                                ?.alpha(1f)
                                ?.setDuration(200)
                                ?.setInterpolator(DecelerateInterpolator())
                                ?.start()
                        }
                    }
                    areHandlesVisible = true
                }
            }
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
                val (start, end) = getSelection()
                if (start != end) {
                    val selectedText = editText.text.substring(start, end)
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = android.content.ClipData.newPlainText("text", selectedText)
                    clipboard.setPrimaryClip(clip)
                    
                    editText.setSelection(start, start)
                    hideSelectionHandles()
                    hideFloatingActionBar()
                    
                    Toast.makeText(this@FloatingBubbleService, "Copied", Toast.LENGTH_SHORT).show()
                }
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
    if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        WindowManager.LayoutParams.TYPE_PHONE,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT
)

params.gravity = Gravity.TOP or Gravity.START
params.x = x.toInt() - 50

// Action Bar-এর actual height মাপা
actionBarView.measure(
    View.MeasureSpec.makeMeasureSpec(
        0,
        View.MeasureSpec.UNSPECIFIED
    ),
    View.MeasureSpec.makeMeasureSpec(
        0,
        View.MeasureSpec.UNSPECIFIED
    )
)

val actionBarHeight =
    actionBarView.measuredHeight

// Selection-এর উপরে 15dp gap
val extraGap =
    (60f * resources.displayMetrics.density).toInt()

params.y =
    y.toInt() -
    actionBarHeight -
    extraGap
            
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
    
    private fun getOffsetAtPosition(editText: EditText, x: Float, y: Float): Int {
        try {
            val currentLayout = editText.layout ?: return -1
            val line = currentLayout.getLineForVertical(editText.scrollY + y.toInt())
            return currentLayout.getOffsetForHorizontal(line, x).coerceIn(0, editText.text.length)
        } catch (e: Exception) {
            return -1
        }
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
            textSize = 32f
            setTextColor(Color.parseColor("orange"))
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

    private fun isWordChar(char: Char): Boolean {
        val isBengali = char in '\u0980'..'\u09FF'
        val isHindi = char in '\u0900'..'\u097F'
        val isArabic = char in '\u0600'..'\u06FF'
        val isUrdu = char in '\u0600'..'\u06FF' || char in '\u0750'..'\u077F'
        val isLetterOrDigit = Character.isLetterOrDigit(char)
        val isSpecial = char == '.' || char == '_' || char == '-' || char == '@' || 
                       char == '#' || char == '$' || char == '%' || char == '&' ||
                       char == '*' || char == '+' || char == '=' || char == '~' ||
                       char == ':' || char == '/' || char == '\\'
        
        return isBengali || isHindi || isArabic || isUrdu || isLetterOrDigit || isSpecial
    }

    // ✅ Character by character selection during drag
    private fun handleDragSelection(editText: EditText, event: MotionEvent) {
        try {
            val currentLayout = editText.layout ?: return
            
            val editLocation = IntArray(2)
            editText.getLocationOnScreen(editLocation)
            
            val textX = event.rawX - editLocation[0] + editText.scrollX
            val textY = event.rawY - editLocation[1] + editText.scrollY
            
            val line = currentLayout.getLineForVertical(textY.toInt().coerceIn(0, currentLayout.height - 1))
            val offset = currentLayout.getOffsetForHorizontal(line, textX)
            val newOffset = offset.coerceIn(0, editText.text.length)
            
            if (editText.hasSelection()) {
                val currentStart = editText.selectionStart
                val currentEnd = editText.selectionEnd
                
                if (newOffset < currentStart) {
                    editText.setSelection(newOffset, currentEnd)
                } else if (newOffset > currentEnd) {
                    editText.setSelection(currentStart, newOffset)
                } else {
                    val distanceToStart = abs(newOffset - currentStart)
                    val distanceToEnd = abs(newOffset - currentEnd)
                    if (distanceToStart < distanceToEnd) {
                        editText.setSelection(newOffset, currentEnd)
                    } else {
                        editText.setSelection(currentStart, newOffset)
                    }
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
        } catch (e: Exception) {
            EmergencyLog.logException(e, "handleDragSelection")
        }
    }

    // ✅ Word selection for double tap / long press (no drag)
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
                    
                    while (wordStart > 0 && isWordChar(text[wordStart - 1])) {
                        wordStart--
                    }
                    
                    while (wordEnd < text.length && isWordChar(text[wordEnd])) {
                        wordEnd++
                    }
                    
                    if (wordStart == wordEnd) {
                        var tempStart = offset - 1
                        while (tempStart >= 0 && isWordChar(text[tempStart])) {
                            tempStart--
                        }
                        wordStart = tempStart + 1
                        
                        var tempEnd = offset
                        while (tempEnd < text.length && isWordChar(text[tempEnd])) {
                            tempEnd++
                        }
                        wordEnd = tempEnd
                    }
                    
                    if (wordStart < wordEnd) {
                        editText.setSelection(wordStart, wordEnd)
                        val selectedWord = text.substring(wordStart, wordEnd)
                        currentSelectedText = selectedWord
                        isActionBarTemporarilyHidden = false
                        
                        showFloatingActionBar(selectedWord)
                        
                        leftHandleView = null
                        rightHandleView = null
                        
                        showSelectionHandles()
                        updateHandlePositionsImmediate()
                        
                        Handler(Looper.getMainLooper()).postDelayed({
                            updateHandlePositionsImmediate()
                        }, 50)
                        
                        Handler(Looper.getMainLooper()).postDelayed({
                            updateHandlePositionsImmediate()
                        }, 150)
                        
                        Handler(Looper.getMainLooper()).postDelayed({
                            updateHandlePositionsImmediate()
                        }, 300)
                        
                        EmergencyLog.log("Selected word: '$selectedWord' at offset $offset")
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
            background = GradientDrawable().apply {
                setColor(Color.parseColor(NOTEPAD_BG_COLOR))
                setStroke(dpToPx(5), Color.parseColor("#333333"))
                cornerRadius = 0f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = 16f
        }
        
        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(40)
            )
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F9E79F"))
            setPadding(dpToPx(5), 0, dpToPx(5), 0)
            setOnTouchListener(TitleBarDragListener())
        }

        val titleText = TextView(this).apply {
            text = note.content.lineSequence().firstOrNull()?.trimEnd()?.ifEmpty { "Untitled Note" } ?: "Untitled Note"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
        }
        topBar.addView(titleText)

        fun createRoundTopButton(icon: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = icon
                textSize = 18f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(dpToPx(26), dpToPx(26)).apply {
                    marginStart = dpToPx(2)
                    marginEnd = dpToPx(2)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(when (icon) {
                        "‹" -> Color.parseColor("#00B8D4")
                        "⤴" -> Color.parseColor("#00C853")
                        else -> Color.parseColor("#FF6D00")
                    })
                    // ইচ্ছাকৃতভাবে কোনো border/stroke নেই
                }
                setOnClickListener { onClick() }
            }
        }

        val backBtn = createRoundTopButton("‹") {
            saveCurrentNoteData(note.id)
            hideSelectionHandles()
            hideFloatingActionBar()
            showNoteList()
        }
        topBar.addView(backBtn)

        val shareBtn = createRoundTopButton("⤴") {
            saveCurrentNoteData(note.id)
            shareLargeText(editText.text.toString())
        }
        topBar.addView(shareBtn)

        val minimizeBtn = createRoundTopButton("−") {
            try {
                saveRunnable?.let { saveHandler.removeCallbacks(it) }
                saveRunnable = null

                savedChildEditorNoteId = note.id
                savedChildSelectionStart = editText.selectionStart.coerceIn(0, editText.text.length)
                savedChildSelectionEnd = editText.selectionEnd.coerceIn(0, editText.text.length)
                savedChildScrollY = scrollView.scrollY
                hasSavedChildEditorState = true

                saveCurrentNoteData(note.id)
                hideSelectionHandles()
                hideFloatingActionBar()

                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(editText.windowToken, 0)

                activeNoteId = note.id
                isChildNoteOpen = true
                collapseToBubble()
            } catch (e: Exception) {
                EmergencyLog.logException(e, "Child Note minimize")
            }
        }
        topBar.addView(minimizeBtn)
        contentContainer.addView(topBar)

        // পুরো Child Note এখন একটি মাত্র EditText; আলাদা Title field নেই।

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
                    EmergencyLog.log("Scrolling started - fading out handles")
                    
                    wereHandlesVisibleBeforeScroll = areHandlesVisible
                    
                    if (areHandlesVisible) {
                        fadeOutHandlesDuringScroll()
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
                        EmergencyLog.log("Scrolling stopped - fading in handles")
                        
                        if (editText.hasSelection()) {
                            updateHandlePositionsSafe()
                            val (start, end) = getSelection()
                            if (start != end) {
                                val selected = editText.text.substring(start, end)
                                if (selected.isNotEmpty()) {
                                    currentSelectedText = selected
                                    isActionBarTemporarilyHidden = false
                                    showFloatingActionBar(selected)
                                    
                                    if (wereHandlesVisibleBeforeScroll) {
                                        fadeInHandlesAfterScroll()
                                    } else {
                                        showSelectionHandles()
                                    }
                                }
                            }
                        }
                        wereHandlesVisibleBeforeScroll = false
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
    private val longPressHandler =
        Handler(Looper.getMainLooper())

    private var isSelecting = false
    private var isDragging = false
    private var hasMoved = false
    private var isSingleTap = false

    private var touchStartX = 0f
    private var touchStartY = 0f

    private var totalDistance = 0f
    private var isScrollingDetected = false
    private var scrollConfirmed = false

    private var velocityTracker: VelocityTracker? = null
    private var moveCount = 0

    private var selectionMagnifier: Magnifier? = null
    private var lastMagnifierTime = 0L
    private var lastHandleUpdateTime = 0L
    private val frameInterval = 16L

    // Native EditText touch handling has to receive the normal editing gesture.
    // Custom selection/dragging takes control only after it is actually detected.
    private var nativeTouchActive = false
    private var customGestureActive = false

    private fun dispatchToNativeEditText(event: MotionEvent): Boolean {
        return try {
            this@apply.onTouchEvent(event)
        } catch (e: Exception) {
            EmergencyLog.logException(e, "dispatchToNativeEditText")
            false
        }
    }

    private fun cancelNativeEditTextGesture() {
        if (!nativeTouchActive) return

        try {
            val now = android.os.SystemClock.uptimeMillis()
            val cancelEvent = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_CANCEL,
                lastTouchX,
                lastTouchY,
                0
            )
            this@apply.onTouchEvent(cancelEvent)
            cancelEvent.recycle()
        } catch (e: Exception) {
            EmergencyLog.logException(
                e,
                "cancelNativeEditTextGesture"
            )
        }

        nativeTouchActive = false
    }

    private fun createMagnifier() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        if (selectionMagnifier == null) {
            val density = resources.displayMetrics.density
            selectionMagnifier = Magnifier.Builder(this@apply)
                .setSize(
                    (130f * density).toInt(),
                    (50f * density).toInt()
                )
                .setCornerRadius(15f * density)
                .build()
        }
    }

    private fun showSelectionMagnifier(
        rawX: Float,
        rawY: Float,
        force: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val now = System.currentTimeMillis()
        if (!force && now - lastMagnifierTime < frameInterval) return
        lastMagnifierTime = now

        try {
            createMagnifier()

            val location = IntArray(2)
            this@apply.getLocationOnScreen(location)

            val localX =
                (rawX - location[0]).coerceIn(
                    0f,
                    this@apply.width.toFloat()
                )

            val localY =
                (rawY - location[1]).coerceIn(
                    0f,
                    this@apply.height.toFloat()
                )

            selectionMagnifier?.show(localX, localY)
        } catch (e: Exception) {
            EmergencyLog.logException(e, "showSelectionMagnifier")
        }
    }

    private fun hideSelectionMagnifier() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            selectionMagnifier?.dismiss()
        } catch (e: Exception) {
            EmergencyLog.logException(e, "hideSelectionMagnifier")
        }

        lastMagnifierTime = 0L
    }

    private fun updateHandlesThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastHandleUpdateTime < frameInterval) return

        lastHandleUpdateTime = now

        if (!isScrolling) {
            updateHandlePositionsSafe()
        }
    }

    private fun updateSelectedTextDuringDrag() {
        val start = this@apply.selectionStart
        val end = this@apply.selectionEnd

        if (
            start < 0 ||
            end < 0 ||
            start == end ||
            start > this@apply.text.length ||
            end > this@apply.text.length
        ) return

        val selected = this@apply.text.substring(start, end)
        if (selected.isEmpty()) return

        currentSelectedText = selected
        isActionBarTemporarilyHidden = true
    }

    override fun onTouch(
        v: View,
        event: MotionEvent
    ): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val currentTime = System.currentTimeMillis()
                val x = event.x
                val y = event.y

                // IMPORTANT:
                // Read the previous tap position BEFORE overwriting lastTouchX/Y.
                val previousTouchTime = lastTouchTime
                val previousTouchX = lastTouchX
                val previousTouchY = lastTouchY

                val isDoubleTap =
                    previousTouchTime > 0L &&
                    currentTime - previousTouchTime < 300L &&
                    Math.abs(x - previousTouchX) < 50f &&
                    Math.abs(y - previousTouchY) < 50f

                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)

                cancelLongPress()
                hideSelectionMagnifier()

                isSelecting = false
                isDragging = false
                hasMoved = false
                isSingleTap = !isDoubleTap
                isScrollingDetected = false
                scrollConfirmed = false
                customGestureActive = false

                totalDistance = 0f
                moveCount = 0
                touchStartX = x
                touchStartY = y

                /*
                 * For a normal tap, let the real EditText receive ACTION_DOWN.
                 * This is what restores native cursor/keyboard/editing behavior.
                 * For a double-tap, the native first tap has already completed;
                 * the second tap is handled by our custom word-selection logic.
                 */
                if (!isDoubleTap) {
                    nativeTouchActive = dispatchToNativeEditText(event)
                } else {
                    nativeTouchActive = false

                    isSelecting = true
                    isSingleTap = false
                    customGestureActive = true

                    selectWordAtPosition(
                        this@apply,
                        x,
                        y,
                        true
                    )

                    if (this@apply.hasSelection()) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                }

                if (!isDoubleTap) {
                    val downX = x
                    val downY = y
                    val downRawX = event.rawX
                    val downRawY = event.rawY

                    val runnable = Runnable {
                        // A movement/scroll means this was not a long press.
                        if (hasMoved || isScrollingDetected || customGestureActive) {
                            return@Runnable
                        }

                        // Native EditText has already received the DOWN event.
                        // We now take control for custom word selection.
                        cancelNativeEditTextGesture()

                        isSelecting = true
                        isSingleTap = false
                        customGestureActive = true

                        selectWordAtPosition(
                            this@apply,
                            downX,
                            downY,
                            true
                        )

                        if (this@apply.hasSelection()) {
                            v.parent.requestDisallowInterceptTouchEvent(true)
                            showSelectionMagnifier(
                                downRawX,
                                downRawY,
                                true
                            )
                        }
                    }

                    longPressRunnable = runnable
                    longPressHandler.postDelayed(runnable, 300L)
                }

                // Update only AFTER double-tap detection.
                lastTouchTime = currentTime
                lastTouchX = x
                lastTouchY = y

                if (!customGestureActive) {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }

                // We manually dispatch to EditText when nativeTouchActive is true.
                // Returning true prevents View from dispatching the same event twice.
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                moveCount++

                val dx = Math.abs(event.x - touchStartX)
                val dy = Math.abs(event.y - touchStartY)
                val distance = sqrt(
                    (dx.toDouble() * dx.toDouble()) +
                    (dy.toDouble() * dy.toDouble())
                ).toFloat()

                totalDistance += distance

                if (dx > 10 || dy > 10) {
                    hasMoved = true
                    isSingleTap = false
                    cancelLongPress()
                }

                // Custom selection drag has priority over native scrolling.
                if (
                    customGestureActive &&
                    isSelecting &&
                    this@apply.hasSelection() &&
                    (dx > 20 || dy > 20)
                ) {
                    isDragging = true
                    isSingleTap = false
                    nativeTouchActive = false

                    v.parent.requestDisallowInterceptTouchEvent(true)

                    handleDragSelection(
                        this@apply,
                        event
                    )

                    updateSelectedTextDuringDrag()

                    showSelectionMagnifier(
                        event.rawX,
                        event.rawY
                    )

                    updateHandlesThrottled()
                    return true
                }

                if (
                    isDragging &&
                    customGestureActive &&
                    this@apply.hasSelection()
                ) {
                    updateSelectedTextDuringDrag()
                    showSelectionMagnifier(
                        event.rawX,
                        event.rawY
                    )
                    updateHandlesThrottled()
                    return true
                }

                // Detect normal ScrollView scrolling before native editing gets
                // a chance to turn the gesture into something else.
                if (
                    !customGestureActive &&
                    !isScrollingDetected &&
                    hasMoved
                ) {
                    velocityTracker?.computeCurrentVelocity(1000)

                    val velX = velocityTracker?.xVelocity ?: 0f
                    val velY = velocityTracker?.yVelocity ?: 0f
                    val velocity = sqrt(
                        (velX.toDouble() * velX.toDouble()) +
                        (velY.toDouble() * velY.toDouble())
                    ).toFloat()

                    if (
                        totalDistance > 30 ||
                        velocity > 100 ||
                        moveCount > 3
                    ) {
                        isScrollingDetected = true
                        scrollConfirmed = true
                        isSingleTap = false
                        cancelLongPress()
                        hideSelectionMagnifier()

                        if (this@apply.hasSelection()) {
                            currentSelectedText = this@apply.text.substring(
                                this@apply.selectionStart,
                                this@apply.selectionEnd
                            )
                        }

                        v.parent.requestDisallowInterceptTouchEvent(false)

                        // Pass the current MOVE to native EditText so that the
                        // already-started native gesture remains consistent.
                        if (nativeTouchActive) {
                            dispatchToNativeEditText(event)
                        }

                        return true
                    }
                }

                // Normal tap/editing/scrolling gesture: keep native EditText
                // receiving the complete event stream until custom selection
                // actually takes control.
                if (!customGestureActive && nativeTouchActive) {
                    dispatchToNativeEditText(event)
                }

                return true
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.recycle()
                velocityTracker = null

                cancelLongPress()
                hideSelectionMagnifier()

                v.parent.requestDisallowInterceptTouchEvent(false)

                /*
                 * NORMAL SINGLE TAP
                 * -----------------
                 * Do NOT manually setSelection() here. Native EditText has
                 * already calculated the exact cursor position and will also
                 * handle keyboard/cursor blinking correctly.
                 */
                if (
                    isSingleTap &&
                    !hasMoved &&
                    !isSelecting &&
                    !isDragging &&
                    !isScrollingDetected &&
                    !scrollConfirmed &&
                    !customGestureActive
                ) {
                    if (nativeTouchActive) {
                        dispatchToNativeEditText(event)
                    } else {
                        // Safety fallback for an unusual focus state.
                        this@apply.requestFocus()
                        val offset = getOffsetAtPosition(
                            this@apply,
                            event.x,
                            event.y
                        )
                        if (offset >= 0) {
                            this@apply.setSelection(offset, offset)
                        }
                    }

                    hideSelectionHandles()
                    hideFloatingActionBar()
                    isActionBarTemporarilyHidden = false

                    this@apply.post {
                        this@apply.requestFocus()
                        val imm = this@apply.context.getSystemService(
                            Context.INPUT_METHOD_SERVICE
                        ) as? InputMethodManager
                        imm?.showSoftInput(
                            this@apply,
                            InputMethodManager.SHOW_IMPLICIT
                        )
                    }
                } else if (
                    !customGestureActive &&
                    nativeTouchActive &&
                    !isScrollingDetected
                ) {
                    // Complete native gesture for non-custom interactions.
                    dispatchToNativeEditText(event)
                }

                if (
                    customGestureActive &&
                    isDragging &&
                    this@apply.hasSelection()
                ) {
                    val start = this@apply.selectionStart
                    val end = this@apply.selectionEnd

                    if (
                        start >= 0 &&
                        end > start &&
                        end <= this@apply.text.length
                    ) {
                        val selected = this@apply.text.substring(start, end)

                        if (selected.isNotEmpty()) {
                            currentSelectedText = selected
                            isActionBarTemporarilyHidden = false
                            showFloatingActionBar(selected)
                            showSelectionHandles()
                            updateHandlePositionsImmediate()
                        }
                    }
                }

                if (
                    customGestureActive &&
                    !isDragging &&
                    this@apply.hasSelection()
                ) {
                    val start = this@apply.selectionStart
                    val end = this@apply.selectionEnd

                    if (
                        start >= 0 &&
                        end > start &&
                        end <= this@apply.text.length
                    ) {
                        val selected = this@apply.text.substring(start, end)

                        if (selected.isNotEmpty()) {
                            currentSelectedText = selected
                            isActionBarTemporarilyHidden = false
                            showFloatingActionBar(selected)
                            showSelectionHandles()
                            updateHandlePositionsImmediate()
                        }
                    }
                }

                // For a native scroll gesture, preserve the selection and let
                // ScrollView perform its normal scrolling behavior.
                if (
                    isScrollingDetected &&
                    this@apply.hasSelection()
                ) {
                    val start = this@apply.selectionStart
                    val end = this@apply.selectionEnd

                    if (
                        start >= 0 &&
                        end > start &&
                        end <= this@apply.text.length
                    ) {
                        val selected = this@apply.text.substring(start, end)

                        if (selected.isNotEmpty()) {
                            currentSelectedText = selected
                            isActionBarTemporarilyHidden = false
                            showFloatingActionBar(selected)
                            showSelectionHandles()
                            updateHandlePositionsImmediate()
                        }
                    }
                }

                isSelecting = false
                isDragging = false
                hasMoved = false
                isSingleTap = false
                scrollConfirmed = false
                isScrollingDetected = false
                nativeTouchActive = false
                customGestureActive = false

                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null

                cancelLongPress()
                hideSelectionMagnifier()

                // Finish/cancel the native gesture if it was active.
                if (nativeTouchActive) {
                    dispatchToNativeEditText(event)
                }

                v.parent.requestDisallowInterceptTouchEvent(false)

                isSelecting = false
                isDragging = false
                hasMoved = false
                isSingleTap = false
                scrollConfirmed = false
                isScrollingDetected = false
                nativeTouchActive = false
                customGestureActive = false

                return true
            }
        }

        return true
    }

    private fun cancelLongPress() {
        longPressRunnable?.let {
            longPressHandler.removeCallbacks(it)
            longPressRunnable = null
        }
    }
})

// -------INTERVEL-----            
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Top bar title = note content-এর প্রথম লাইন
                    val firstLineTitle = s?.toString()?.lineSequence()?.firstOrNull()?.trimEnd()
                        ?.ifEmpty { "Untitled Note" } ?: "Untitled Note"
                    titleText.text = firstLineTitle

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
                                title = text.toString().lineSequence().firstOrNull()?.trimEnd()?.ifEmpty { "Untitled Note" } ?: "Untitled Note",
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

        // Save/Delete bottom bar সম্পূর্ণ বাদ দেওয়া হয়েছে।
        // Resize handle এখন নোটপ্যাডের একদম bottom-right corner-এ থাকবে।
        val resizeHandleView = TextView(this).apply {
            // শুধু bold resize arrow — কোনো button/background নেই
            text = "↘"
            textSize = 24f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#555555"))
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = null
            layoutParams = FrameLayout.LayoutParams(dpToPx(30), dpToPx(30), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = 0
                bottomMargin = 0
            }
            setOnTouchListener(ResizeTouchListener())
            bringToFront()
        }

        container.addView(contentContainer)
        container.addView(resizeHandleView)
        
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

            // Minimize করার সময়কার cursor/selection + scroll position restore করি।
            if (hasSavedChildEditorState && savedChildEditorNoteId == note.id) {
                val textLength = editText.text.length
                val restoreStart = savedChildSelectionStart.coerceIn(0, textLength)
                val restoreEnd = savedChildSelectionEnd.coerceIn(0, textLength)

                try {
                    editText.setSelection(restoreStart, restoreEnd)
                } catch (_: Exception) {
                    editText.setSelection(restoreStart)
                }

                scrollView.post {
                    scrollView.scrollTo(0, savedChildScrollY.coerceAtLeast(0))
                    editText.post {
                        try {
                            editText.setSelection(restoreStart, restoreEnd)
                        } catch (_: Exception) {
                            editText.setSelection(restoreStart)
                        }

                        if (restoreStart != restoreEnd) {
                            showSelectionHandles()
                            updateHandlePositionsImmediate()
                        } else {
                            hideSelectionHandles()
                            hideFloatingActionBar()
                        }
                    }
                }

                // State একবার restore হয়ে গেলে পরের নতুন editor-এ ভুল করে apply হবে না।
                hasSavedChildEditorState = false
                savedChildEditorNoteId = null
            }
        }, 300)
    }

    private fun EditText.hasSelection(): Boolean {
        return selectionStart != selectionEnd
    }

    private fun saveCurrentNoteData(noteId: Long) {
        val index = notesList.indexOfFirst { it.id == noteId }
        if (index != -1) {
            val updatedNote = notesList[index].copy(
                title = editText.text.toString().lineSequence().firstOrNull()?.trimEnd()?.ifEmpty { "Untitled Note" } ?: "Untitled Note",
                content = editText.text.toString(),
                lastEdited = System.currentTimeMillis()
            )
            notesList[index] = updatedNote
            saveNotesToPrefs()
            notesAdapter.updateList(notesList)
            updateBubbleCount()
        }
    }

    private fun saveCurrentNote(noteId: Long) {
        val index = notesList.indexOfFirst { it.id == noteId }
        if (index != -1) {
            saveCurrentNoteData(noteId)
            Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
            hideSelectionHandles()
            hideFloatingActionBar()
            showNoteList()
        }
    }

    private fun showNoteList() {
        // Child editor থেকে সত্যিকার অর্থে Note List-এ ফিরে এলে
        // active Child Note state clear হবে।
        activeNoteId = null
        isChildNoteOpen = false

        // সত্যিকার অর্থে Child Note থেকে বের হলে পুরোনো cursor/selection state আর রাখব না।
        hasSavedChildEditorState = false
        savedChildEditorNoteId = null
        savedChildSelectionStart = -1
        savedChildSelectionEnd = -1
        savedChildScrollY = 0

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
