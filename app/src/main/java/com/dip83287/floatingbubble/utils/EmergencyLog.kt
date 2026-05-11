package com.dip83287.floatingbubble.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object EmergencyLog {
    private lateinit var logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        logFile = File(logsDir, "emergency_log.txt")
    }

    fun write(message: String) {
        if (!::logFile.isInitialized) return
        try {
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] $message\n"
            logFile.appendText(logEntry)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun log(message: String) {
        write(message)
    }

    fun logError(tag: String, exception: Exception?) {
        val message = if (exception != null) {
            "$tag: ${exception.message}\n${exception.stackTraceToString()}"
        } else {
            tag
        }
        write(message)
    }

    fun getLogPath(): String {
        return if (::logFile.isInitialized) logFile.absolutePath else ""
    }

    fun getLogContent(): String {
        return if (::logFile.isInitialized && logFile.exists()) {
            logFile.readText()
        } else {
            "No logs available"
        }
    }
}