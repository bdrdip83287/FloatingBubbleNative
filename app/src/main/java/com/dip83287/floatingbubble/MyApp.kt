package com.dip83287.floatingbubble

import android.app.Application
import com.dip83287.floatingbubble.utils.SystemLogger

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        SystemLogger.init(this)

        // Global Crash Catcher
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->

            SystemLogger.logError(
                "GLOBAL CRASH in thread: ${thread.name}",
                e
            )
        }

        SystemLogger.logRuntime("Application Started")
    }
}
