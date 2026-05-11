package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Environment
import java.io.File

object EmergencyLog {

    private var logFile: File? = null

    fun init(context: Context) {

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

        write("==== APP STARTED ====")
    }

    fun write(msg: String) {

        try {

            logFile?.appendText(
                "\n${System.currentTimeMillis()} : $msg"
            )

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLogFile(): File? {
        return logFile
    }
}