package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dip83287.floatingbubble.adapter.NoteAdapter
import com.dip83287.floatingbubble.data.AppDatabase
import com.dip83287.floatingbubble.data.Note
import com.dip83287.floatingbubble.repository.NoteRepository
import com.dip83287.floatingbubble.utils.PreferenceManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var noteAdapter: NoteAdapter
    private lateinit var repository: NoteRepository
    private lateinit var preferenceManager: PreferenceManager
    
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkOverlayPermission()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        preferenceManager = PreferenceManager(this)
        val database = AppDatabase.getDatabase(this)
        repository = NoteRepository(database)
        
        setupRecyclerView()
        setupClickListeners()
        loadNotes()
        checkOverlayPermission()
        startFloatingBubbleService()
    }
    
    private fun setupRecyclerView() {
        noteAdapter = NoteAdapter { note ->
            openNoteEditor(note)
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewNotes).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = noteAdapter
        }
    }
    
    private fun setupClickListeners() {
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddNote).setOnClickListener {
            createNewNote()
        }
    }
    
    private fun loadNotes() {
        lifecycleScope.launch {
            repository.getAllNotes().collect { notes ->
                noteAdapter.submitList(notes)
            }
        }
    }
    
    private fun createNewNote() {
        lifecycleScope.launch {
            val newNote = Note(
                title = "Untitled Note",
                content = "",
                preview = "",
                lastEdited = System.currentTimeMillis()
            )
            val id = repository.insertNote(newNote)
            val note = Note(
                id = id,
                title = "Untitled Note",
                content = "",
                preview = ""
            )
            openNoteEditor(note)
        }
    }
    
    private fun openNoteEditor(note: Note) {
        val intent = Intent(this, NoteEditorActivity::class.java)
        intent.putExtra("note_id", note.id)
        intent.putExtra("note_title", note.title)
        intent.putExtra("note_content", note.content)
        startActivity(intent)
    }
    
    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Overlay Permission Required")
                    .setMessage("This app needs 'Display over other apps' permission to show floating bubble.")
                    .setPositiveButton("Grant") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    
    private fun startFloatingBubbleService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                val intent = Intent(this, FloatingBubbleService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }
    }
}
