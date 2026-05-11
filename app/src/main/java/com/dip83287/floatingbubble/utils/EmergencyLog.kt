package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EmergencyLog {

    private var logFile: File? = null

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

            log("==== APP STARTED ====")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun log(message: String) {
        write(message)
    }

    fun write(message: String) {

        try {

            val time = SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            logFile?.appendText(
                "[$time] $message\n"
            )

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logError(message: String) {

        write("ERROR: $message")
    }

    fun getLogContent(): String {

        return try {

            if (logFile != null && logFile!!.exists()) {
                logFile!!.readText()
            } else {
                "Log file not found"
            }

        } catch (e: Exception) {
            e.stackTraceToString()
        }
    }

    fun getLogPath(): String {

        return logFile?.absolutePath ?: "Log path unavailable"
    }

    fun getLogFile(): File? {
        return logFile
    }
}