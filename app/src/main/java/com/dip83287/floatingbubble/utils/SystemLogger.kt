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

    private var initialized = false

    fun init(context: Context) {

        try {

            logDir = File(context.getExternalFilesDir(null), "FloatingBubbleLogs")

            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            runtimeFile = File(logDir, "runtime_log.txt")
            errorFile = File(logDir, "error_log.txt")
            flowFile = File(logDir, "flow_log.txt")

            initialized = true

            logRuntime("LOGGER INIT OK")
            logRuntime("PATH = ${logDir.absolutePath}")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun time(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun logRuntime(msg: String) {
        try {
            if (!initialized) return
            runtimeFile.appendText("[${time()}] $msg\n")
        } catch (_: Exception) {}
    }

    fun logError(msg: String, e: Throwable?) {
        try {
            if (!initialized) return
            errorFile.appendText("""
                
[${time()}] ERROR: $msg
${e?.stackTraceToString()}

----------------------

            """.trimIndent())
        } catch (_: Exception) {}
    }

    fun flow(fn: String, state: String) {
        try {
            if (!initialized) return
            flowFile.appendText("[${time()}] $fn => $state\n")
        } catch (_: Exception) {}
    }

    fun getPath(): String {
        return if (::logDir.isInitialized) logDir.absolutePath else "NOT INIT"
    }
}
