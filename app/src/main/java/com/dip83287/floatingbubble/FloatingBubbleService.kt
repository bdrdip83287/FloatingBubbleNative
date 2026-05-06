package com.dip83287.floatingbubble

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.dip83287.floatingbubble.utils.EmergencyLog

class FloatingBubbleService : Service() {
    
    override fun onCreate() {
        super.onCreate()
        EmergencyLog.log("FloatingBubbleService: onCreate called")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EmergencyLog.log("FloatingBubbleService: onStartCommand called")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        EmergencyLog.log("FloatingBubbleService: onDestroy called")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
