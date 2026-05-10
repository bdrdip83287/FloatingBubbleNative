package com.dip83287.floatingbubble.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object SystemLogger {

    private lateinit var logDir: File
    private lateinit var runtimeFile: File
    private lateinit var errorFile: File
    private lateinit var flowFile: File

    fun init(context: Context) {

        // ✅ INTERNAL STORAGE (100% safe)
        logDir = File(context.filesDir, "FloatingBubbleLogs")

        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        runtimeFile = File(logDir, "runtime_log.txt")
        errorFile = File(logDir, "error_log.txt")
        flowFile = File(logDir, "flow_log.txt")

        logRuntime("LOGGER INIT OK -> ${logDir.absolutePath}")
    }

    private fun time(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date())
    }

    fun logRuntime(msg: String) {
        runtimeFile.appendText("[${time()}] $msg\n")
    }

    fun logError(msg: String, e: Throwable?) {
        errorFile.appendText("""
            
[${time()}] ERROR: $msg
${e?.stackTraceToString()}

-----------------------------

        """.trimIndent())
    }

    fun flow(fn: String, state: String) {
        flowFile.appendText("[${time()}] $fn => $state\n")
    }
}
