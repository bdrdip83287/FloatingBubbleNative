package com.dip83287.floatingbubble.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object EmergencyLog {

    private var logFile: File? = null

    fun init(context: Context) {

        try {

            val baseDir = context.getExternalFilesDir(null)

            val logDir = File(baseDir, "FloatingNotesLogs")

            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            logFile = File(logDir, "floating_notes_crash_log.txt")

            write("LOGGER INITIALIZED")
            write("PATH => ${logFile?.absolutePath}")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun write(message: String) {

        try {

            if (logFile == null) return

            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            logFile?.appendText(
                "[$time] $message\n"
            )

        } catch (_: Exception) {
        }
    }

    fun getLogFile(): File? {
        return logFile
    }
}
