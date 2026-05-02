package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class SimpleLog private constructor(context: Context) {

    companion object {
        private const val LOG_FILE_NAME = "floating_notes_log.txt"
        
        @Volatile
        private var instance: SimpleLog? = null
        
        fun getInstance(context: Context): SimpleLog {
            return instance ?: synchronized(this) {
                instance ?: SimpleLog(context.applicationContext).also { instance = it }
            }
        }
    }

    private val logFile: File
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    init {
        val logDir = File(Environment.getExternalStorageDirectory(), "FloatingNotesLogs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        logFile = File(logDir, LOG_FILE_NAME)
        writeLog("=== APP STARTED at ${dateFormat.format(Date())} ===")
    }

    private fun writeLog(message: String) {
        try {
            FileWriter(logFile, true).use { writer ->
                writer.write("$message\n")
                writer.flush()
            }
            Log.d("FloatingNotes", message)
        } catch (e: Exception) {
            Log.e("FloatingNotes", "Failed to write log: ${e.message}")
        }
    }

    private fun formatMessage(level: String, tag: String, msg: String): String {
        val timestamp = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        return "$timestamp [$level] [$thread] [$tag] $msg"
    }

    fun d(tag: String, msg: String) {
        writeLog(formatMessage("DEBUG", tag, msg))
    }

    fun i(tag: String, msg: String) {
        writeLog(formatMessage("INFO", tag, msg))
    }

    fun w(tag: String, msg: String) {
        writeLog(formatMessage("WARN", tag, msg))
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        val errorMsg = if (throwable != null) {
            "$msg\n${throwable.stackTrace.joinToString("\n") { "    at $it" }}"
        } else {
            msg
        }
        writeLog(formatMessage("ERROR", tag, errorMsg))
    }

    fun crash(tag: String, throwable: Throwable) {
        val crashMsg = """
            ========== CRASH ==========
            Thread: ${Thread.currentThread().name}
            Exception: ${throwable.javaClass.simpleName}
            Message: ${throwable.message}
            StackTrace:
            ${throwable.stackTrace.joinToString("\n") { "    at $it" }}
            ========== END ==========
        """.trimIndent()
        writeLog(formatMessage("CRASH", tag, crashMsg))
    }

    fun getLogContent(): String {
        return try {
            logFile.readText()
        } catch (e: Exception) {
            "Unable to read log: ${e.message}"
        }
    }

    fun getLogFile(): File = logFile
}
