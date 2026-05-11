package com.dip83287.floatingbubble

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EmergencyLog {

    private lateinit var logFile: File

    fun init(context: Context) {

        val logDir = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            ),
            "FloatingBubbleLogs"
        )

        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        logFile = File(logDir, "runtime_log.txt")

        if (!logFile.exists()) {
            logFile.createNewFile()
        }

        log("========== APP STARTED ==========")
        log("Log Path: ${logFile.absolutePath}")
    }

    fun log(message: String) {

        try {

            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            val finalMessage = "[$time] $message\n"

            logFile.appendText(finalMessage)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logError(tag: String, e: Exception) {

        log("ERROR [$tag]")
        log(e.stackTraceToString())
    }

    fun getLogContent(): String {

        return try {
            logFile.readText()
        } catch (e: Exception) {
            "Cannot read logs"
        }
    }

    fun getLogPath(): String {
        return logFile.absolutePath
    }
}