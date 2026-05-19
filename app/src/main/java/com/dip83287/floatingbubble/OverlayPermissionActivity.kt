package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.EmergencyLog

class OverlayPermissionActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 5001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_permission)

        EmergencyLog.log("OverlayPermissionActivity Opened")

        val title = findViewById<TextView>(R.id.permissionTitle)
        val desc = findViewById<TextView>(R.id.permissionDesc)
        val btnGrant = findViewById<Button>(R.id.btnGrant)

        title.text = "Overlay Permission Required"
        desc.text = "This app needs overlay permission to show floating bubble over other apps."

        btnGrant.setOnClickListener {
            EmergencyLog.log("Overlay Permission Button Clicked")
            openOverlaySettings()
        }

        checkPermissionAlreadyGranted()
    }

    private fun checkPermissionAlreadyGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay already granted")
                startMainApp()
            }
        }
    }

    private fun openOverlaySettings() {
        try {
            EmergencyLog.log("Opening Overlay Settings")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
        } catch (e: Exception) {
            EmergencyLog.logError("Overlay settings error: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    EmergencyLog.log("Overlay permission granted")
                    startMainApp()
                } else {
                    EmergencyLog.logError("Overlay permission denied")
                }
            }
        }
    }

    private fun startMainApp() {
        try {
            EmergencyLog.log("Starting Bubble Service")
            startService(Intent(this, FloatingBubbleService::class.java))
        } catch (e: Exception) {
            EmergencyLog.logError("Service start failed: ${e.message}")
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
