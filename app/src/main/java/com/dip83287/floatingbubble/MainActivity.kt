package com.dip83287.floatingbubble

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        EmergencyLog.log("MainActivity Started")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay permission missing")
                startActivity(Intent(this, OverlayPermissionActivity::class.java))
                finish()
                return
            }
        }
        
        EmergencyLog.log("Overlay permission already granted")
        setContentView(R.layout.activity_main)
    }
}
