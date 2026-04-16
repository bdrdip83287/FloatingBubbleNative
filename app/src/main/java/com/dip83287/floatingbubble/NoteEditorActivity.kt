import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class NoteEditorActivity : AppCompatActivity() {
    private lateinit var titleInput: EditText
    private lateinit var contentInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        
        titleInput = EditText(this).apply {
            hint = "Title"
            setText(intent.getStringExtra("note_title") ?: "")
            layout.addView(this)
        }
        
        contentInput = EditText(this).apply {
            hint = "Content"
            setText(intent.getStringExtra("note_content") ?: "")
            layout.addView(this)
        }
        
        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val resultIntent = Intent().apply {
                    putExtra("note_id", intent.getLongExtra("note_id", 0))
                    putExtra("title", titleInput.text.toString())
                    putExtra("content", contentInput.text.toString())
                    putExtra("index", intent.getIntExtra("note_index", -1))
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
            layout.addView(this)
        }
        
        setContentView(layout)
    }
}