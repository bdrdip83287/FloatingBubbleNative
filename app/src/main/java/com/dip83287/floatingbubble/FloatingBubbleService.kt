package com.dip83287.floatingbubble

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.AlertDialog
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
import android.graphics.RectF
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
    // Child notepad resize limits. Minimum is exactly 120px × 120px.
    // Maximum is the current device display size, allowing full-screen access.
    private val NOTEPAD_MIN_WIDTH = 120
    private val NOTEPAD_MIN_HEIGHT = 120
    private val NOTEPAD_MAX_WIDTH: Int
        get() = resources.displayMetrics.widthPixels
    private val NOTEPAD_MAX_HEIGHT: Int
        get() = resources.displayMetrics.heightPixels

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

    // Child-note editor undo/redo state.
    // Each snapshot keeps text + cursor/selection + viewport, so Undo/Redo
    // changes the document without moving the visible editor to the bottom.
    private data class EditorHistoryState(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val scrollY: Int,
        val scrollX: Int,
        val editTextScrollY: Int,
        val editTextScrollX: Int
    )

    private val editorUndoStack = java.util.ArrayDeque<EditorHistoryState>()
    private val editorRedoStack = java.util.ArrayDeque<EditorHistoryState>()
    private var suppressEditorHistory = false
    private var isEditorLocked = false
    private var lastEditorText = ""
    private var historyInitializedForCurrentEditor = false

    // Child-note state that must survive minimize -> bubble -> expand.
    private var currentEditingNoteId: Long? = null
    private var restoreEditorStatePending = false
    private var savedEditorSelectionStart = 0
    private var savedEditorSelectionEnd = 0
    private var savedEditorScrollY = 0
    private var savedEditorScrollX = 0
    private var savedEditorEditTextScrollY = 0
    private var savedEditorEditTextScrollX = 0

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

    // Keeps the last real non-empty selection long enough to detect deletion
    // performed by the Android keyboard/IME. Some keyboards collapse the
    // selection before TextWatcher.beforeTextChanged() is dispatched.
    private var lastNonEmptySelectionStart = -1
    private var lastNonEmptySelectionEnd = -1

    // Prevent delayed selection/scroll callbacks from recreating the custom
    // selection UI immediately after the keyboard deletes a selected range.
    private var suppressSelectionUiUntil = 0L

    private fun isSelectionUiSuppressed(): Boolean =
        android.os.SystemClock.uptimeMillis() < suppressSelectionUiUntil

    private fun hideSelectionUiAfterImeDeletion() {
        suppressSelectionUiUntil = android.os.SystemClock.uptimeMillis() + 1200L
        currentSelectedText = ""
        lastNonEmptySelectionStart = -1
        lastNonEmptySelectionEnd = -1
        isActionBarTemporarilyHidden = false
        hideSelectionHandles()
        hideFloatingActionBar()

        // A few IMEs dispatch selection changes after the text deletion.
        // Run once more after that dispatch has completed.
        editText.post {
            currentSelectedText = ""
            hideSelectionHandles()
            hideFloatingActionBar()
        }
    }

    private fun handleImeSelectionDeletionIfNeeded() {
        val start = editText.selectionStart
        val end = editText.selectionEnd
        val hasLiveSelection = start >= 0 && end >= 0 && start != end
        val hasRememberedSelection =
            lastNonEmptySelectionStart >= 0 &&
            lastNonEmptySelectionEnd > lastNonEmptySelectionStart

        if (hasLiveSelection || hasRememberedSelection) {
            hideSelectionUiAfterImeDeletion()
        }
    }

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
        val lastEdited: Long = System.currentTimeMillis(),
        val createdAt: Long = System.currentTimeMillis(),
        var isLocked: Boolean = false
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


                        lastFontScale = currentFontScale
                        lastScreenWidth = currentScreenWidth
                        lastScreenHeight = currentScreenHeight

                        if (editText.hasSelection() && !isScrolling) {
                            updateHandlePositionsSafe()
                        }
                    }
                } catch (e: Exception) {
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
            .coerceIn(NOTEPAD_MIN_WIDTH, NOTEPAD_MAX_WIDTH)
        currentNotepadHeight = prefs.getInt(KEY_NOTEPAD_HEIGHT, NOTEPAD_MIN_HEIGHT)
            .coerceIn(NOTEPAD_MIN_HEIGHT, NOTEPAD_MAX_HEIGHT)
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


    } catch (e: Exception) {
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
        }
    }

    private fun resetFirstTimeFlag() {
        prefs.edit().putBoolean(KEY_FIRST_TIME_BUBBLE, true).apply()
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

        } catch (e: Exception) {
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
        }
    }

    private fun createAndShowNotePad() {
        if (noteView != null) return

        try {
            // If a child note was minimized, reopen that SAME child editor.
            // Never fall back to the Note List in that case.
            currentEditingNoteId?.let { id ->
                val activeNote = notesList.firstOrNull { it.id == id }
                if (activeNote != null) {
                    openEditorForNote(activeNote)
                    return
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
        }
    }

    private fun resetHandleReferences() {
        leftHandleView = null
        rightHandleView = null
        areHandlesVisible = false
        wereHandlesVisibleBeforeScroll = false
    }

    private fun collapseToBubble() {
        if (!isExpanded) return

        // Capture the exact editor state BEFORE the child note is removed.
        // This includes cursor/selection and the visible scroll position.
        if (::editText.isInitialized && currentEditingNoteId != null) {
            savedEditorSelectionStart = editText.selectionStart.coerceAtLeast(0)
            savedEditorSelectionEnd = editText.selectionEnd.coerceAtLeast(0)
            // Save BOTH containers. The visible front view is determined by
            // the ScrollView, while EditText can also have its own internal
            // scroll offset. Saving both prevents Android from changing the
            // viewport when the cursor/selection is restored.
            savedEditorScrollY = scrollView.scrollY.coerceAtLeast(0)
            savedEditorScrollX = scrollView.scrollX.coerceAtLeast(0)
            savedEditorEditTextScrollY = editText.scrollY.coerceAtLeast(0)
            savedEditorEditTextScrollX = editText.scrollX.coerceAtLeast(0)
            restoreEditorStatePending = true

            // Persist the latest text immediately so minimize cannot lose edits.
            val activeId = currentEditingNoteId!!
            val index = notesList.indexOfFirst { it.id == activeId }
            if (index >= 0) {
                val contentText = editText.text.toString()
                val rawTitle = if (::titleInput.isInitialized) titleInput.text.toString().trim() else ""
                val finalTitle = rawTitle.ifEmpty {
                    getEditorAutoTitle(contentText).ifEmpty { "Untitled Note" }
                }

                notesList[index] = notesList[index].copy(
                    content = contentText,
                    title = finalTitle,
                    lastEdited = System.currentTimeMillis()
                )
                saveNotesToPrefs()
            }
        }

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
        }
    }

    // ============================================================
    // Top Bar Icons - Canvas + Path + Drawable
    // Unicode/Text glyph সম্পূর্ণ বাদ দেওয়া হয়েছে।
    // তিনটি icon একই 24dp x 24dp coordinate system ব্যবহার করে,
    // তাই visual size ও vertical/horizontal alignment একই থাকে।
    // ============================================================

    private fun createTopBarIconButton(
        iconDrawable: Drawable,
        buttonColor: Int,
        clickAction: () -> Unit
    ): ImageButton {
        val size = dpToPx(24)
        return ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dpToPx(2)
                marginEnd = dpToPx(2)
            }
            setImageDrawable(iconDrawable)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(buttonColor)
                setStroke(dpToPx(1), Color.BLACK)
            }
            background = bg
            // Keep a uniform 2px gap between the custom icon and the black button border.
            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            minimumWidth = 0
            minimumHeight = 0
            scaleType = ImageView.ScaleType.CENTER
            elevation = dpToPx(2).toFloat()
            contentDescription = null
            isFocusable = true
            isClickable = true
            setOnClickListener { clickAction() }
        }
    }

    private fun createTopBarBackDrawable(): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(2).toFloat()
                strokeCap = Paint.Cap.SQUARE
                strokeJoin = Paint.Join.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.save()
                val iconScale = ((w - dpToPx(1).toFloat()).coerceAtLeast(1f)) / w
                canvas.scale(iconScale, iconScale, w / 2f, h / 2f)
                val cy = h / 2f
                val left = w * 0.20f
                val right = w * 0.78f
                val head = w * 0.20f
                val path = android.graphics.Path().apply {
                    moveTo(right, cy)
                    lineTo(left + head, cy)
                    moveTo(left + head, cy)
                    lineTo(left + head * 1.65f, cy - h * 0.22f)
                    moveTo(left + head, cy)
                    lineTo(left + head * 1.65f, cy + h * 0.22f)
                }
                canvas.drawPath(path, paint)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun createTopBarShareDrawable(): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.save()
                val iconScale = ((w - dpToPx(1).toFloat()).coerceAtLeast(1f)) / w
                canvas.scale(iconScale, iconScale, w / 2f, h / 2f)
                val left = w * 0.22f
                val right = w * 0.76f
                val cy = h * 0.50f
                val top = h * 0.25f
                val bottom = h * 0.75f

                // Center share node
                canvas.drawCircle(left, cy, w * 0.085f, paint)
                // Top node
                canvas.drawCircle(right, top, w * 0.085f, paint)
                // Bottom node
                canvas.drawCircle(right, bottom, w * 0.085f, paint)

                // Connecting lines
                canvas.drawLine(left + w * 0.10f, cy - h * 0.035f, right - w * 0.10f, top + h * 0.035f, paint)
                canvas.drawLine(left + w * 0.10f, cy + h * 0.035f, right - w * 0.10f, bottom - h * 0.035f, paint)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun createTopBarMinimizeDrawable(): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 145, 0)
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(2).toFloat()
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.save()
                val iconScale = ((w - dpToPx(1).toFloat()).coerceAtLeast(1f)) / w
                canvas.scale(iconScale, iconScale, w / 2f, h / 2f)
                val left = w * 0.22f
                val right = w * 0.78f
                val cy = h / 2f
                canvas.drawLine(left, cy, right, cy, paint)
                canvas.restore()
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun createTopBarCloseDrawable(): Drawable = createStrokePathDrawable { canvas, w, h, paint ->
        // Bold red X for closing the currently visible child note.
        paint.color = Color.rgb(220, 35, 35)
        paint.strokeWidth = dpToPx(2).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        val inset = w * 0.24f
        canvas.drawLine(inset, inset, w - inset, h - inset, paint)
        canvas.drawLine(w - inset, inset, inset, h - inset, paint)
    }

    private fun createTopBarUndoDrawable(): Drawable = createStrokePathDrawable { canvas, w, h, paint ->
        val path = android.graphics.Path()
        val cx = w * 0.50f
        val cy = h * 0.50f
        val r = w * 0.28f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 215f, 250f, false, paint)
        val ah = w * 0.18f
        val arrow = android.graphics.Path().apply {
            moveTo(w * 0.18f, h * 0.47f)
            lineTo(w * 0.38f, h * 0.30f)
            lineTo(w * 0.36f, h * 0.48f)
            close()
        }
        canvas.drawPath(arrow, paint)
    }

    private fun createTopBarRedoDrawable(): Drawable = createStrokePathDrawable { canvas, w, h, paint ->
        val cx = w * 0.50f
        val cy = h * 0.50f
        val r = w * 0.28f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, 35f, 250f, false, paint)
        val arrow = android.graphics.Path().apply {
            moveTo(w * 0.82f, h * 0.47f)
            lineTo(w * 0.62f, h * 0.30f)
            lineTo(w * 0.64f, h * 0.48f)
            close()
        }
        canvas.drawPath(arrow, paint)
    }

    private fun createTopBarPasteDrawable(): Drawable = createStrokePathDrawable { canvas, w, h, paint ->
        val left = w * 0.25f
        val top = h * 0.27f
        val right = w * 0.75f
        val bottom = h * 0.82f
        canvas.drawRoundRect(RectF(left, top, right, bottom), w * 0.07f, w * 0.07f, paint)
        canvas.drawRoundRect(RectF(w * 0.38f, h * 0.16f, w * 0.62f, h * 0.34f), w * 0.05f, w * 0.05f, paint)
    }

    private fun createTopBarLockDrawable(): Drawable = createStrokePathDrawable { canvas, w, h, paint ->
        canvas.drawRoundRect(RectF(w * 0.24f, h * 0.42f, w * 0.76f, h * 0.82f), w * 0.07f, w * 0.07f, paint)
        val arc = RectF(w * 0.34f, h * 0.18f, w * 0.66f, h * 0.58f)
        canvas.drawArc(arc, 180f, 180f, false, paint)
        canvas.drawCircle(w * 0.50f, h * 0.60f, w * 0.045f, paint)
    }

    private fun createTopBarDeleteDrawable(
        iconColor: Int = Color.BLACK
    ): Drawable = createStrokePathDrawable(iconColor) { canvas, w, h, paint ->
        canvas.drawRoundRect(RectF(w * 0.28f, h * 0.30f, w * 0.72f, h * 0.82f), w * 0.05f, w * 0.05f, paint)
        canvas.drawLine(w * 0.22f, h * 0.25f, w * 0.78f, h * 0.25f, paint)
        canvas.drawLine(w * 0.40f, h * 0.18f, w * 0.60f, h * 0.18f, paint)
        canvas.drawLine(w * 0.42f, h * 0.40f, w * 0.42f, h * 0.70f, paint)
        canvas.drawLine(w * 0.58f, h * 0.40f, w * 0.58f, h * 0.70f, paint)
    }

    private fun createStrokePathDrawable(
        iconColor: Int = Color.BLACK,
        strokeWidthPx: Float = dpToPx(2).toFloat(),
        drawer: (Canvas, Float, Float, Paint) -> Unit
    ): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = iconColor
                style = Paint.Style.STROKE
                strokeWidth = strokeWidthPx
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.save()
                val iconScale = ((w - dpToPx(1).toFloat()).coerceAtLeast(1f)) / w
                canvas.scale(iconScale, iconScale, w / 2f, h / 2f)
                drawer(canvas, w, h, paint)
                canvas.restore()
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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

    // ================================================================
    // Shared custom magnifier for BOTH selection-handle dragging and
    // long-press + drag character selection.
    // ================================================================
    private var customSelectionMagnifier: Magnifier? = null
    private var customMagnifierTarget: EditText? = null
    private var lastCustomMagnifierTime = 0L
    private val customMagnifierFrameInterval = 16L

    private fun createCustomSelectionMagnifier() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        if (customMagnifierTarget !== editText) {
            try {
                customSelectionMagnifier?.dismiss()
            } catch (_: Exception) {
            }
            customSelectionMagnifier = null
            customMagnifierTarget = editText
        }

        if (customSelectionMagnifier == null) {
            val density = resources.displayMetrics.density
            customSelectionMagnifier = Magnifier.Builder(editText)
                .setSize(
                    (130f * density).toInt(),
                    (50f * density).toInt()
                )
                .setCornerRadius(15f * density)
                .build()
        }
    }

    private fun showCustomSelectionMagnifier(
        rawX: Float,
        rawY: Float,
        force: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val now = System.currentTimeMillis()
        if (!force && now - lastCustomMagnifierTime < customMagnifierFrameInterval) {
            return
        }
        lastCustomMagnifierTime = now

        try {
            createCustomSelectionMagnifier()

            val location = IntArray(2)
            editText.getLocationOnScreen(location)

            val localX = (rawX - location[0]).coerceIn(
                0f,
                editText.width.toFloat()
            )
            val localY = (rawY - location[1]).coerceIn(
                0f,
                editText.height.toFloat()
            )

            // Exactly the same Magnifier configuration and positioning logic
            // used by the custom selection handles.
            customSelectionMagnifier?.show(localX, localY)
        } catch (e: Exception) {
        }
    }

    private fun hideCustomSelectionMagnifier() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            customSelectionMagnifier?.dismiss()
        } catch (e: Exception) {
        }

        lastCustomMagnifierTime = 0L
        customMagnifierTarget = null
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
    private val frameInterval = 16L

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

                showCustomSelectionMagnifier(
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

                    showCustomSelectionMagnifier(
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
                showCustomSelectionMagnifier(
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

                hideCustomSelectionMagnifier()

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

                hideCustomSelectionMagnifier()

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

            if (start == end || start < 0 || end < 0 ||
                start > editText.text.length || end > editText.text.length) {
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

            // Selection handles are allowed only inside the actual editor
            // viewport. Once a handle reaches the title bar, it disappears.
            val editorLocation = IntArray(2)
            scrollView.getLocationOnScreen(editorLocation)
            val editorTop = editorLocation[1]
            val editorBottom = editorTop + scrollView.height

            val leftScreenTop =
                editLocation[1] + currentLayout.getLineBottom(startLine) -
                    halfHandle - editText.scrollY
            val rightScreenTop =
                editLocation[1] + currentLayout.getLineBottom(endLine) -
                    halfHandle - editText.scrollY

            val leftScreenBottom = leftScreenTop + HANDLE_SIZE
            val rightScreenBottom = rightScreenTop + HANDLE_SIZE

            val leftInsideEditor =
                leftScreenTop >= editorTop && leftScreenBottom <= editorBottom
            val rightInsideEditor =
                rightScreenTop >= editorTop && rightScreenBottom <= editorBottom

            leftHandleView?.let { handle ->
                val params = handle.layoutParams as? FrameLayout.LayoutParams
                if (params != null) {
                    params.leftMargin = (startX - halfHandle).toInt()
                    params.topMargin = (startY - halfHandle).toInt()
                    handle.layoutParams = params

                    if (leftInsideEditor) {
                        handle.animate().cancel()
                        handle.visibility = View.VISIBLE
                        handle.alpha = 1f
                    } else {
                        handle.animate().cancel()
                        handle.alpha = 0f
                        handle.visibility = View.GONE
                    }
                }
            }

            rightHandleView?.let { handle ->
                val params = handle.layoutParams as? FrameLayout.LayoutParams
                if (params != null) {
                    val gap = 14
                    params.leftMargin = (endX + gap).toInt()
                    params.topMargin = (endY - halfHandle).toInt()
                    handle.layoutParams = params

                    if (rightInsideEditor) {
                        handle.animate().cancel()
                        handle.visibility = View.VISIBLE
                        handle.alpha = 1f
                    } else {
                        handle.animate().cancel()
                        handle.alpha = 0f
                        handle.visibility = View.GONE
                    }
                }
            }
        } catch (e: Exception) {
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
        if (isSelectionUiSuppressed()) return
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
            }

            // updateHandlePositions() itself decides whether each handle is
            // inside the editor viewport. Do not force VISIBLE here, otherwise
            // a handle near the title bar would reappear.
            updateHandlePositionsImmediate()

        } catch (e: Exception) {
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
        if (isSelectionUiSuppressed()) return
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

    /**
     * Child note editor-এর keyboard/IME hide করে।
     * Focus সরানো হচ্ছে না, যাতে minimize করার সময় cursor/selection state
     * পরবর্তীতে bubble থেকে note খুললে আগের অবস্থায় restore করা যায়।
     */
    private fun hideEditorKeyboard() {
        try {
            if (::editText.isInitialized) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(editText.windowToken, 0)
            }
        } catch (e: Exception) {
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
            if (isSelectionUiSuppressed()) {
                hideSelectionHandles()
                hideFloatingActionBar()
                return@Runnable
            }
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
        // ============================================================
        // MAIN NOTE LIST PAGE
        // Same top-bar/button sizing logic as the child note editor.
        // ============================================================
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor(NOTEPAD_BG_COLOR))
                cornerRadius = dpToPx(5).toFloat()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clipToOutline = true
                elevation = dpToPx(14).toFloat()
                translationZ = dpToPx(2).toFloat()
            }
        }

        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // ------------------------------------------------------------
        // Top Bar: Settings | Floating Notes | + | Minimize | Close
        // Height is exactly the same as the child note top bar.
        // ------------------------------------------------------------
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(35)
            )
            setPadding(dpToPx(3), 0, dpToPx(3), 0)
            setBackgroundColor(Color.parseColor("#F9E79F"))
            setOnTouchListener(TitleBarDragListener())
        }

        val settingsBtn = createTopBarIconButton(
            createTopBarSettingsDrawable(),
            Color.rgb(255, 220, 80)
        ) {
            // Settings screen is not part of the current service yet.
            // The button is kept ready without changing existing behavior.
        }

        val newNoteBtn = createTopBarIconButton(
            createTopBarPlusDrawable(),
            Color.rgb(255, 220, 80)
        ) {
            createNewNote()
        }
        val minimizeBtn = createTopBarIconButton(
            createTopBarMinimizeDrawable(),
            Color.rgb(255, 220, 80)
        ) {
            collapseToBubble()
        }
        val closeBtn = createTopBarIconButton(
            createTopBarCloseDrawable(),
            Color.rgb(255, 220, 80)
        ) {
            // Close the complete floating-notes UI, including its docked bubble.
            stopSelf()
        }

        // Left and right control groups have identical widths so the title is
        // mathematically centered on the complete top bar.
        val leftControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(dpToPx(84), dpToPx(35))
        }
        leftControls.addView(settingsBtn)
        topBar.addView(leftControls)

        val titleText = TextView(this).apply {
            text = NOTEPAD_TITLE
            textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        }
        topBar.addView(titleText)

        val rightControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(dpToPx(84), dpToPx(35))
        }
        rightControls.addView(newNoteBtn)
        rightControls.addView(minimizeBtn)
        rightControls.addView(closeBtn)
        topBar.addView(rightControls)
        contentContainer.addView(topBar)

        // ------------------------------------------------------------
        // Note list: no separate "Note List" bar and no "New Note" bar.
        // ------------------------------------------------------------
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@FloatingBubbleService)
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            clipToPadding = false
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(20)
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val position = parent.getChildAdapterPosition(view)
                    if (position > 0) {
                        outRect.top = dpToPx(6)
                    }
                }
            })
        }

        notesAdapter = NoteAdapter(
            notesList,
            onItemClick = { note ->
                openEditorForNote(note)
            },
            onMoveUp = { note -> moveNote(note.id, -1) },
            onMoveDown = { note -> moveNote(note.id, 1) },
            onLockClick = { note -> toggleNoteLock(note.id) },
            onDeleteClick = { note -> deleteNoteFromList(note.id) }
        )
        recyclerView.adapter = notesAdapter
        contentContainer.addView(recyclerView)

        container.addView(contentContainer)

        // Same resize-handle style, size and zero-corner positioning as the
        // child note editor.
        val resizeHandleView = TextView(this).apply {
            text = "◢"
            textSize = 18f
            setTextColor(Color.parseColor("#F28B82"))
            gravity = Gravity.END or Gravity.BOTTOM
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            background = null

            layoutParams = FrameLayout.LayoutParams(
                dpToPx(18),
                dpToPx(18),
                Gravity.END or Gravity.BOTTOM
            ).apply {
                rightMargin = 0
                bottomMargin = 0
            }

            translationY = dpToPx(4).toFloat()
            setOnTouchListener(ResizeTouchListener())
            bringToFront()
        }
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

        return container
    }

    private fun createTopBarSettingsDrawable(): Drawable =
        createStrokePathDrawable { canvas, w, h, paint ->
            val cx = w * 0.50f
            val cy = h * 0.50f
            val outerR = w * 0.29f
            val innerR = w * 0.11f

            canvas.drawCircle(cx, cy, outerR, paint)
            canvas.drawCircle(cx, cy, innerR, paint)

            // Six short gear teeth.
            for (i in 0 until 6) {
                canvas.save()
                canvas.rotate(i * 60f, cx, cy)
                canvas.drawLine(
                    cx,
                    cy - outerR,
                    cx,
                    cy - w * 0.39f,
                    paint
                )
                canvas.restore()
            }
        }

    private fun createTopBarPlusDrawable(): Drawable =
        createStrokePathDrawable { canvas, w, h, paint ->
            val cx = w * 0.50f
            val cy = h * 0.50f
            val half = w * 0.27f
            canvas.drawLine(cx - half, cy, cx + half, cy, paint)
            canvas.drawLine(cx, cy - half, cx, cy + half, paint)
        }

    private fun createTopBarArrowDrawable(
        up: Boolean,
        iconColor: Int = Color.BLACK
    ): Drawable =
        createStrokePathDrawable(iconColor) { canvas, w, h, paint ->
            val cx = w * 0.50f
            val cy = h * 0.50f
            val half = w * 0.22f
            val tipY = if (up) h * 0.24f else h * 0.76f
            val baseY = if (up) h * 0.66f else h * 0.34f
            val path = android.graphics.Path().apply {
                moveTo(cx, tipY)
                lineTo(cx - half, baseY)
                moveTo(cx, tipY)
                lineTo(cx + half, baseY)
                moveTo(cx, tipY)
                lineTo(cx, if (up) h * 0.84f else h * 0.16f)
            }
            canvas.drawPath(path, paint)
        }

    private fun createTopBarListLockDrawable(
        locked: Boolean,
        iconColor: Int = Color.BLACK
    ): Drawable =
        createStrokePathDrawable(iconColor) { canvas, w, h, paint ->
            val body = RectF(w * 0.25f, h * 0.42f, w * 0.75f, h * 0.82f)
            canvas.drawRoundRect(body, w * 0.06f, w * 0.06f, paint)
            val arc = RectF(w * 0.34f, h * 0.16f, w * 0.66f, h * 0.58f)
            if (locked) {
                canvas.drawArc(arc, 180f, 180f, false, paint)
            } else {
                canvas.drawArc(arc, 205f, 145f, false, paint)
                canvas.drawLine(w * 0.66f, h * 0.38f, w * 0.72f, h * 0.28f, paint)
            }
            canvas.drawCircle(w * 0.50f, h * 0.61f, w * 0.045f, paint)
        }

    private fun formatNoteCreatedDate(note: NoteItem): String {
        val timestamp = if (note.createdAt > 0L) note.createdAt else note.lastEdited
        return try {
            java.text.SimpleDateFormat(
                "dd MMM yyyy, hh:mm a",
                java.util.Locale.getDefault()
            ).format(java.util.Date(timestamp))
        } catch (_: Exception) {
            ""
        }
    }

    private fun moveNote(noteId: Long, direction: Int) {
        val from = notesList.indexOfFirst { it.id == noteId }
        if (from < 0) return
        val to = (from + direction).coerceIn(0, notesList.lastIndex)
        if (from == to) return

        val moved = notesList.removeAt(from)
        notesList.add(to, moved)
        saveNotesToPrefs()
        notesAdapter.updateList(notesList)
        updateBubbleCount()
        recyclerView.post {
            recyclerView.smoothScrollToPosition(to)
        }
    }

    private fun toggleNoteLock(noteId: Long) {
        val index = notesList.indexOfFirst { it.id == noteId }
        if (index < 0) return

        val note = notesList[index]
        notesList[index] = note.copy(isLocked = !note.isLocked)
        saveNotesToPrefs()
        notesAdapter.updateList(notesList)

        val message = if (notesList[index].isLocked) "Note locked" else "Note unlocked"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun deleteNoteFromList(noteId: Long) {
        val index = notesList.indexOfFirst { it.id == noteId }
        if (index < 0) return

        if (currentEditingNoteId == noteId) {
            currentEditingNoteId = null
            restoreEditorStatePending = false
            hideSelectionHandles()
            hideFloatingActionBar()
        }

        notesList.removeAt(index)
        saveNotesToPrefs()
        notesAdapter.updateList(notesList)
        updateBubbleCount()

        Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show()
    }

    private fun createNewNote() {
        val now = System.currentTimeMillis()
        val newNote = NoteItem(
            id = now,
            title = "Untitled Note",
            content = "",
            lastEdited = now,
            createdAt = now,
            isLocked = false
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

                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun openEditorForNote(note: NoteItem) {
        currentEditingNoteId = note.id

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // Rounded child-notepad surface: exactly 5px corner radius, no border.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor(NOTEPAD_BG_COLOR))
                cornerRadius = 5f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clipToOutline = true
                elevation = dpToPx(14).toFloat()
                translationZ = dpToPx(2).toFloat()
            }
        }

        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // No inset/padding around the child note: no fake border/edge.
            setPadding(0, 0, 0, 0)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(35)
            )
            setPadding(dpToPx(3), 0, dpToPx(3), 0)
            setBackgroundColor(Color.parseColor("#F9E79F"))
            setOnTouchListener(TitleBarDragListener())
        }

        val backBtn = createTopBarIconButton(createTopBarBackDrawable(), Color.rgb(255, 220, 80)) {
            // Save the current child note first so an edited title/content is
            // not lost when the Back button returns to the note list.
            saveCurrentNote(note.id)
        }
        topBar.addView(backBtn)

        // Intentionally blank left/center title area.
        val emptyTitleSpace = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(emptyTitleSpace)

        val undoBtn = createTopBarIconButton(createTopBarUndoDrawable(), Color.rgb(255, 220, 80)) { undoEditorChange() }
        val redoBtn = createTopBarIconButton(createTopBarRedoDrawable(), Color.rgb(255, 220, 80)) { redoEditorChange() }
        val pasteBtnTop = createTopBarIconButton(createTopBarPasteDrawable(), Color.rgb(255, 220, 80)) {
            pasteIntoEditor()
        }
        val shareTopBtn = createTopBarIconButton(createTopBarShareDrawable(), Color.rgb(255, 220, 80)) {
            // Top-bar Share: একই সাথে Action Bar-এর Share-এর মতো
            // keyboard hide + selection UI hide + note minimize করবে।
            hideFloatingActionBar()
            hideSelectionHandles()
            hideEditorKeyboard()

            val title = getEditorAutoTitle(editText.text.toString())
            shareLargeText(
                if (title.isEmpty()) editText.text.toString()
                else "$title\n\n${editText.text}"
            )

            // Share chooser খোলার পর child note-কে bubble-এ minimize করা হবে।
            Handler(Looper.getMainLooper()).postDelayed({
                if (isExpanded) {
                    collapseToBubble()
                }
            }, 500)
        }
        val minimizeBtn = createTopBarIconButton(createTopBarMinimizeDrawable(), Color.rgb(255, 220, 80)) {
            collapseToBubble()
        }

        val closeBtn = createTopBarIconButton(createTopBarCloseDrawable(), Color.rgb(255, 220, 80)) {
            closeChildNotePad(note.id)
        }

        topBar.addView(undoBtn)
        topBar.addView(redoBtn)
        topBar.addView(pasteBtnTop)
        topBar.addView(shareTopBtn)
        topBar.addView(minimizeBtn)
        topBar.addView(closeBtn)
        contentContainer.addView(topBar)

        // ============================================================
        // EDITABLE TITLE BAR
        // ============================================================
        // The number is kept in a small fixed TextView, while the title itself
        // is a real EditText. Therefore the user can freely edit the title
        // without accidentally changing the note's serial number.
        var titleWasEditedManually = false

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(20)
            )
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.parseColor("#FFF0B8"))
        }

        val noteNumberText = TextView(this).apply {
            val number = notesList.indexOfFirst { it.id == note.id } + 1
            text = "$number."
            textSize = 12f
            // Serial number + dot are bold.
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#444444"))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(24),
                dpToPx(20)
            )
        }
        titleBar.addView(noteNumberText)

        val initialAutoTitle = getEditorAutoTitle(note.content)
        val initialTitle = if (note.title.isNotBlank() && note.title != "Untitled Note") {
            note.title
        } else {
            initialAutoTitle
        }

        titleInput = EditText(this).apply {
            setText(initialTitle)
            textSize = 12f
            // Title text is bold, including manually edited titles.
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#444444"))
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dpToPx(4), 0, dpToPx(8), 0)
            background = null
            includeFontPadding = false
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isLongClickable = true
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "Title"

            layoutParams = LinearLayout.LayoutParams(
                0,
                dpToPx(20),
                1f
            )

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) { }

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    if (hasFocus()) {
                        titleWasEditedManually = true
                    }
                }

                override fun afterTextChanged(s: Editable?) { }
            })
        }
        titleBar.addView(titleInput)
        contentContainer.addView(titleBar)


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

        editorUndoStack.clear()
        editorRedoStack.clear()
        suppressEditorHistory = false
        isEditorLocked = false
        lastEditorText = note.content
        historyInitializedForCurrentEditor = true

        editText = object : EditText(this) {
            override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                super.onSelectionChanged(selStart, selEnd)

                // Real EditText selection callback. A TextWatcher does not
                // reliably report selection-only changes.
                if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
                    lastNonEmptySelectionStart = minOf(selStart, selEnd)
                    lastNonEmptySelectionEnd = maxOf(selStart, selEnd)
                }

                if (isSelectionUiSuppressed()) {
                    hideSelectionHandles()
                    hideFloatingActionBar()
                    currentSelectedText = ""
                }
            }

            override fun onCreateInputConnection(
                outAttrs: android.view.inputmethod.EditorInfo?
            ): android.view.inputmethod.InputConnection? {
                val base = super.onCreateInputConnection(outAttrs) ?: return null
                return object : android.view.inputmethod.InputConnectionWrapper(base, true) {
                    private fun hideIfDeletingSelection() {
                        handleImeSelectionDeletionIfNeeded()
                    }

                    override fun deleteSurroundingText(
                        beforeLength: Int,
                        afterLength: Int
                    ): Boolean {
                        hideIfDeletingSelection()
                        return super.deleteSurroundingText(beforeLength, afterLength)
                    }

                    override fun deleteSurroundingTextInCodePoints(
                        beforeLength: Int,
                        afterLength: Int
                    ): Boolean {
                        hideIfDeletingSelection()
                        return super.deleteSurroundingTextInCodePoints(
                            beforeLength,
                            afterLength
                        )
                    }

                    override fun commitText(
                        text: CharSequence?,
                        newCursorPosition: Int
                    ): Boolean {
                        // Some keyboards delete a selected range by committing
                        // an empty string rather than calling deleteSurroundingText().
                        if (text.isNullOrEmpty()) {
                            hideIfDeletingSelection()
                        }
                        return super.commitText(text, newCursorPosition)
                    }

                    override fun setComposingText(
                        text: CharSequence?,
                        newCursorPosition: Int
                    ): Boolean {
                        if (text.isNullOrEmpty()) {
                            hideIfDeletingSelection()
                        }
                        return super.setComposingText(text, newCursorPosition)
                    }

                    override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                            (event.keyCode == android.view.KeyEvent.KEYCODE_DEL ||
                             event.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL)
                        ) {
                            hideIfDeletingSelection()
                        }
                        return super.sendKeyEvent(event)
                    }
                }
            }
        }.apply {
            setText(note.content)
            hint = "Write your note here..."
            textSize = 15f
            // Normal body text: remove any default/bold typeface.
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
            gravity = Gravity.TOP or Gravity.START
            setPadding(18, 18, 18, 18)
            background = null

            setLineSpacing(0f, 1.05f)
            setHorizontallyScrolling(false)
            maxLines = Int.MAX_VALUE
            minHeight = 400

            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI

            // Editable EditText already supports cursor/selection.
            // Keeping text selectable here can make the first tap select the whole text.
            isHapticFeedbackEnabled = false
            isLongClickable = false
            customInsertionActionModeCallback = null
            customSelectionActionModeCallback = null

            isClickable = true
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true

            setOnSelectionChangedListener { selStart, selEnd ->
                if (isSelectionUiSuppressed()) {
                    hideSelectionHandles()
                    hideFloatingActionBar()
                    currentSelectedText = ""
                    return@setOnSelectionChangedListener
                }

                // Android IME keyboards can collapse a selection immediately
                // before deleting it. Preserve the last real selection so the
                // deletion can still be recognized by the TextWatcher.
                if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
                    lastNonEmptySelectionStart = minOf(selStart, selEnd)
                    lastNonEmptySelectionEnd = maxOf(selStart, selEnd)
                }

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

            // Keep the title automatically synchronized with the first line of
            // the note until the user manually edits the title field.
            addTextChangedListener(object : TextWatcher {
                private var internalChange = false

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    if (internalChange || titleWasEditedManually) return

                    val autoTitle = getEditorAutoTitle(s?.toString().orEmpty())
                    val number = notesList.indexOfFirst { it.id == note.id } + 1
                    val newTitle = autoTitle

                    if (titleInput.text.toString() != newTitle) {
                        internalChange = true
                        titleInput.setText(newTitle)
                        titleInput.setSelection(titleInput.text.length)
                        internalChange = false
                    }
                }

                override fun afterTextChanged(s: Editable?) {
                }
            })

            // ============================================================
            // ROBUST EDITOR HISTORY
            // ============================================================
            // Every real user edit stores the COMPLETE state that existed
            // immediately before that edit. Undo/Redo themselves use
            // suppressEditorHistory, so they never create recursive entries.
            addTextChangedListener(object : TextWatcher {
                // True only when the current edit operation is deleting an
                // already-selected range (for example, pressing the keyboard
                // Backspace/Cross key while text is selected).
                // This lets us close the custom selection UI even when the
                // deletion is performed by the IME rather than our own Cut button.
                private var deletingActiveSelection = false

                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {
                    val liveStart = this@FloatingBubbleService.editText.selectionStart
                    val liveEnd = this@FloatingBubbleService.editText.selectionEnd

                    val liveSelectionIsNonEmpty =
                        liveStart >= 0 &&
                        liveEnd >= 0 &&
                        liveStart != liveEnd

                    val rememberedSelectionIsNonEmpty =
                        lastNonEmptySelectionStart >= 0 &&
                        lastNonEmptySelectionEnd > lastNonEmptySelectionStart &&
                        lastNonEmptySelectionEnd <=
                            (s?.length ?: this@FloatingBubbleService.editText.length())

                    // Detect deletion from the keyboard in both situations:
                    // the selection is still present, or the IME has already
                    // collapsed it before TextWatcher.beforeTextChanged().
                    deletingActiveSelection =
                        count > 0 &&
                        after == 0 &&
                        (liveSelectionIsNonEmpty || rememberedSelectionIsNonEmpty) &&
                        (isActionBarVisible || areHandlesVisible || liveSelectionIsNonEmpty)

                    if (suppressEditorHistory) return

                    val snapshot = captureEditorHistoryState(
                        textOverride = s?.toString() ?: ""
                    )

                    // Do not add duplicate consecutive snapshots.
                    if (editorUndoStack.isEmpty() || editorUndoStack.peekLast() != snapshot) {
                        editorUndoStack.addLast(snapshot)
                    }

                    while (editorUndoStack.size > 100) {
                        editorUndoStack.removeFirst()
                    }

                    // Any new user edit after an Undo starts a new branch.
                    editorRedoStack.clear()
                    historyInitializedForCurrentEditor = true
                }

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    if (!suppressEditorHistory) {
                        lastEditorText = s?.toString() ?: ""
                    }
                }

                override fun afterTextChanged(s: Editable?) {
                    if (deletingActiveSelection) {
                        // The IME has already deleted the selected text.
                        // Suppress all delayed callbacks that could recreate
                        // the custom handles/action bar.
                        hideSelectionUiAfterImeDeletion()
                    }
                    deletingActiveSelection = false
                }
            })

setOnTouchListener(object : View.OnTouchListener {
    /*
     * FINAL TOUCH/SELECTION FIX v5
     *
     * 1) Double tap is detected here instead of relying on EditText's native
     *    double-tap selection, so the custom selection is not overwritten.
     * 2) Native long-click selection is disabled; our own long-press handler
     *    starts selection and keeps the parent ScrollView from taking MOVE
     *    events after the long press.
     * 3) Long-press + drag uses one fixed character offset as the anchor and
     *    changes only the other endpoint. Therefore the range grows/shrinks
     *    character-by-character rather than word-by-word.
     * 4) Before long-press, MOVE remains native so normal scrolling works.
     */

    private val touchHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var touchMoved = false
    private var longPressTriggered = false
    private var secondTapCandidate = false
    private var selectionAtDown = false
    private var selectionAnchor = -1

    private val touchSlopPx = ViewConfiguration
        .get(this@FloatingBubbleService)
        .scaledTouchSlop
        .toFloat()

    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapDistance = dpToPx(48).toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { touchHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun offsetAt(x: Float, y: Float): Int {
        return try {
            val layout = this@apply.layout ?: return this@apply.selectionStart.coerceAtLeast(0)
            val textLength = this@apply.length()
            if (textLength == 0) return 0

            val vertical = (this@apply.scrollY + y.toInt())
                .coerceIn(0, layout.height.coerceAtLeast(1) - 1)
            val line = layout.getLineForVertical(vertical)
            val horizontal = x + this@apply.scrollX
            layout.getOffsetForHorizontal(line, horizontal)
                .coerceIn(0, textLength)
        } catch (_: Exception) {
            0
        }
    }

    private fun updateCustomSelectionUi() {
        if (isSelectionUiSuppressed()) {
            hideSelectionHandles()
            hideFloatingActionBar()
            currentSelectedText = ""
            return
        }
        if (!this@apply.hasSelection()) {
            currentSelectedText = ""
            hideSelectionHandles()
            hideFloatingActionBar()
            return
        }

        val start = minOf(this@apply.selectionStart, this@apply.selectionEnd)
        val end = maxOf(this@apply.selectionStart, this@apply.selectionEnd)
        if (start < 0 || end > this@apply.length() || start >= end) return

        currentSelectedText = this@apply.text.substring(start, end)
        showSelectionHandles()
        updateHandlePositionsImmediate()
        showFloatingActionBar(currentSelectedText)
    }

    private fun beginCustomLongPress(x: Float, y: Float) {
        if (this@apply.length() == 0) return

        longPressTriggered = true
        secondTapCandidate = false
        selectionAnchor = offsetAt(x, y)

        // Start from the character under the finger. The initial visual
        // selection is the word, but as soon as the finger moves the word is
        // replaced by an exact character-range anchored at selectionAnchor.
        selectWordAtPosition(this@apply, x, y, true)
        updateCustomSelectionUi()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isEditorLocked) {
                    return true
                }
                cancelPendingLongPress()

                touchStartX = event.x
                touchStartY = event.y
                touchMoved = false
                longPressTriggered = false
                selectionAtDown = this@apply.hasSelection()

                val now = System.currentTimeMillis()
                val withinTime = now - lastTapTime <= doubleTapTimeout
                val dxTap = event.x - lastTapX
                val dyTap = event.y - lastTapY
                val withinDistance =
                    (dxTap * dxTap + dyTap * dyTap) <=
                        (doubleTapDistance * doubleTapDistance)

                secondTapCandidate = withinTime && withinDistance
                selectionAnchor = -1

                this@apply.requestFocus()
                this@apply.isCursorVisible = true
                v.parent?.requestDisallowInterceptTouchEvent(false)

                val downX = event.x
                val downY = event.y
                longPressRunnable = Runnable {
                    if (!touchMoved) {
                        beginCustomLongPress(downX, downY)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                touchHandler.postDelayed(longPressRunnable!!, longPressTimeout)

                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                val distance = sqrt(
                    (dx.toDouble() * dx.toDouble()) +
                    (dy.toDouble() * dy.toDouble())
                ).toFloat()

                if (distance > touchSlopPx) {
                    touchMoved = true
                }

                if (longPressTriggered) {
                    // After long press, consume the drag so EditText's native
                    // word-selection machinery cannot replace our character
                    // range, while the parent ScrollView stays locked out.
                    cancelPendingLongPress()
                    v.parent?.requestDisallowInterceptTouchEvent(true)

                    if (selectionAnchor < 0) {
                        selectionAnchor = offsetAt(touchStartX, touchStartY)
                    }

                    if (distance > touchSlopPx) {
                        // Use the EXACT same custom Magnifier used by the
                        // selection handles. The focal point follows the
                        // finger while the selection expands character-by-character.
                        showCustomSelectionMagnifier(
                            event.rawX,
                            event.rawY
                        )

                        val movingOffset = offsetAt(event.x, event.y)
                        val a = minOf(selectionAnchor, movingOffset)
                        val b = maxOf(selectionAnchor, movingOffset)

                        if (a != b) {
                            this@apply.setSelection(a, b)
                            updateCustomSelectionUi()
                        } else {
                            this@apply.setSelection(a)
                            hideSelectionHandles()
                            hideFloatingActionBar()
                            currentSelectedText = ""
                        }
                    }

                    return true
                }

                // Before long press, do not consume MOVE. This preserves the
                // normal ScrollView scrolling path and native cursor behaviour.
                if (distance > touchSlopPx) {
                    cancelPendingLongPress()
                    secondTapCandidate = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }

                return false
            }

            MotionEvent.ACTION_UP -> {
                cancelPendingLongPress()
                v.parent?.requestDisallowInterceptTouchEvent(false)

                val wasLongPress = longPressTriggered
                val wasSecondTap = secondTapCandidate
                val wasMoved = touchMoved

                if (wasLongPress) {
                    hideCustomSelectionMagnifier()

                    // Keep the final selection exactly where the drag ended.
                    if (this@apply.hasSelection()) {
                        updateCustomSelectionUi()
                    }

                    lastTapTime = 0L
                    lastTapX = event.x
                    lastTapY = event.y
                    longPressTriggered = false
                    secondTapCandidate = false
                    selectionAnchor = -1
                    touchMoved = false
                    return true
                }

                if (!wasMoved && wasSecondTap) {
                    // IMPORTANT: post the selection so EditText's own ACTION_UP
                    // processing finishes first. Otherwise native EditText can
                    // immediately replace our custom word selection.
                    val doubleX = event.x
                    val doubleY = event.y
                    touchHandler.post {
                        try {
                            selectWordAtPosition(this@apply, doubleX, doubleY, true)
                            updateCustomSelectionUi()
                        } catch (ex: Exception) {
                        }
                    }

                    lastTapTime = 0L
                } else if (!wasMoved) {
                    // A normal single tap should only collapse an existing
                    // selection; otherwise native EditText places the cursor.
                    if (selectionAtDown && this@apply.hasSelection()) {
                        val offset = offsetAt(event.x, event.y)
                        this@apply.setSelection(offset)
                        hideSelectionHandles()
                        hideFloatingActionBar()
                        currentSelectedText = ""
                    }

                    this@apply.requestFocus()
                    this@apply.isCursorVisible = true
                    this@apply.post {
                        try {
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                                as InputMethodManager
                            imm.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                        } catch (ex: Exception) {
                        }
                    }

                    lastTapTime = System.currentTimeMillis()
                    lastTapX = event.x
                    lastTapY = event.y
                }

                longPressTriggered = false
                secondTapCandidate = false
                selectionAnchor = -1
                touchMoved = false
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                hideCustomSelectionMagnifier()
                v.parent?.requestDisallowInterceptTouchEvent(false)
                longPressTriggered = false
                secondTapCandidate = false
                selectionAnchor = -1
                touchMoved = false
                return false
            }
        }

        return false
    }
})
        }

        scrollView.addView(editText)
        contentContainer.addView(scrollView)

        // A note locked from the main list opens read-only. Unlocking it from
        // the list restores normal editing on the next open.
        isEditorLocked = note.isLocked
        if (note.isLocked) {
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            editText.isCursorVisible = false
            editText.isLongClickable = false
            titleInput.isEnabled = false
        } else {
            editText.isFocusable = true
            editText.isFocusableInTouchMode = true
            editText.isCursorVisible = true
            editText.isLongClickable = false
            titleInput.isEnabled = true
        }

        container.addView(contentContainer)

        val resizeHandleView = TextView(this).apply {
    text = "◢"
    textSize = 18f
    setTextColor(Color.parseColor("#F28B82"))
    gravity = Gravity.END or Gravity.BOTTOM
    includeFontPadding = false
    setPadding(0, 0, 0, 0)
    background = null

    layoutParams = FrameLayout.LayoutParams(
        dpToPx(18),
        dpToPx(18),
        Gravity.END or Gravity.BOTTOM
    ).apply {
        rightMargin = 0
        bottomMargin = 0
    }

    // ✅ Resize icon আরও 5px নিচে নামবে
    translationY = dpToPx(4).toFloat()

    setOnTouchListener(ResizeTouchListener())
    bringToFront()
}
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

        // Restore the exact cursor/selection and visible scroll position only when
        // this editor came back from a minimized child-note bubble.
        if (restoreEditorStatePending) {
            val restoreStart = savedEditorSelectionStart
            val restoreEnd = savedEditorSelectionEnd
            val restoreY = savedEditorScrollY
            val restoreX = savedEditorScrollX
            val restoreEditY = savedEditorEditTextScrollY
            val restoreEditX = savedEditorEditTextScrollX

            // IMPORTANT: Do not requestFocus() while restoring a minimized child
            // note. Android EditText may automatically scroll the cursor into
            // view when focus/selection is restored. That is the source of the
            // unwanted jump to the cursor.
            //
            // The viewport saved at minimize is authoritative. We restore the
            // selection while the editor is not focused, then restore BOTH the
            // outer ScrollView and the EditText's own scroll offsets.
            editText.post {
                try {
                    val len = editText.length()
                    val start = restoreStart.coerceIn(0, len)
                    val end = restoreEnd.coerceIn(0, len)

                    editText.isCursorVisible = true
                    editText.setSelection(start, end)

                    fun restoreExactViewport() {
                        try {
                            // First restore the EditText's internal viewport.
                            editText.scrollTo(restoreEditX, restoreEditY)
                            // Then restore the outer viewport that the user saw.
                            scrollView.scrollTo(restoreX, restoreY)
                        } catch (_: Exception) { }
                    }

                    // Restore before the first visible stable frame.
                    restoreExactViewport()

                    scrollView.post {
                        restoreExactViewport()

                        editText.post {
                            restoreExactViewport()

                            if (start != end) {
                                currentSelectedText = editText.text.substring(start, end)
                                showSelectionHandles()
                                updateHandlePositionsImmediate()
                                showFloatingActionBar(currentSelectedText)
                            } else {
                                hideSelectionHandles()
                                hideFloatingActionBar()
                            }

                            // A final animation-frame restore catches any layout
                            // pass that might otherwise move the viewport. No
                            // smooth-scroll or cursor-following operation is used.
                            editText.postOnAnimation {
                                restoreExactViewport()
                                restoreEditorStatePending = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    restoreEditorStatePending = false
                }
            }
        } else {
            // Do NOT force-open the keyboard on a normal note open.
            // The keyboard will appear naturally when the user touches the editor.
            editText.post {
                // New/open-from-list note: always start at the very top.
                // Do not place the cursor at the end because EditText may then
                // auto-scroll the ScrollView to the bottom.
                editText.requestFocus()
                editText.setSelection(0, 0)
                scrollView.post {
                    scrollView.scrollTo(0, 0)
                }
            }
        }
    }

    private fun EditText.hasSelection(): Boolean {
        return selectionStart != selectionEnd
    }

    private fun getEditorAutoTitle(content: String): String {
        return content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(80)
            ?: ""
    }

    private fun captureEditorHistoryState(
        textOverride: String? = null
    ): EditorHistoryState {
        val text = textOverride ?: if (::editText.isInitialized) editText.text.toString() else ""
        val selectionStart = if (::editText.isInitialized) editText.selectionStart.coerceAtLeast(0) else 0
        val selectionEnd = if (::editText.isInitialized) editText.selectionEnd.coerceAtLeast(0) else 0
        val outerY = if (::scrollView.isInitialized) scrollView.scrollY.coerceAtLeast(0) else 0
        val outerX = if (::scrollView.isInitialized) scrollView.scrollX.coerceAtLeast(0) else 0
        val innerY = if (::editText.isInitialized) editText.scrollY.coerceAtLeast(0) else 0
        val innerX = if (::editText.isInitialized) editText.scrollX.coerceAtLeast(0) else 0

        return EditorHistoryState(
            text = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            scrollY = outerY,
            scrollX = outerX,
            editTextScrollY = innerY,
            editTextScrollX = innerX
        )
    }

    private fun restoreEditorHistoryState(state: EditorHistoryState) {
        if (!::editText.isInitialized) return

        val safeStart = state.selectionStart.coerceIn(0, state.text.length)
        val safeEnd = state.selectionEnd.coerceIn(0, state.text.length)

        suppressEditorHistory = true
        try {
            editText.setText(state.text)
            editText.setSelection(safeStart, safeEnd)
            lastEditorText = state.text
        } finally {
            suppressEditorHistory = false
        }

        fun restoreViewport() {
            try {
                editText.scrollTo(
                    state.editTextScrollX.coerceAtLeast(0),
                    state.editTextScrollY.coerceAtLeast(0)
                )
                if (::scrollView.isInitialized) {
                    scrollView.scrollTo(
                        state.scrollX.coerceAtLeast(0),
                        state.scrollY.coerceAtLeast(0)
                    )
                }
            } catch (_: Exception) { }
        }

        // setText()/setSelection() may cause Android to reveal the cursor.
        // Re-apply the saved viewport over several layout passes so Undo/Redo
        // never jumps to the bottom or to the cursor.
        restoreViewport()
        editText.post {
            restoreViewport()
            scrollView.post {
                restoreViewport()
                editText.postOnAnimation {
                    restoreViewport()
                    if (editText.hasSelection() && !isScrolling) {
                        updateHandlePositionsSafe()
                    }
                }
            }
        }
    }

    private fun undoEditorChange() {
        if (!::editText.isInitialized || editorUndoStack.isEmpty() || isEditorLocked) return

        try {
            // Current state becomes the Redo target.
            val current = captureEditorHistoryState()
            val previous = editorUndoStack.removeLast()
            editorRedoStack.addLast(current)

            while (editorRedoStack.size > 100) {
                editorRedoStack.removeFirst()
            }

            restoreEditorHistoryState(previous)

            if (previous.text.isEmpty()) {
                titleInput.setText("")
            }
        } catch (e: Exception) {
        }
    }

    private fun redoEditorChange() {
        if (!::editText.isInitialized || editorRedoStack.isEmpty() || isEditorLocked) return

        try {
            // Current state becomes the Undo target.
            val current = captureEditorHistoryState()
            val next = editorRedoStack.removeLast()
            editorUndoStack.addLast(current)

            while (editorUndoStack.size > 100) {
                editorUndoStack.removeFirst()
            }

            restoreEditorHistoryState(next)
        } catch (e: Exception) {
        }
    }

    private fun pasteIntoEditor() {
        if (!::editText.isInitialized || isEditorLocked) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount <= 0) return
        val pasted = clip.getItemAt(0).coerceToText(this).toString()
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(0)
        val a = minOf(start, end)
        val b = maxOf(start, end)
        editText.text.replace(a, b, pasted)
        editText.setSelection((a + pasted.length).coerceAtMost(editText.length()))
    }

    private fun toggleEditorLock() {
        if (!::editText.isInitialized) return
        isEditorLocked = !isEditorLocked
        editText.isFocusable = !isEditorLocked
        editText.isFocusableInTouchMode = !isEditorLocked
        editText.isCursorVisible = !isEditorLocked
        editText.isLongClickable = !isEditorLocked
        if (isEditorLocked) {
            editText.clearFocus()
            hideSelectionHandles()
            hideFloatingActionBar()
        } else {
            editText.requestFocus()
        }
    }

    private fun closeChildNotePad(noteId: Long) {
        if (currentEditingNoteId != noteId || noteView == null) return

        try {
            // Save edits before removing the child editor from the screen.
            val index = notesList.indexOfFirst { it.id == noteId }
            if (index >= 0 && ::editText.isInitialized) {
                val rawTitle = if (::titleInput.isInitialized) titleInput.text.toString().trim() else ""
                val contentText = editText.text.toString()
                val finalTitle = rawTitle.ifEmpty {
                    getEditorAutoTitle(contentText).ifEmpty { "Untitled Note" }
                }
                notesList[index] = notesList[index].copy(
                    title = finalTitle,
                    content = contentText,
                    lastEdited = System.currentTimeMillis()
                )
                saveNotesToPrefs()
                notesAdapter.updateList(notesList)
                updateBubbleCount()
            }

            hideSelectionHandles()
            hideFloatingActionBar()
            saveNotepadSizeAndPosition(
                currentNotepadWidth,
                currentNotepadHeight,
                (noteView?.layoutParams as? WindowManager.LayoutParams)?.x ?: notepadPosX,
                (noteView?.layoutParams as? WindowManager.LayoutParams)?.y ?: notepadPosY
            )

            noteView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) { }
            }
            noteView = null
            isExpanded = false
            currentEditingNoteId = null
            restoreEditorStatePending = false
            resetHandleReferences()

            // Close means the child note AND its floating bubble both disappear.
            // Do not recreate the bubble here. Stopping the service also removes
            // any remaining overlay immediately through onDestroy().
            deleteBubble()
        } catch (e: Exception) {
        }
    }

    private fun deleteCurrentEditorNote(noteId: Long) {
        val index = notesList.indexOfFirst { it.id == noteId }
        if (index < 0) return
        notesList.removeAt(index)
        saveNotesToPrefs()
        updateBubbleCount()
        currentEditingNoteId = null
        restoreEditorStatePending = false
        hideSelectionHandles()
        hideFloatingActionBar()
        showNoteList()
    }

    private fun saveCurrentNote(noteId: Long) {
        val index = notesList.indexOfFirst { it.id == noteId }
        if (index != -1) {
            val rawTitle = if (::titleInput.isInitialized) {
                titleInput.text.toString().trim()
            } else {
                ""
            }

            val contentText = editText.text.toString()
            val finalTitle = rawTitle.ifEmpty {
                getEditorAutoTitle(contentText).ifEmpty { "Untitled Note" }
            }

            val updatedNote = notesList[index].copy(
                title = finalTitle,
                content = contentText,
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
        currentEditingNoteId = null
        restoreEditorStatePending = false
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

    private fun showDeleteNoteConfirmation(note: NoteItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Note")
            .setMessage("Are you sure you want to delete this note?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                deleteNoteFromList(note.id)
            }
            .show()
    }

    inner class NoteAdapter(
        private var notes: List<NoteItem>,
        private val onItemClick: (NoteItem) -> Unit,
        private val onMoveUp: (NoteItem) -> Unit,
        private val onMoveDown: (NoteItem) -> Unit,
        private val onLockClick: (NoteItem) -> Unit,
        private val onDeleteClick: (NoteItem) -> Unit
    ) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

        fun updateList(newNotes: List<NoteItem>) {
            notes = newNotes.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            // Main note-list item: 55px high, with the text area protected
            // from being squeezed by the four right-side control buttons.
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(55)
                )
                setPadding(dpToPx(7), dpToPx(3), dpToPx(3), dpToPx(3))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#FFFDF0"))
                    // Lighter black border as requested.
                    setStroke(dpToPx(1), Color.parseColor("#AAAAAA"))
                    cornerRadius = dpToPx(5).toFloat()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dpToPx(3).toFloat()
                }
                isClickable = true
                isFocusable = true
            }

            // Left side: title on top, created date below.
            val textContainer = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setPadding(0, 0, dpToPx(5), 0)
            }

            val titleView = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#222222"))
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            val dateView = TextView(parent.context).apply {
                textSize = 9.5f
                setTextColor(Color.parseColor("#8A8A8A"))
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            textContainer.addView(titleView)
            textContainer.addView(dateView)

            // Right side: two vertical columns.
            // Left column = Delete / Lock.
            // Right column = Up / Down.
            // The controls have no background, border, padding or elevation:
            // only the custom icon is visible. Each column is 25px wide so the
            // icon remains visually separated from the text area.
            val controls = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(50),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val deleteLockColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(25), ViewGroup.LayoutParams.MATCH_PARENT)
            }

            val sortColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(25), ViewGroup.LayoutParams.MATCH_PARENT)
            }

            fun smallListButton(icon: Drawable): ImageButton =
                ImageButton(parent.context).apply {
                    // Icon size increased by 2px: 15px -> 17px.
                    layoutParams = LinearLayout.LayoutParams(dpToPx(17), dpToPx(17)).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageDrawable(icon)
                    background = null
                    setPadding(0, 0, 0, 0)
                    minimumWidth = 0
                    minimumHeight = 0
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    elevation = 0f
                    stateListAnimator = null
                    isFocusable = true
                    isClickable = true
                }

            val deleteBtn = smallListButton(
                createTopBarDeleteDrawable(Color.rgb(220, 40, 40))
            )

            // State-dependent color/drawing is refreshed in bind().
            val lockBtn = smallListButton(
                createTopBarListLockDrawable(false, Color.rgb(255, 193, 7))
            )

            val upBtn = smallListButton(
                createTopBarArrowDrawable(true, Color.rgb(125, 125, 125))
            )

            val downBtn = smallListButton(
                createTopBarArrowDrawable(false, Color.rgb(125, 125, 125))
            )

            // Exact requested arrangement:
            // Delete
            //   16px vertical gap
            // Lock
            //
            // Up
            //   16px vertical gap
            // Down
            deleteLockColumn.addView(deleteBtn)
            deleteLockColumn.addView(View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(1, dpToPx(16))
            })
            deleteLockColumn.addView(lockBtn)
            sortColumn.addView(upBtn)
            sortColumn.addView(View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(1, dpToPx(16))
            })
            sortColumn.addView(downBtn)

            controls.addView(deleteLockColumn)
            controls.addView(sortColumn)

            card.addView(textContainer)
            card.addView(controls)

            // When the main note-list window becomes too narrow, hide ONLY the
            // right-side controls. The title/date area keeps the remaining
            // width and therefore stays visible instead of being squeezed away.
            card.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                val availableWidth = right - left
                val hideControlsBelow = dpToPx(150)
                val shouldHide = availableWidth < hideControlsBelow
                val newVisibility = if (shouldHide) View.GONE else View.VISIBLE
                if (controls.visibility != newVisibility) {
                    controls.visibility = newVisibility
                    textContainer.requestLayout()
                }
            }

            return ViewHolder(
                card,
                titleView,
                dateView,
                lockBtn,
                deleteBtn,
                upBtn,
                downBtn
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(notes[position], position)
        }

        override fun getItemCount(): Int = notes.size

        inner class ViewHolder(
            itemView: View,
            private val titleView: TextView,
            private val dateView: TextView,
            private val lockBtn: ImageButton,
            private val deleteBtn: ImageButton,
            private val upBtn: ImageButton,
            private val downBtn: ImageButton
        ) : RecyclerView.ViewHolder(itemView) {

            fun bind(note: NoteItem, position: Int) {
                titleView.text = note.title.ifEmpty { "Untitled Note" }
                dateView.text = formatNoteCreatedDate(note)

                lockBtn.setImageDrawable(
                    createTopBarListLockDrawable(
                        note.isLocked,
                        if (note.isLocked) Color.rgb(46, 173, 91) else Color.rgb(255, 193, 7)
                    )
                )

                itemView.setOnClickListener { onItemClick(note) }
                deleteBtn.setOnClickListener { showDeleteNoteConfirmation(note) }
                lockBtn.setOnClickListener { onLockClick(note) }
                upBtn.setOnClickListener { onMoveUp(note) }
                downBtn.setOnClickListener { onMoveDown(note) }

                upBtn.isEnabled = position > 0
                downBtn.isEnabled = position < notes.lastIndex
                upBtn.alpha = if (upBtn.isEnabled) 1f else 0.35f
                downBtn.alpha = if (downBtn.isEnabled) 1f else 0.35f

                // Button touches must not also open the note.
                deleteBtn.setOnTouchListener { _, _ -> false }
                lockBtn.setOnTouchListener { _, _ -> false }
                upBtn.setOnTouchListener { _, _ -> false }
                downBtn.setOnTouchListener { _, _ -> false }
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
                        val params = noteView?.layoutParams as? WindowManager.LayoutParams

                        // Maximum size is the full current display. This also lets the
                        // resize handle reach the complete screen area instead of the
                        // previous fixed 650 × 850 limit.
                        val screenWidth = resources.displayMetrics.widthPixels
                        val screenHeight = resources.displayMetrics.heightPixels

                        val availableWidth = (screenWidth - (params?.x ?: 0)).coerceAtLeast(NOTEPAD_MIN_WIDTH)
                        val availableHeight = (screenHeight - (params?.y ?: 0)).coerceAtLeast(NOTEPAD_MIN_HEIGHT)

                        val newWidth = (resizeStartWidth + dx)
                            .coerceIn(NOTEPAD_MIN_WIDTH, availableWidth.coerceAtMost(NOTEPAD_MAX_WIDTH))
                        val newHeight = (resizeStartHeight + dy)
                            .coerceIn(NOTEPAD_MIN_HEIGHT, availableHeight.coerceAtMost(NOTEPAD_MAX_HEIGHT))

                        if (newWidth != currentNotepadWidth || newHeight != currentNotepadHeight) {
                            currentNotepadWidth = newWidth
                            currentNotepadHeight = newHeight

                            params?.let {
                                it.width = currentNotepadWidth
                                it.height = currentNotepadHeight
                                windowManager.updateViewLayout(noteView, it)
                            }
                        }
                        return true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    isResizing = false
                    val params = noteView?.layoutParams as? WindowManager.LayoutParams
                    if (params != null && System.currentTimeMillis() - resizeTouchTime > 100) {
                        saveNotepadSizeAndPosition(
                            currentNotepadWidth,
                            currentNotepadHeight,
                            params.x,
                            params.y
                        )
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isResizing = false
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
    }

    override fun onBind(intent: Intent?) = null
}
