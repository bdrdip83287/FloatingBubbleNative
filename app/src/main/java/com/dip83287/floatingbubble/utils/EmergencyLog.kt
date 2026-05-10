package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object EmergencyLog {

    private lateinit var logFile: File

    fun init(context: Context) {

        try {

            val logDir = File(
                Environment.getExternalStorageDirectory(),
                "FloatingNotesLogs"
            )

            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            logFile = File(logDir, "floating_notes_crash_log.txt")

            if (!logFile.exists()) {
                logFile.createNewFile()
            }

            log("LOGGER INITIALIZED")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun time(): String {
        return SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
    }

    fun log(message: String) {

        try {

            if (!::logFile.isInitialized) return

            logFile.appendText(
                "\n[${time()}] $message\n"
            )

        } catch (_: Exception) {
        }
    }

    fun logError(message: String, throwable: Throwable?) {

        try {

            if (!::logFile.isInitialized) return

            val sw = StringWriter()
            val pw = PrintWriter(sw)

            throwable?.printStackTrace(pw)

            logFile.appendText(
                """

================ CRASH ================

TIME: ${time()}

MESSAGE:
$message

STACKTRACE:
${sw.toString()}

=======================================

                """.trimIndent()
            )

        } catch (_: Exception) {
        }
    }

    fun getLogContent(): String {

        return try {

            if (!::logFile.isInitialized) {
                "Log file not initialized"
            } else if (!logFile.exists()) {
                "Log file not found"
            } else {
                logFile.readText()
            }

        } catch (e: Exception) {
            "Failed to read log:\n${e.message}"
        }
    }

    fun installGlobalCrashCatcher(context: Context) {

        init(context)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->

            logError(
                "GLOBAL APP CRASH",
                throwable
            )

            android.os.Process.killProcess(
                android.os.Process.myPid()
            )

            exitProcess(1)
        }
    }

    private fun exitProcess(code: Int) {
        kotlin.system.exitProcess(code)
    }
}
