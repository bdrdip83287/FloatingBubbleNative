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

    private lateinit var logFile: File

    fun init(context: Context) {

        try {

            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS
                ),
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

            log("========== LOGGER STARTED ==========")
            log("Android Version: ${Build.VERSION.RELEASE}")
            log("Device SDK: ${Build.VERSION.SDK_INT}")

            setupGlobalCrashHandler()

        } catch (e: Exception) {

            Log.e(TAG, "LOGGER INIT FAILED", e)
        }
    }

    private fun setupGlobalCrashHandler() {

        val defaultHandler =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->

            try {

                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))

                val fullError = """
                    
========== APP CRASH ==========
Thread: ${thread.name}

${sw}

================================
                    
                """.trimIndent()

                append(fullError)

            } catch (_: Exception) {
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun log(message: String) {

        try {

            val finalMessage =
                "[${time()}] [INFO] [${Thread.currentThread().name}] $message"

            Log.d(TAG, finalMessage)

            append(finalMessage)

        } catch (_: Exception) {
        }
    }

    fun logError(message: String) {

        try {

            val finalMessage =
                "[${time()}] [ERROR] [${Thread.currentThread().name}] $message"

            Log.e(TAG, finalMessage)

            append(finalMessage)

        } catch (_: Exception) {
        }
    }

    fun logException(throwable: Throwable) {

        try {

            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val finalMessage = """
                
[${time()}] [EXCEPTION]

${sw}

            """.trimIndent()

            Log.e(TAG, finalMessage)

            append(finalMessage)

        } catch (_: Exception) {
        }
    }

    private fun append(text: String) {

        try {

            rotateIfTooLarge()

            logFile.appendText(text + "\n\n")

        } catch (e: Exception) {

            Log.e(TAG, "FILE WRITE FAILED", e)
        }
    }

    private fun rotateIfTooLarge() {

        try {

            if (::logFile.isInitialized && logFile.exists()) {

                val maxSize = 2 * 1024 * 1024

                if (logFile.length() > maxSize) {

                    val backup = File(
                        logFile.parent,
                        "runtime_log_old.txt"
                    )

                    if (backup.exists()) {
                        backup.delete()
                    }

                    logFile.renameTo(backup)

                    logFile = File(
                        logFile.parent,
                        "runtime_log.txt"
                    )

                    logFile.createNewFile()
                }
            }

        } catch (_: Exception) {
        }
    }

    private fun time(): String {

        return SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
    }

    fun getLogContent(): String {

        return try {

            if (::logFile.isInitialized && logFile.exists()) {

                logFile.readText()

            } else {

                "Log file not found"
            }

        } catch (e: Exception) {

            e.stackTraceToString()
        }
    }

    fun getLogPath(): String {

        return try {

            if (::logFile.isInitialized) {

                logFile.absolutePath

            } else {

                "Logger not initialized"
            }

        } catch (e: Exception) {

            e.stackTraceToString()
        }
    }
}