package com.dip83287.floatingbubble

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dip83287.floatingbubble.data.AppDatabase
import com.dip83287.floatingbubble.data.Note
import com.dip83287.floatingbubble.repository.NoteRepository
import kotlinx.coroutines.launch

class NoteEditorActivity : AppCompatActivity() {
    
    private lateinit var repository: NoteRepository
    private var noteId: Long = 0
    private var isLocked: Boolean = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)
        
        val database = AppDatabase.getDatabase(this)
        repository = NoteRepository(database)
        
        noteId = intent.getLongExtra("note_id", 0)
        val title = intent.getStringExtra("note_title") ?: "Untitled Note"
        val content = intent.getStringExtra("note_content") ?: ""
        
        findViewById<TextView>(R.id.tvNoteTitle).text = title
        findViewById<EditText>(R.id.etNoteContent).setText(content)
        
        setupClickListeners()
        loadNote()
    }
    
    private fun setupClickListeners() {
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }
        
        findViewById<android.view.View>(R.id.btnSaveNote).setOnClickListener {
            saveNote()
        }
        
        findViewById<android.view.View>(R.id.btnDeleteNote).setOnClickListener {
            deleteNote()
        }
        
        findViewById<android.view.View>(R.id.btnShareNote).setOnClickListener {
            shareNote()
        }
    }
    
    private fun loadNote() {
        lifecycleScope.launch {
            val note = repository.getAllNotes().collect { notes ->
                notes.find { it.id == noteId }?.let {
                    findViewById<TextView>(R.id.tvNoteTitle).text = it.title
                    findViewById<EditText>(R.id.etNoteContent).setText(it.content)
                    isLocked = it.isLocked
                }
            }
        }
    }
    
    private fun saveNote() {
        val title = findViewById<TextView>(R.id.tvNoteTitle).text.toString()
        val content = findViewById<EditText>(R.id.etNoteContent).text.toString()
        
        lifecycleScope.launch {
            val note = Note(
                id = noteId,
                title = title,
                content = content,
                preview = content.take(50),
                isLocked = isLocked,
                lastEdited = System.currentTimeMillis()
            )
            repository.updateNote(note)
            Toast.makeText(this@NoteEditorActivity, "Note saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun deleteNote() {
        AlertDialog.Builder(this)
            .setTitle("Delete Note")
            .setMessage("Are you sure?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val note = Note(id = noteId)
                    repository.deleteNote(note)
                    Toast.makeText(this@NoteEditorActivity, "Note deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun shareNote() {
        val title = findViewById<TextView>(R.id.tvNoteTitle).text.toString()
        val content = findViewById<EditText>(R.id.etNoteContent).text.toString()
        
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "$title\n\n$content")
        }
        startActivity(android.content.Intent.createChooser(shareIntent, "Share Note"))
    }
}
