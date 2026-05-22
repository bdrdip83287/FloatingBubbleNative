package com.dip83287.floatingbubble

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.dip83287.floatingbubble.utils.EmergencyLog

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize logging system
        EmergencyLog.init(this)
        EmergencyLog.log("APPLICATION STARTED")

        // Track all activities lifecycle
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                EmergencyLog.logLifecycle(activity.localClassName, "CREATED")
            }
            override fun onActivityStarted(activity: Activity) {
                EmergencyLog.logLifecycle(activity.localClassName, "STARTED")
            }
            override fun onActivityResumed(activity: Activity) {
                EmergencyLog.logLifecycle(activity.localClassName, "RESUMED")
            }
            override fun onActivityPaused(activity: Activity) {
                EmergencyLog.logLifecycle(activity.localClassName, "PAUSED")
            }
            override fun onActivityStopped(activity: Activity) {
                EmergencyLog.logLifecycle(activity.localClassName, "STOPPED")
            }
            override fun onActivityDestroyed(activity: Activity) {
                EmergencyLog.logLifecycle(activity.localClassName, "DESTROYED")
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}