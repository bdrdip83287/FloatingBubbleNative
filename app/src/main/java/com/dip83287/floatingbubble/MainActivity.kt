package com.dip83287.floatingbubble

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class NoteEditorActivity : AppCompatActivity() {
    
    private lateinit var titleEditText: EditText
    private lateinit var contentEditText: EditText
    private var noteId: Long = 0
    private var noteIndex: Int = -1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get data from intent
        noteId = intent.getLongExtra("note_id", 0)
        val noteTitle = intent.getStringExtra("note_title") ?: ""
        val noteContent = intent.getStringExtra("note_content") ?: ""
        noteIndex = intent.getIntExtra("note_index", -1)
        
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
            title = "Edit Note"
            setTitleTextColor(android.graphics.Color.parseColor("#333333"))
            setBackgroundColor(android.graphics.Color.parseColor("#F9E79F"))
        }
        mainLayout.addView(toolbar)
        
        // Title EditText
        titleEditText = EditText(this).apply {
            hint = "Note Title"
            setText(noteTitle)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(16, 16, 16, 16)
        }
        mainLayout.addView(titleEditText)
        
        // Content EditText
        contentEditText = EditText(this).apply {
            hint = "Note Content"
            setText(noteContent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(16, 16, 16, 16)
        }
        mainLayout.addView(contentEditText)
        
        // Button container
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Save button
        val saveButton = Button(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener { saveNote() }
        }
        buttonContainer.addView(saveButton)
        
        // Delete button
        val deleteButton = Button(this).apply {
            text = "Delete"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener { deleteNote() }
        }
        buttonContainer.addView(deleteButton)
        
        mainLayout.addView(buttonContainer)
        
        setContentView(mainLayout)
    }
    
    private fun saveNote() {
        val resultIntent = Intent().apply {
            putExtra("note_id", noteId)
            putExtra("title", titleEditText.text.toString())
            putExtra("content", contentEditText.text.toString())
            putExtra("index", noteIndex)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
    
    private fun deleteNote() {
        val resultIntent = Intent().apply {
            putExtra("delete_note_id", noteId)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}