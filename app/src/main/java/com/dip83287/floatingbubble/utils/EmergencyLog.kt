package com.dip83287.floatingbubble.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object EmergencyLog {
    
    private const val LOG_FILE_NAME = "emergency_log.txt"
    private lateinit var logFile: File
    private var isInitialized = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    fun init(context: Context) {
        if (isInitialized) return
        
        try {
            val logDir = File(context.filesDir, "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            logFile = File(logDir, LOG_FILE_NAME)
            
            // Clear previous log on each fresh start
            if (logFile.exists()) {
                logFile.writeText("")
            }
            
            write("=== EMERGENCY LOG STARTED at ${dateFormat.format(Date())} ===")
            write("Log file path: ${logFile.absolutePath}")
            isInitialized = true
            
        } catch (e: Exception) {
            Log.e("EmergencyLog", "Failed to init: ${e.message}")
        }
    }
    
    private fun write(message: String) {
        if (!isInitialized) return
        
        try {
            FileWriter(logFile, true).use { writer ->
                writer.write("$message\n")
                writer.flush()
            }
        } catch (e: Exception) {
            // Silent fail - nothing we can do
        }
    }
    
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        write("$timestamp [$thread] $message")
        Log.d("EmergencyLog", message)
    }
    
    fun logError(message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        write("$timestamp [ERROR] [$thread] $message")
        if (throwable != null) {
            write("$timestamp [ERROR] StackTrace: ${throwable.stackTrace.joinToString("\n") { "    at $it" }}")
        }
        Log.e("EmergencyLog", message, throwable)
    }
    
    fun getLogContent(): String {
        return try {
            if (isInitialized && logFile.exists()) {
                logFile.readText()
            } else {
                "Log file not initialized or not found"
            }
        } catch (e: Exception) {
            "Failed to read log: ${e.message}"
        }
    }
    
    fun getLogFile(): File? = if (isInitialized) logFile else null
}

fun clearLog() {
    if (isInitialized && logFile.exists()) {
        logFile.writeText("")
        write("=== LOG CLEARED at ${dateFormat.format(Date())} ===")
    }
}
