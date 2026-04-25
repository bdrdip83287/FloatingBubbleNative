package com.dip83287.floatingbubble.data

data class Note(
    val id: Long = System.currentTimeMillis(),
    val title: String = "",
    val content: String = ""
)