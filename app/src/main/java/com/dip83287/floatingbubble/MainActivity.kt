package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dip83287.floatingbubble.data.Note
import com.dip83287.floatingbubble.repository.NoteRepository

class MainActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var noteAdapter: NoteAdapter
    private lateinit var repository: NoteRepository
    private var notes = mutableListOf<Note>()
    
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingBubbleService()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            repository = NoteRepository(this)
            notes = repository.getAllNotes().toMutableList()
            
            if (notes.isEmpty()) {
                notes.add(Note(title = "Welcome!", content = "Tap + to create a new note"))
                notes.add(Note(title = "Floating Bubble", content = "Tap the bubble to expand!"))
                repository.saveNotes(notes)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
        
        // Create layout
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        // Toolbar
        val toolbar = Toolbar(this).apply {
            title = "Floating Notes"
            setTitleTextColor(android.graphics.Color.parseColor("#333333"))
            setBackgroundColor(android.graphics.Color.parseColor("#F9E79F"))
        }
        mainLayout.addView(toolbar)
        
        // RecyclerView
        recyclerView = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        mainLayout.addView(recyclerView)
        
        // FAB
        val fab = Button(this).apply {
            text = "+"
            textSize = 24f
            setBackgroundColor(android.graphics.Color.parseColor("#F9E79F"))
            setTextColor(android.graphics.Color.parseColor("#333333"))
            val params = LinearLayout.LayoutParams(120, 120)
            params.gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
            params.setMargins(0, 0, 32, 32)
            layoutParams = params
            setOnClickListener {
                createNewNote()
            }
        }
        mainLayout.addView(fab)
        
        setContentView(mainLayout)
        
        // Setup adapter
        noteAdapter = NoteAdapter(notes) { note ->
            openNoteEditor(note)
        }
        recyclerView.adapter = noteAdapter
        
        // Check overlay permission and start bubble
        checkAndStartBubble()
    }
    
    private fun createNewNote() {
        val newNote = Note(title = "New Note", content = "")
        notes.add(0, newNote)
        repository.saveNotes(notes)
        noteAdapter.notifyItemInserted(0)
        openNoteEditor(newNote)
    }
    
    private fun openNoteEditor(note: Note) {
        val intent = Intent(this, NoteEditorActivity::class.java)
        intent.putExtra("note_id", note.id)
        intent.putExtra("note_title", note.title)
        intent.putExtra("note_content", note.content)
        intent.putExtra("note_index", notes.indexOf(note))
        startActivityForResult(intent, 100)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            data?.let {
                if (it.hasExtra("delete_note_id")) {
                    val deleteId = it.getLongExtra("delete_note_id", 0)
                    notes.removeAll { n -> n.id == deleteId }
                } else {
                    val noteId = it.getLongExtra("note_id", 0)
                    val title = it.getStringExtra("title") ?: ""
                    val content = it.getStringExtra("content") ?: ""
                    val index = it.getIntExtra("index", -1)
                    if (index != -1 && index < notes.size) {
                        notes[index] = notes[index].copy(
                            title = title,
                            content = content,
                            preview = content.take(50),
                            lastEdited = System.currentTimeMillis()
                        )
                    }
                }
                repository.saveNotes(notes)
                noteAdapter.notifyDataSetChanged()
            }
        }
    }
    
    private fun checkAndStartBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant overlay permission for floating bubble", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                startFloatingBubbleService()
            }
        } else {
            startFloatingBubbleService()
        }
    }
    
    private fun startFloatingBubbleService() {
        try {
            val intent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Floating bubble started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    class NoteAdapter(
        private val notes: List<Note>,
        private val onItemClick: (Note) -> Unit
    ) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.View.inflate(parent.context, android.R.layout.simple_list_item_2, null)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(notes[position])
        }
        
        override fun getItemCount(): Int = notes.size
        
        inner class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            private val titleView = itemView.findViewById<android.widget.TextView>(android.R.id.text1)
            private val contentView = itemView.findViewById<android.widget.TextView>(android.R.id.text2)
            
            fun bind(note: Note) {
                titleView.text = note.title
                contentView.text = note.content.take(50)
                itemView.setOnClickListener { onItemClick(note) }
            }
        }
    }
}
