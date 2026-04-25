package com.dip83287.floatingbubble

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.PreferenceManager

class SettingsActivity : AppCompatActivity() {
    
    private lateinit var preferenceManager: PreferenceManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        preferenceManager = PreferenceManager(this)
        
        setupClickListeners()
    }
    
    private fun setupClickListeners() {
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }
        
        findViewById<android.view.View>(R.id.btnSetPassword).setOnClickListener {
            showSetPasswordDialog()
        }
        
        findViewById<android.view.View>(R.id.btnChangePassword).setOnClickListener {
            showChangePasswordDialog()
        }
        
        val isPasswordSet = preferenceManager.isPasswordSet()
        findViewById<android.view.View>(R.id.btnSetPassword).visibility = if (isPasswordSet) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<android.view.View>(R.id.btnChangePassword).visibility = if (isPasswordSet) android.view.View.VISIBLE else android.view.View.GONE
    }
    
    private fun showSetPasswordDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Enter password (min 3 chars)"
        
        AlertDialog.Builder(this)
            .setTitle("Set Password")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val password = input.text.toString()
                if (password.length >= 3) {
                    preferenceManager.setMasterPassword(password)
                    preferenceManager.setPasswordSet(true)
                    Toast.makeText(this, "Password set", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Minimum 3 characters", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showChangePasswordDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Enter old password"
        
        AlertDialog.Builder(this)
            .setTitle("Change Password")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val oldPassword = input.text.toString()
                if (oldPassword == preferenceManager.getMasterPassword()) {
                    showNewPasswordDialog()
                } else {
                    Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showNewPasswordDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Enter new password (min 3 chars)"
        
        AlertDialog.Builder(this)
            .setTitle("New Password")
            .setView(input)
            .setPositiveButton("Change") { _, _ ->
                val newPassword = input.text.toString()
                if (newPassword.length >= 3) {
                    preferenceManager.setMasterPassword(newPassword)
                    Toast.makeText(this, "Password changed", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this, "Minimum 3 characters", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
