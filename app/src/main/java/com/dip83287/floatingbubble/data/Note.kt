package com.dip83287.floatingbubble.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "Untitled Note",
    val content: String = "",
    val preview: String = "",
    val isLocked: Boolean = false,
    val lastEdited: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getFormattedDate(): String {
        val date = java.util.Date(lastEdited)
        return android.text.format.DateFormat.getDateFormat(null).format(date)
    }
}
