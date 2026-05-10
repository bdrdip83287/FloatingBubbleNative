package com.dip83287.floatingbubble.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object SystemLogger {

    private var logDir: File? = null
    private var runtimeFile: File? = null
    private var errorFile: File? = null
    private var flowFile: File? = null

    fun init(context: Context) {
        try {
            logDir = File(context.getExternalFilesDir(null), "FloatingBubbleLogs")

            if (!logDir!!.exists()) {
                logDir!!.mkdirs()
            }

            runtimeFile = File(logDir, "runtime_log.txt")
            errorFile = File(logDir, "error_log.txt")
            flowFile = File(logDir, "flow_log.txt")

            logRuntime("LOGGER INIT OK -> ${logDir!!.absolutePath}")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun time(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun logRuntime(msg: String) {
        try {
            runtimeFile?.appendText("[${time()}] $msg\n")
        } catch (_: Exception) {}
    }

    fun logError(msg: String, e: Throwable?) {
        try {
            errorFile?.appendText("""
                
[${time()}] ERROR: $msg
${e?.stackTraceToString()}

-----------------------------

            """.trimIndent())
        } catch (_: Exception) {}
    }

    fun flow(fn: String, state: String) {
        try {
            flowFile?.appendText("[${time()}] $fn => $state\n")
        } catch (_: Exception) {}
    }
}
