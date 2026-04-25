package com.dip83287.floatingbubble.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    
    fun isPasswordSet(): Boolean = prefs.getBoolean("password_set", false)
    fun setPasswordSet(set: Boolean) = prefs.edit().putBoolean("password_set", set).apply()
    
    fun getMasterPassword(): String? = prefs.getString("master_password", null)
    fun setMasterPassword(password: String) = prefs.edit().putString("master_password", password).apply()
}
