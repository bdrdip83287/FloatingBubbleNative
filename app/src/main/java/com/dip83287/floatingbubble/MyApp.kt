package com.dip83287.floatingbubble

import android.app.Application
import com.dip83287.floatingbubble.utils.EmergencyLog

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EmergencyLog.init(this)
        EmergencyLog.log("Application Started")
    }
}
