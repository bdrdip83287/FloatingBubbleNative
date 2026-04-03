package com.dip83287.floatingbubble.repository

import com.dip83287.floatingbubble.data.AppDatabase
import com.dip83287.floatingbubble.data.Note
import kotlinx.coroutines.flow.Flow

class NoteRepository(private val database: AppDatabase) {
    
    fun getAllNotes(): Flow<List<Note>> = database.noteDao().getAllNotes()
    
    suspend fun insertNote(note: Note): Long = database.noteDao().insertNote(note)
    
    suspend fun updateNote(note: Note) = database.noteDao().updateNote(note)
    
    suspend fun deleteNote(note: Note) = database.noteDao().deleteNote(note)
    
    suspend fun deleteAllNotes() = database.noteDao().deleteAllNotes()
}
