package com.dip83287.floatingbubble.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class SimpleCrashHandler(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    companion object {

        private var defaultHandler:
            Thread.UncaughtExceptionHandler? = null

        fun init(context: Context) {

            defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler(
                SimpleCrashHandler(
                    context.applicationContext
                )
            )
        }
    }

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable
    ) {

        EmergencyLog.log(
            "APP CRASH:\n\n" +
            throwable.stackTraceToString()
        )

        Handler(Looper.getMainLooper()).post {

            Toast.makeText(
                context,
                "App crashed! Log saved.",
                Toast.LENGTH_LONG
            ).show()
        }

        Thread.sleep(500)

        defaultHandler?.uncaughtException(
            thread,
            throwable
        )
    }
}