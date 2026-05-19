package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 5001
    }

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

    private fun openOverlaySettings() {
        try {
            EmergencyLog.log("Opening direct overlay settings")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // DIRECT APP OVERLAY PAGE - এখানেই toggle on/off option থাকবে
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            } else {
                EmergencyLog.log("Overlay permission auto granted below Android M")
            }

        } catch (e: Exception) {
            EmergencyLog.logException(e, "openOverlaySettings")
            try {
                // FALLBACK → APP DETAILS PAGE
                val fallbackIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fallbackIntent)
            } catch (ex: Exception) {
                EmergencyLog.logException(ex, "fallbackOverlaySettings")
            }
        }
    }
}
