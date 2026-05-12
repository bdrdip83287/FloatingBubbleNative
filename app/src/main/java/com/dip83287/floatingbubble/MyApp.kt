package com.dip83287.floatingbubble

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.dip83287.floatingbubble.utils.EmergencyLog

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        EmergencyLog.init(this)

        EmergencyLog.log("APPLICATION STARTED")

        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) {
                    EmergencyLog.log(
                        "${activity.localClassName} CREATED"
                    )
                }

                override fun onActivityStarted(activity: Activity) {
                    EmergencyLog.log(
                        "${activity.localClassName} STARTED"
                    )
                }

                override fun onActivityResumed(activity: Activity) {
                    EmergencyLog.log(
                        "${activity.localClassName} RESUMED"
                    )
                }

                override fun onActivityPaused(activity: Activity) {
                    EmergencyLog.log(
                        "${activity.localClassName} PAUSED"
                    )
                }

                override fun onActivityStopped(activity: Activity) {
                    EmergencyLog.log(
                        "${activity.localClassName} STOPPED"
                    )
                }

                override fun onActivityDestroyed(activity: Activity) {
                    EmergencyLog.log(
                        "${activity.localClassName} DESTROYED"
                    )
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) {
                }
            }
        )
    }
}