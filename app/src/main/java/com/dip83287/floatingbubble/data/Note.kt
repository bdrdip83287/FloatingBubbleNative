package com.dip83287.floatingbubble.data

import java.util.Date

data class Note(
    val id: Long = System.currentTimeMillis(),
    val title: String = "Untitled Note",
    val content: String = "",
    val preview: String = "",
    val isLocked: Boolean = false,
    val lastEdited: Long = System.currentTimeMillis()
) {
    fun getFormattedDate(): String {
        val date = Date(lastEdited)
        return android.text.format.DateFormat.getDateFormat(null).format(date)
    }
}
