package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object EmergencyLog {

    private var logFile: File? = null

    fun init(context: Context) {

        try {

            val rootDir = Environment.getExternalStorageDirectory()

            val logDir = File(
                rootDir,
                "FloatingNotesLogs"
            )

            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            logFile = File(
                logDir,
                "floating_notes_crash_log.txt"
            )

            if (!logFile!!.exists()) {
                logFile!!.createNewFile()
            }

            write("===== LOGGER STARTED =====")
            write("PATH => ${logFile!!.absolutePath}")

        } catch (e: Exception) {

            Log.e(
                "LOGGER_DEBUG",
                "LOGGER INIT FAILED",
                e
            )
        }
    }

    fun write(message: String) {

        try {

            if (logFile == null) return

            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            logFile!!.appendText(
                "[$time] $message\n"
            )

        } catch (e: Exception) {

            Log.e(
                "LOGGER_DEBUG",
                "WRITE FAILED",
                e
            )
        }
    }

    fun getLogFile(): File? {
        return logFile
    }
}
