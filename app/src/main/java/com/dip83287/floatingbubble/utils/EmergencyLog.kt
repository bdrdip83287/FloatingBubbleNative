package com.dip83287.floatingbubble.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EmergencyLog {

    private lateinit var logFile: File

    fun init(context: Context) {

        val dir = File(
            context.getExternalFilesDir(null),
            "FloatingBubbleLogs"
        )

        if (!dir.exists()) {
            dir.mkdirs()
        }

        logFile = File(dir, "runtime_log.txt")

        if (!logFile.exists()) {
            logFile.createNewFile()
        }

        write("LOGGER INITIALIZED")
    }

    fun write(message: String) {

        try {

            if (!::logFile.isInitialized) {
                return
            }

            val time = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

            logFile.appendText("[$time] $message\n")

        } catch (_: Exception) {
        }
    }

    fun getLogFile(): File? {

        return if (::logFile.isInitialized) {
            logFile
        } else {
            null
        }
    }
}
