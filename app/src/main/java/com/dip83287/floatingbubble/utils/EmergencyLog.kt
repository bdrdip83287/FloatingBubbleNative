package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EmergencyLog {

    private const val TAG = "FloatingBubble"
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024 // 2MB

    private lateinit var logFile: File
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return

        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "FloatingBubbleLogs"
            )
            if (!dir.exists()) {
                dir.mkdirs()
            }

            logFile = File(dir, "runtime_log.txt")
            if (!logFile.exists()) {
                logFile.createNewFile()
            }

            rotateIfTooLarge()
            isInitialized = true

            log("========== LOGGER STARTED ==========")
            log("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            log("Device: ${Build.MANUFACTURER} ${Build.MODEL}")

            setupGlobalCrashHandler()

        } catch (e: Exception) {
            Log.e(TAG, "LOGGER INIT FAILED", e)
        }
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val fullError = """
                    
                    ========== APP CRASH ==========
                    Thread: ${thread.name}
                    Time: ${time()}
                    ${sw}
                    ================================
                    
                """.trimIndent()
                append(fullError)
                Log.e(TAG, "CRASH DETECTED: ${throwable.message}", throwable)
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(message: String) {
        try {
            val finalMessage = "[${time()}] [INFO] [${Thread.currentThread().name}] $message"
            Log.d(TAG, finalMessage)
            append(finalMessage)
        } catch (_: Exception) { }
    }

    fun logError(message: String, throwable: Throwable? = null) {
        try {
            val errorMsg = if (throwable != null) {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                "$message\n${sw}"
            } else {
                message
            }
            val finalMessage = "[${time()}] [ERROR] [${Thread.currentThread().name}] $errorMsg"
            Log.e(TAG, finalMessage, throwable)
            append(finalMessage)
        } catch (_: Exception) { }
    }

    fun logException(throwable: Throwable, context: String = "") {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val finalMessage = """
                
                [${time()}] [EXCEPTION] $context
                ${sw}
                
            """.trimIndent()
            Log.e(TAG, finalMessage, throwable)
            append(finalMessage)
        } catch (_: Exception) { }
    }

    fun logLifecycle(component: String, event: String) {
        log("[LIFECYCLE] $component -> $event")
    }

    fun logFlow(flowName: String, step: String, extra: String = "") {
        val msg = if (extra.isEmpty()) "[FLOW] $flowName -> $step" else "[FLOW] $flowName -> $step | $extra"
        log(msg)
    }

    private fun append(text: String) {
        try {
            rotateIfTooLarge()
            logFile.appendText("$text\n")
        } catch (e: Exception) {
            Log.e(TAG, "FILE WRITE FAILED", e)
        }
    }

    private fun rotateIfTooLarge() {
        try {
            if (::logFile.isInitialized && logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                val backup = File(logFile.parent, "runtime_log_old.txt")
                if (backup.exists()) backup.delete()
                logFile.renameTo(backup)
                logFile = File(logFile.parent, "runtime_log.txt")
                logFile.createNewFile()
            }
        } catch (_: Exception) { }
    }

    private fun time(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }

    fun getLogContent(): String {
        return try {
            if (::logFile.isInitialized && logFile.exists()) logFile.readText()
            else "Log file not found"
        } catch (e: Exception) { e.stackTraceToString() }
    }

    fun getLogPath(): String {
        return try {
            if (::logFile.isInitialized) logFile.absolutePath
            else "Logger not initialized"
        } catch (e: Exception) { e.stackTraceToString() }
    }

    fun clearLog() {
        try {
            if (::logFile.isInitialized && logFile.exists()) {
                logFile.writeText("")
                log("Log file cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clear log failed", e)
        }
    }
}
