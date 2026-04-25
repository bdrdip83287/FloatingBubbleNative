package com.dip83287.floatingbubble.repository

import android.content.Context
import com.dip83287.floatingbubble.data.Note
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NoteRepository(context: Context) {

    private val prefs = context.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAllNotes(): List<Note> {
        return try {
            val json = prefs.getString("notes_list", "[]")
            val type = object : TypeToken<List<Note>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveNotes(list: List<Note>) {
        prefs.edit().putString("notes_list", gson.toJson(list)).apply()
    }
}