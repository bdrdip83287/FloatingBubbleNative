package com.dip83287.floatingbubble.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.appcompat.app.AppCompatActivity

class BaseLifecycleTracker(private val logManager: LogManager, private val name: String) : LifecycleObserver {
    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE) fun onCreate() { logManager.flow("Lifecycle", "$name.onCreate()") }
    @OnLifecycleEvent(Lifecycle.Event.ON_START) fun onStart() { logManager.flow("Lifecycle", "$name.onStart()") }
    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME) fun onResume() { logManager.flow("Lifecycle", "$name.onResume()") }
    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE) fun onPause() { logManager.flow("Lifecycle", "$name.onPause()") }
    @OnLifecycleEvent(Lifecycle.Event.ON_STOP) fun onStop() { logManager.flow("Lifecycle", "$name.onStop()") }
    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY) fun onDestroy() { logManager.flow("Lifecycle", "$name.onDestroy()") }

    companion object {
        fun attach(activity: AppCompatActivity, logManager: LogManager) {
            activity.lifecycle.addObserver(BaseLifecycleTracker(logManager, activity.javaClass.simpleName))
        }
    }
}
