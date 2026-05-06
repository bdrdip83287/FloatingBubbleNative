package com.dip83287.floatingbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dip83287.floatingbubble.utils.SimpleLog

class FloatingBubbleService : Service() {
    
    private lateinit var log: SimpleLog
    
    override fun onCreate() {
        super.onCreate()
        log = SimpleLog.getInstance(this)
        log.i("FloatingBubbleService", "onCreate called")
        
        // Create notification channel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_bubble_channel",
                "Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(this, "floating_bubble_channel")
            .setContentTitle("Floating Notes")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        
        startForeground(1001, notification)
        log.i("FloatingBubbleService", "Foreground service started")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.i("FloatingBubbleService", "onStartCommand called")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        log.i("FloatingBubbleService", "onDestroy called")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
