package com.dip83287.floatingbubble.utils

import android.os.Handler
import android.os.Looper

class SafeExecutor(private val logManager: LogManager) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun <T> execute(tag: String, opName: String, block: () -> T): T? {
        logManager.flow(tag, "→ $opName")
        return try { block().also { logManager.flow(tag, "← $opName ✓") } }
        catch (e: Exception) { logManager.e(tag, "✗ $opName: ${e.message}", e); null }
    }

    fun executeAsync(tag: String, opName: String, block: () -> Unit) { Thread { execute(tag, opName, block) }.start() }
    fun executeOnMain(tag: String, opName: String, block: () -> Unit) { mainHandler.post { execute(tag, opName, block) } }
    inline fun <T> tryOrNull(tag: String, opName: String, block: () -> T): T? = try { block() }
        catch (e: Exception) { logManager.e(tag, "Silent error in $opName: ${e.message}", e); null }
}
