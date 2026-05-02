package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    companion object {
        private var defaultHandler: Thread.UncaughtExceptionHandler? = null
        fun init(context: Context) {
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext))
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val lm = LogManager.getInstance(context)
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        lm.crash("CrashHandler", "Thread: ${thread.name}\n${sw.toString()}", throwable)
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, "App crashed. Log saved.", Toast.LENGTH_LONG).show() }
        Thread.sleep(1000)
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
