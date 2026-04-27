package com.dip83287.floatingbubble

import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

class FloatingBubbleService : Service() {

    // ==================== CONFIGURATION ====================
    private val BUBBLE_COLOR = "#2196F3"
    private val BUBBLE_ICON = "📝"
    private val BUBBLE_SIZE = 80
    private val NOTEPAD_BG_COLOR = "#FFF8DC"
    private val NOTEPAD_TITLE = "Floating Notes"
    private val NOTEPAD_MIN_WIDTH = 300
    private val NOTEPAD_MIN_HEIGHT = 400
    private val NOTEPAD_MAX_WIDTH = 600
    private val NOTEPAD_MAX_HEIGHT = 800
    private val ANIMATION_DURATION = 300L
    private val STORAGE_NAME = "secure_prefs"
    private val KEY_MASTER_PASSWORD = "master_password"
    private val KEY_PASSWORD_SET = "password_set"
    private val KEY_SECURITY_QUESTION = "security_question"
    private val KEY_SECURITY_ANSWER_HASH = "security_answer_hash"
    private val KEY_DARK_MODE = "dark_mode"
    private val KEY_LANGUAGE = "language"
    private val KEY_TEXT_SIZE = "text_size"
    private val KEY_OPACITY = "opacity"
    private val KEY_SCROLL_SPEED = "scroll_speed"
    private val KEY_NOTES = "notes"
    private val KEY_LAST_BACKUP = "last_backup"

    // ==================== VARIABLES ====================
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var noteView: View? = null
    private var isExpanded = false
    private var currentWidth = NOTEPAD_MIN_WIDTH
    private var currentHeight = NOTEPAD_MIN_HEIGHT
    private var isResizing = false
    private var resizeStartX = 0
    private var resizeStartY = 0
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private lateinit var prefs: SharedPreferences
    private lateinit var notesList: MutableList<Note>
    private lateinit var notesAdapter: ArrayAdapter<String>
    private lateinit var listView: ListView
    private lateinit var editText: EditText
    private lateinit var searchEditText: EditText
    private var textSize = 14f
    private var opacity = 0.9f
    private var scrollSpeed = 1.0f

    data class Note(
        var id: String = System.currentTimeMillis().toString(),
        var title: String = "",
        var content: String = "",
        var isLocked: Boolean = false,
        var lastEdited: Long = System.currentTimeMillis()
    )

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE)
        loadNotes()
        loadSettings()
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

    // ==================== BUBBLE UI ====================
    private fun createBubble() {
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
        }
        bubbleLayout.addView(iconView)
        bubbleView = bubbleLayout

        val params = WindowManager.LayoutParams(
            BUBBLE_SIZE, BUBBLE_SIZE,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        var isDragging = false
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(bubbleView!!, params)
                    isDragging = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && abs(event.rawX - touchX) < 10 && abs(event.rawY - touchY) < 10) {
                        expandToNotePad()
                    }
                    true
                }
                else -> false
            }
        }
        bubbleView?.setOnLongClickListener {
            Toast.makeText(this, "Bubble closed", Toast.LENGTH_SHORT).show()
            stopSelf()
            true
        }
        windowManager.addView(bubbleView, params)
    }

    // ==================== EXPAND / COLLAPSE ====================
    private fun expandToNotePad() {
        if (isExpanded) return
        bubbleView?.animate()?.scaleX(0.5f)?.scaleY(0.5f)?.alpha(0f)?.setDuration(ANIMATION_DURATION)
            ?.withEndAction {
                bubbleView?.let { windowManager.removeView(it) }
                bubbleView = null
                showNotePad()
            }?.start()
    }

    private fun collapseToBubble() {
        if (!isExpanded) return
        noteView?.animate()?.alpha(0f)?.scaleX(0.5f)?.scaleY(0.5f)?.setDuration(ANIMATION_DURATION)
            ?.withEndAction {
                noteView?.let { windowManager.removeView(it) }
                noteView = null
                isExpanded = false
                createBubble()
                bubbleView?.alpha = 0f
                bubbleView?.scaleX = 0.5f
                bubbleView?.scaleY = 0.5f
                bubbleView?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(ANIMATION_DURATION)?.start()
            }?.start()
    }

    // ==================== NOTE PAD UI ====================
    private fun showNotePad() {
        isExpanded = true
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(NOTEPAD_BG_COLOR))
            setPadding(24, 24, 24, 24)
            if (Build.VERSION.SDK_INT >= 23) elevation = 24f
        }

        // Title bar
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val titleView = TextView(this).apply {
            text = NOTEPAD_TITLE
            textSize = 18f
            setTextColor(Color.BLACK)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(titleView)
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 28f
            setTextColor(Color.parseColor("#C0392B"))
            setPadding(16, 0, 8, 0)
            setOnClickListener { collapseToBubble() }
        }
        titleBar.addView(closeBtn)
        container.addView(titleBar)

        // Search bar
        searchEditText = EditText(this).apply {
            hint = if (getLanguage() == "ar") "بحث..." else "Search..."
            textSize = textSize
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { filterNotes(s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        container.addView(searchEditText)

        // ListView for notes
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 16; bottomMargin = 16 }
        }
        container.addView(listView)

        // EditText for new note
        editText = EditText(this).apply {
            hint = if (getLanguage() == "ar") "اكتب ملاحظتك..." else "Write your note..."
            textSize = textSize
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        container.addView(editText)

        // Buttons
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
        }
        val saveBtn = Button(this).apply {
            text = if (getLanguage() == "ar") "حفظ" else "Save"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8 }
            setOnClickListener { saveNote() }
        }
        buttonRow.addView(saveBtn)
        val clearBtn = Button(this).apply {
            text = if (getLanguage() == "ar") "مسح" else "Clear"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { editText.setText("") }
        }
        buttonRow.addView(clearBtn)
        container.addView(buttonRow)

        // Settings, Lock, Share, Delete, Undo, Redo buttons
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
        }
        val settingsBtn = createActionButton("⚙️") { showSettingsDialog() }
        val lockAllBtn = createActionButton("🔒") { toggleAllNotesLock() }
        val shareBtn = createActionButton("📤") { shareAllNotes() }
        val deleteAllBtn = createActionButton("🗑️") { deleteAllNotes() }
        val undoBtn = createActionButton("↩️") { undoLastAction() }
        val redoBtn = createActionButton("↪️") { redoLastAction() }
        actionRow.addView(settingsBtn)
        actionRow.addView(lockAllBtn)
        actionRow.addView(shareBtn)
        actionRow.addView(deleteAllBtn)
        actionRow.addView(undoBtn)
        actionRow.addView(redoBtn)
        container.addView(actionRow)

        // Resize handle
        val resizeHandle = TextView(this).apply {
            text = "◢"
            textSize = 20f
            setTextColor(Color.GRAY)
            gravity = Gravity.END or Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40).apply { topMargin = 8 }
            setOnTouchListener(ResizeTouchListener())
        }
        container.addView(resizeHandle)

        noteView = container
        val params = WindowManager.LayoutParams(
            currentWidth, currentHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        windowManager.addView(noteView, params)
        updateNotesList()
    }

    private fun createActionButton(icon: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = icon
            textSize = 18f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    // ==================== NOTE MANAGEMENT ====================
    private fun loadNotes() {
        val gson = Gson()
        val json = prefs.getString(KEY_NOTES, "[]")
        val type = object : TypeToken<MutableList<Note>>() {}.type
        notesList = gson.fromJson(json, type)
        if (notesList.isEmpty()) notesList.add(Note(title = "Welcome!", content = "Tap + to add a note", lastEdited = System.currentTimeMillis()))
        saveNotes()
    }

    private fun saveNotes() {
        val gson = Gson()
        prefs.edit().putString(KEY_NOTES, gson.toJson(notesList)).apply()
        updateNotesList()
    }

    private fun updateNotesList() {
        val titles = notesList.map { if (it.isLocked) "🔒 ${it.title}" else it.title }.toMutableList()
        notesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
        listView.adapter = notesAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val note = notesList[position]
            if (note.isLocked && !checkPassword()) {
                Toast.makeText(this, "This note is locked", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            showEditNoteDialog(note, position)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val note = notesList[position]
            if (note.isLocked && !checkPassword()) {
                Toast.makeText(this, "This note is locked", Toast.LENGTH_SHORT).show()
                return@setOnItemLongClickListener true
            }
            showNoteOptionsDialog(note, position)
            true
        }
    }

    private fun filterNotes(query: String) {
        val filtered = if (query.isEmpty()) notesList else notesList.filter { it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true) }
        val titles = filtered.map { if (it.isLocked) "🔒 ${it.title}" else it.title }.toMutableList()
        notesAdapter.clear()
        notesAdapter.addAll(titles)
        notesAdapter.notifyDataSetChanged()
    }

    private fun saveNote() {
        val content = editText.text.toString().trim()
        if (content.isEmpty()) {
            Toast.makeText(this, "Please write something", Toast.LENGTH_SHORT).show()
            return
        }
        val title = content.take(30)
        notesList.add(0, Note(title = title, content = content))
        saveNotes()
        editText.setText("")
        Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
    }

    private fun showEditNoteDialog(note: Note, position: Int) {
        val input = EditText(this).apply { setText(note.content) }
        AlertDialog.Builder(this)
            .setTitle("Edit Note")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                note.content = input.text.toString()
                note.title = note.content.take(30)
                note.lastEdited = System.currentTimeMillis()
                notesList[position] = note
                saveNotes()
                Toast.makeText(this, "Note updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNoteOptionsDialog(note: Note, position: Int) {
        val options = arrayOf("Delete", if (note.isLocked) "Unlock" else "Lock")
        AlertDialog.Builder(this)
            .setTitle(note.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        notesList.removeAt(position)
                        saveNotes()
                        Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        if (!note.isLocked && !checkPassword()) return@setItems
                        note.isLocked = !note.isLocked
                        notesList[position] = note
                        saveNotes()
                        Toast.makeText(this, if (note.isLocked) "Note locked" else "Note unlocked", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun toggleAllNotesLock() {
        if (!checkPassword()) return
        val allLocked = notesList.all { it.isLocked }
        notesList.forEach { it.isLocked = !allLocked }
        saveNotes()
        Toast.makeText(this, if (allLocked) "All notes unlocked" else "All notes locked", Toast.LENGTH_SHORT).show()
    }

    private fun shareAllNotes() {
        val content = notesList.joinToString("\n\n") { "${it.title}\n${it.content}" }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(sendIntent, "Share Notes"))
    }

    private fun deleteAllNotes() {
        AlertDialog.Builder(this)
            .setTitle("Delete All Notes")
            .setMessage("Are you sure?")
            .setPositiveButton("Delete") { _, _ ->
                notesList.clear()
                notesList.add(Note(title = "Welcome!", content = "Tap + to add a note"))
                saveNotes()
                Toast.makeText(this, "All notes deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==================== UNDO/REDO ====================
    private fun undoLastAction() { Toast.makeText(this, "Undo coming soon", Toast.LENGTH_SHORT).show() }
    private fun redoLastAction() { Toast.makeText(this, "Redo coming soon", Toast.LENGTH_SHORT).show() }

    // ==================== SECURITY ====================
    private fun hash(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isPasswordSet(): Boolean = prefs.getBoolean(KEY_PASSWORD_SET, false)

    private fun checkPassword(): Boolean {
        if (!isPasswordSet()) {
            showSetPasswordDialog()
            return false
        }
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("Enter Password")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val hash = hash(input.text.toString())
                if (hash == prefs.getString(KEY_MASTER_PASSWORD, "")) true
                else Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
        return false
    }

    private fun showSetPasswordDialog() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("Set Master Password")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val pass = input.text.toString()
                if (pass.length < 3) Toast.makeText(this, "Min 3 chars", Toast.LENGTH_SHORT).show()
                else {
                    prefs.edit().putString(KEY_MASTER_PASSWORD, hash(pass)).putBoolean(KEY_PASSWORD_SET, true).apply()
                    showSecurityQuestionDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSecurityQuestionDialog() {
        val questions = arrayOf("What is your mother's maiden name?", "What was your first pet's name?", "What city were you born in?")
        AlertDialog.Builder(this)
            .setTitle("Security Question")
            .setItems(questions) { _, which ->
                val answerInput = EditText(this)
                AlertDialog.Builder(this)
                    .setTitle(questions[which])
                    .setView(answerInput)
                    .setPositiveButton("Save") { _, _ ->
                        prefs.edit().putString(KEY_SECURITY_QUESTION, questions[which]).putString(KEY_SECURITY_ANSWER_HASH, hash(answerInput.text.toString())).apply()
                        Toast.makeText(this, "Security question saved", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Skip", null)
                    .show()
            }
            .show()
    }

    // ==================== SETTINGS ====================
    private fun loadSettings() {
        val dark = prefs.getBoolean(KEY_DARK_MODE, false)
        textSize = prefs.getFloat(KEY_TEXT_SIZE, 14f)
        opacity = prefs.getFloat(KEY_OPACITY, 0.9f)
        scrollSpeed = prefs.getFloat(KEY_SCROLL_SPEED, 1.0f)
    }

    private fun getLanguage(): String = prefs.getString(KEY_LANGUAGE, "en")!!

    private fun showSettingsDialog() {
        val options = arrayOf("Dark Mode", "Text Size", "Opacity", "Scroll Speed", "Change Password", "Security Question", "Backup/Restore", "Language")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleDarkMode()
                    1 -> showTextSizeDialog()
                    2 -> showOpacityDialog()
                    3 -> showScrollSpeedDialog()
                    4 -> changePassword()
                    5 -> showSecurityQuestionSetup()
                    6 -> showBackupRestoreDialog()
                    7 -> showLanguageDialog()
                }
            }
            .show()
    }

    private fun toggleDarkMode() {
        val newMode = !prefs.getBoolean(KEY_DARK_MODE, false)
        prefs.edit().putBoolean(KEY_DARK_MODE, newMode).apply()
        Toast.makeText(this, if (newMode) "Dark mode enabled" else "Light mode enabled", Toast.LENGTH_SHORT).show()
        if (isExpanded) collapseToBubble()
    }

    private fun showTextSizeDialog() {
        val seekBar = SeekBar(this).apply { progress = textSize.toInt() - 10; max = 20 }
        AlertDialog.Builder(this).setTitle("Text Size").setView(seekBar).setPositiveButton("Set") { _, _ ->
            textSize = (seekBar.progress + 10).toFloat()
            prefs.edit().putFloat(KEY_TEXT_SIZE, textSize).apply()
            Toast.makeText(this, "Text size set to ${textSize.toInt()}", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun showOpacityDialog() {
        val seekBar = SeekBar(this).apply { progress = (opacity * 100).toInt(); max = 100 }
        AlertDialog.Builder(this).setTitle("Opacity").setView(seekBar).setPositiveButton("Set") { _, _ ->
            opacity = seekBar.progress / 100f
            prefs.edit().putFloat(KEY_OPACITY, opacity).apply()
            if (noteView != null) noteView?.alpha = opacity
            Toast.makeText(this, "Opacity set to ${(opacity * 100).toInt()}%", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun showScrollSpeedDialog() {
        val seekBar = SeekBar(this).apply { progress = (scrollSpeed * 10).toInt(); max = 30 }
        AlertDialog.Builder(this).setTitle("Scroll Speed").setView(seekBar).setPositiveButton("Set") { _, _ ->
            scrollSpeed = seekBar.progress / 10f
            prefs.edit().putFloat(KEY_SCROLL_SPEED, scrollSpeed).apply()
            Toast.makeText(this, "Scroll speed set to ${scrollSpeed}x", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun changePassword() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("New Password")
            .setView(input)
            .setPositiveButton("Change") { _, _ ->
                val newPass = input.text.toString()
                if (newPass.length < 3) Toast.makeText(this, "Min 3 chars", Toast.LENGTH_SHORT).show()
                else {
                    prefs.edit().putString(KEY_MASTER_PASSWORD, hash(newPass)).apply()
                    Toast.makeText(this, "Password changed", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showSecurityQuestionSetup() {
        val questions = arrayOf("What is your mother's maiden name?", "What was your first pet's name?", "What city were you born in?")
        AlertDialog.Builder(this)
            .setTitle("Set Security Question")
            .setItems(questions) { _, which ->
                val answerInput = EditText(this)
                AlertDialog.Builder(this)
                    .setTitle(questions[which])
                    .setView(answerInput)
                    .setPositiveButton("Save") { _, _ ->
                        prefs.edit().putString(KEY_SECURITY_QUESTION, questions[which]).putString(KEY_SECURITY_ANSWER_HASH, hash(answerInput.text.toString())).apply()
                        Toast.makeText(this, "Security question saved", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .show()
    }

    private fun showBackupRestoreDialog() {
        val options = arrayOf("Backup Notes", "Restore Notes")
        AlertDialog.Builder(this)
            .setTitle("Backup / Restore")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> backupNotes()
                    1 -> restoreNotes()
                }
            }
            .show()
    }

    private fun backupNotes() {
        val file = File(filesDir, "notes_backup_${System.currentTimeMillis()}.json")
        file.writeText(Gson().toJson(notesList))
        prefs.edit().putLong(KEY_LAST_BACKUP, System.currentTimeMillis()).apply()
        Toast.makeText(this, "Backup saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun restoreNotes() {
        val files = filesDir.listFiles { _, name -> name.startsWith("notes_backup_") }
        if (files.isNullOrEmpty()) {
            Toast.makeText(this, "No backup found", Toast.LENGTH_SHORT).show()
            return
        }
        val fileNames = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Backup")
            .setItems(fileNames) { _, which ->
                val json = files[which].readText()
                val type = object : TypeToken<MutableList<Note>>() {}.type
                notesList = Gson().fromJson(json, type)
                saveNotes()
                Toast.makeText(this, "Notes restored", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("English", "العربية", "עברית")
        AlertDialog.Builder(this)
            .setTitle("Select Language")
            .setItems(languages) { _, which ->
                val lang = when (which) { 0 -> "en"; 1 -> "ar"; else -> "he" }
                prefs.edit().putString(KEY_LANGUAGE, lang).apply()
                Toast.makeText(this, "Language changed. Restart bubble to apply.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ==================== RESIZE ====================
    inner class ResizeTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isResizing = true
                    resizeStartX = event.rawX.toInt()
                    resizeStartY = event.rawY.toInt()
                    resizeStartWidth = currentWidth
                    resizeStartHeight = currentHeight
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizing) {
                        val dx = event.rawX.toInt() - resizeStartX
                        val dy = event.rawY.toInt() - resizeStartY
                        currentWidth = (resizeStartWidth + dx).coerceIn(NOTEPAD_MIN_WIDTH, NOTEPAD_MAX_WIDTH)
                        currentHeight = (resizeStartHeight + dy).coerceIn(NOTEPAD_MIN_HEIGHT, NOTEPAD_MAX_HEIGHT)
                        noteView?.layoutParams?.width = currentWidth
                        noteView?.layoutParams?.height = currentHeight
                        windowManager.updateViewLayout(noteView, noteView?.layoutParams)
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> isResizing = false
            }
            return false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
        noteView?.let { windowManager.removeView(it) }
    }
    override fun onBind(intent: Intent?) = null
}
