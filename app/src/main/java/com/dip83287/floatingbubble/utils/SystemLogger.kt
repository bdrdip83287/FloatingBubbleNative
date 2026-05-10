package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object SystemLogger {

    private const val TAG = "FLOATING_TRACE"

    private lateinit var runtimeFile: File
    private lateinit var errorFile: File
    private lateinit var flowFile: File

    fun init(context: Context) {

        val logDir = File(
            Environment.getExternalStorageDirectory(),
            "FloatingBubbleLogs"
        )

        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        runtimeFile = File(logDir, "runtime_log.txt")
        errorFile = File(logDir, "error_log.txt")
        flowFile = File(logDir, "flow_log.txt")

        logRuntime("===== LOGGER STARTED =====")
    }

    private fun getTime(): String {
        return SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())
    }

    // =============================
    // Runtime Log
    // =============================

    fun logRuntime(message: String) {

        val text = "[${getTime()}] $message"

        Log.d(TAG, text)

        try {
            runtimeFile.appendText("$text\n")
        } catch (_: Exception) {}
    }

    // =============================
    // Error Log
    // =============================

    fun logError(message: String, e: Throwable?) {

        val stack = e?.stackTraceToString() ?: "No Stacktrace"

        val text = """

❌ ERROR TIME: ${getTime()}
MESSAGE: $message

STACKTRACE:
$stack

====================================

        """.trimIndent()

        Log.e(TAG, text)

        try {
            errorFile.appendText("$text\n")
        } catch (_: Exception) {}
    }

    // =============================
    // Flow Log
    // =============================

    fun flow(functionName: String, state: String) {

        val text = "[${getTime()}] [$functionName] => $state"

        try {
            flowFile.appendText("$text\n")
        } catch (_: Exception) {}
    }

    // =============================
    // Safe Run Wrapper
    // =============================

    inline fun safe(
        functionName: String,
        block: () -> Unit
    ) {

        flow(functionName, "ENTER")

        try {

            block()

            flow(functionName, "SUCCESS")

        } catch (e: Exception) {

            flow(functionName, "FAILED")

            logError("Crash inside: $functionName", e)
        }

        flow(functionName, "EXIT")
    }
}
