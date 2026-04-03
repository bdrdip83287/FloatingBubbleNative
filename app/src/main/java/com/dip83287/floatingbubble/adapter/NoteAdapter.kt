package com.dip83287.floatingbubble.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dip83287.floatingbubble.R
import com.dip83287.floatingbubble.data.Note

class NoteAdapter(
    private val onNoteClick: (Note) -> Unit
) : RecyclerView.Adapter<NoteAdapter.NoteViewHolder>() {
    
    private var notes = listOf<Note>()
    
    fun submitList(newNotes: List<Note>) {
        notes = newNotes
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return NoteViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(notes[position])
    }
    
    override fun getItemCount(): Int = notes.size
    
    inner class NoteViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(android.R.id.text1)
        private val previewView: TextView = itemView.findViewById(android.R.id.text2)
        
        fun bind(note: Note) {
            titleView.text = note.title
            previewView.text = note.preview.ifEmpty { note.content.take(50) }
            itemView.setOnClickListener { onNoteClick(note) }
        }
    }
}
