package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var noteAdapter: NoteAdapter
    private val notes = mutableListOf<Note>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create layout programmatically
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
        
        // Add sample notes
        notes.add(Note(title = "Welcome!", content = "Tap + to create a new note"))
        notes.add(Note(title = "Floating Bubble", content = "This bubble appears over other apps!"))
        noteAdapter.notifyDataSetChanged()
        
        // Check overlay permission
        checkOverlayPermission()
    }
    
    private fun createNewNote() {
        val newNote = Note(title = "New Note", content = "")
        notes.add(0, newNote)
        noteAdapter.notifyItemInserted(0)
        openNoteEditor(newNote)
    }
    
    private fun openNoteEditor(note: Note) {
        val intent = Intent(this, NoteEditorActivity::class.java)
        intent.putExtra("note_title", note.title)
        intent.putExtra("note_content", note.content)
        intent.putExtra("note_index", notes.indexOf(note))
        startActivity(intent)
    }
    
    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                startFloatingBubbleService()
            }
        }
    }
    
    private fun startFloatingBubbleService() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Floating bubble started", Toast.LENGTH_SHORT).show()
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh notes if needed
    }
    
    data class Note(
        var title: String,
        var content: String
    )
    
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
