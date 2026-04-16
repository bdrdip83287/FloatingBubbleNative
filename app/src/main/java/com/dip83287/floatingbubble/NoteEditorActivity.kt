// app/src/main/java/com/dip83287/floatingbubble/NoteEditorActivity.kt

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.dip83287.floatingbubble.data.Note
import com.dip83287.floatingbubble.repository.NoteRepository

class NoteEditorActivity : AppCompatActivity() {
    
    private lateinit var titleEdit: EditText
    private lateinit var contentEdit: EditText
    private lateinit var repository: NoteRepository
    private var noteId: Long = 0
    private var noteIndex: Int = -1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        repository = NoteRepository(this)
        
        // Get data from intent
        noteId = intent.getLongExtra("note_id", 0)
        val title = intent.getStringExtra("note_title") ?: ""
        val content = intent.getStringExtra("note_content") ?: ""
        noteIndex = intent.getIntExtra("note_index", -1)
        
        // Create layout
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }
        
        // Toolbar
        val toolbar = Toolbar(this).apply {
            setTitle("Edit Note")
            setTitleTextColor(android.graphics.Color.parseColor("#333333"))
            setBackgroundColor(android.graphics.Color.parseColor("#F9E79F"))
        }
        mainLayout.addView(toolbar)
        
        // Title EditText
        titleEdit = EditText(this).apply {
            setText(title)
            hint = "Note title"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 8) }
        }
        mainLayout.addView(titleEdit)
        
        // Content EditText
        contentEdit = EditText(this).apply {
            setText(content)
            hint = "Note content"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(0, 8, 0, 16) }
            isVerticalScrollBarEnabled = true
        }
        mainLayout.addView(contentEdit)
        
        // Button container
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // Save button
        val saveButton = Button(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { saveNote() }
        }
        buttonLayout.addView(saveButton)
        
        // Delete button
        val deleteButton = Button(this).apply {
            text = "Delete"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { deleteNote() }
        }
        buttonLayout.addView(deleteButton)
        
        mainLayout.addView(buttonLayout)
        setContentView(mainLayout)
    }
    
    private fun saveNote() {
        val resultIntent = Intent().apply {
            putExtra("note_id", noteId)
            putExtra("title", titleEdit.text.toString())
            putExtra("content", contentEdit.text.toString())
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