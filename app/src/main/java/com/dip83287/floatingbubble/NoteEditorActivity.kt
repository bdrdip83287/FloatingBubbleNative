package com.dip83287.floatingbubble

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class NoteEditorActivity : AppCompatActivity() {
    
    private lateinit var titleInput: EditText
    private lateinit var contentInput: EditText
    private var noteId: Long = 0
    private var noteIndex: Int = -1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val title = intent.getStringExtra("note_title") ?: ""
        val content = intent.getStringExtra("note_content") ?: ""
        noteId = intent.getLongExtra("note_id", System.currentTimeMillis())
        noteIndex = intent.getIntExtra("note_index", -1)
        
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
            title = "Edit Note"
            setTitleTextColor(android.graphics.Color.parseColor("#333333"))
            setBackgroundColor(android.graphics.Color.parseColor("#F9E79F"))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener {
                finish()
            }
        }
        mainLayout.addView(toolbar)
        
        // ScrollView for content
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        // Title input
        titleInput = EditText(this).apply {
            setText(title)
            hint = "Title"
            textSize = 18f
        }
        contentLayout.addView(titleInput)
        
        // Content input
        contentInput = EditText(this).apply {
            setText(content)
            hint = "Content"
            textSize = 14f
            minHeight = 300
            gravity = android.view.Gravity.TOP
        }
        contentLayout.addView(contentInput)
        
        scrollView.addView(contentLayout)
        mainLayout.addView(scrollView)
        
        // Bottom buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        val saveButton = Button(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(0, 100, 1f)
            setBackgroundColor(android.graphics.Color.parseColor("#27AE60"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                saveNote()
            }
        }
        buttonLayout.addView(saveButton)
        
        val deleteButton = Button(this).apply {
            text = "Delete"
            layoutParams = LinearLayout.LayoutParams(0, 100, 1f)
            setBackgroundColor(android.graphics.Color.parseColor("#E74C3C"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                deleteNote()
            }
        }
        buttonLayout.addView(deleteButton)
        
        mainLayout.addView(buttonLayout)
        
        setContentView(mainLayout)
    }
    
    private fun saveNote() {
        val resultIntent = android.content.Intent().apply {
            putExtra("note_id", noteId)
            putExtra("title", titleInput.text.toString())
            putExtra("content", contentInput.text.toString())
            putExtra("index", noteIndex)
        }
        setResult(RESULT_OK, resultIntent)
        Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun deleteNote() {
        val resultIntent = android.content.Intent().apply {
            putExtra("delete_note_id", noteId)
            putExtra("delete_index", noteIndex)
        }
        setResult(RESULT_OK, resultIntent)
        Toast.makeText(this, "Note deleted", Toast.LENGTH_SHORT).show()
        finish()
    }
}