package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Environment
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

            // 🔥 Try External Storage first (Termux readable)
            val externalDir = File(
                Environment.getExternalStorageDirectory(),
                "FloatingBubbleLogs"
            )

            // fallback internal
            val internalDir = File(context.filesDir, "FloatingBubbleLogs")

            logDir = if (isWritable(externalDir)) externalDir else internalDir

            if (!logDir.exists()) logDir.mkdirs()

            runtimeFile = File(logDir, "runtime_log.txt")
            errorFile = File(logDir, "error_log.txt")
            flowFile = File(logDir, "flow_log.txt")

            initialized = true

            logRuntime("LOGGER INIT OK -> ${logDir.absolutePath}")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isWritable(dir: File): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            val test = File(dir, "test.tmp")
            test.writeText("ok")
            test.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureInit() {
        if (!initialized) {
            throw IllegalStateException("SystemLogger not initialized. Call init() in Application or Service")
        }
    }

    private fun time(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun logRuntime(msg: String) {
        try {
            if (!initialized) return
            runtimeFile.appendText("[${time()}] $msg\n")
        } catch (e: Exception) {}
    }

    fun logError(msg: String, e: Throwable?) {
        try {
            if (!initialized) return
            errorFile.appendText(
                """
                
[${time()}] ERROR: $msg
${e?.stackTraceToString()}

-----------------------------

                """.trimIndent()
            )
        } catch (ex: Exception) {}
    }

    fun flow(fn: String, state: String) {
        try {
            if (!initialized) return
            flowFile.appendText("[${time()}] $fn => $state\n")
        } catch (e: Exception) {}
    }

    fun getLogPath(): String {
        return if (::logDir.isInitialized) logDir.absolutePath else "NOT INIT"
    }
}