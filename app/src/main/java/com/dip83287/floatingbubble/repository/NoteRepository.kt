package com.dip83287.floatingbubble.repository

import android.content.Context
import android.content.SharedPreferences
import com.dip83287.floatingbubble.data.Note
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NoteRepository(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    fun getAllNotes(): List<Note> {
        val json = prefs.getString("notes_list", "[]")
        val type = object : TypeToken<List<Note>>() {}.type
        return gson.fromJson(json, type)
    }
    
    fun saveNotes(notes: List<Note>) {
        val json = gson.toJson(notes)
        prefs.edit().putString("notes_list", json).apply()
    }
    
    fun addNote(note: Note): List<Note> {
        val notes = getAllNotes().toMutableList()
        notes.add(0, note)
        saveNotes(notes)
        return notes
    }
    
    fun updateNote(note: Note): List<Note> {
        val notes = getAllNotes().toMutableList()
        val index = notes.indexOfFirst { it.id == note.id }
        if (index != -1) {
            notes[index] = note
            saveNotes(notes)
        }
        return notes
    }
    
    fun deleteNote(note: Note): List<Note> {
        val notes = getAllNotes().toMutableList()
        notes.removeAll { it.id == note.id }
        saveNotes(notes)
        return notes
    }
    
    fun deleteAllNotes(): List<Note> {
        saveNotes(emptyList())
        return emptyList()
    }
}
