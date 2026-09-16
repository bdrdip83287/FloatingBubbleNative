#!/bin/bash

echo "=========================================="
echo "Google Play Store Compatible Fix"
echo "=========================================="
echo ""

# ============================================================
# FILE 1: AndroidManifest.xml (Storage permission বাদ)
# ============================================================
cat > app/src/main/AndroidManifest.xml << 'MANIFEST_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Overlay permission for all Android versions -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.SYSTEM_OVERLAY_WINDOW" />

    <!-- ✅ Storage permissions REMOVED for Play Store compatibility -->
    <!-- Google Drive Auto Backup will handle persistence -->

    <!-- Other permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".MyApp"
        android:allowBackup="true"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@drawable/ic_launcher_foreground"
        android:label="@string/app_name"
        android:roundIcon="@drawable/ic_launcher_foreground"
        android:supportsRtl="true"
        android:theme="@style/Theme.FloatingBubbleNative"
        android:usesCleartextTraffic="true"
        tools:targetApi="31">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".FloatingBubbleService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.foreground_service_type_special_use"
                android:value="floating_bubble" />
        </service>

        <!-- FileProvider for sharing files -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>
</manifest>
MANIFEST_EOF

echo "✅ AndroidManifest.xml - Storage permission বাদ দেওয়া হয়েছে"

# ============================================================
# FILE 2: backup_rules.xml (শুধু SharedPreferences)
# ============================================================
cat > app/src/main/res/xml/backup_rules.xml << 'BACKUP_EOF'
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <!-- ✅ Only backup SharedPreferences for Google Drive Auto Backup -->
    <include domain="sharedpref" path="bubble_prefs.xml" />
</full-backup-content>
BACKUP_EOF

echo "✅ backup_rules.xml - শুধু SharedPreferences"

# ============================================================
# FILE 3: data_extraction_rules.xml
# ============================================================
cat > app/src/main/res/xml/data_extraction_rules.xml << 'EXTRACT_EOF'
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <!-- ✅ Only backup SharedPreferences -->
        <include domain="sharedpref" path="bubble_prefs.xml" />
    </cloud-backup>
    <device-transfer>
        <!-- ✅ Only transfer SharedPreferences -->
        <include domain="sharedpref" path="bubble_prefs.xml" />
    </device-transfer>
</data-extraction-rules>
EXTRACT_EOF

echo "✅ data_extraction_rules.xml - শুধু SharedPreferences"

# ============================================================
# FILE 4: MainActivity.kt (Storage permission বাদ)
# ============================================================
cat > app/src/main/java/com/dip83287/floatingbubble/MainActivity.kt << 'MAIN_EOF'
package com.dip83287.floatingbubble

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dip83287.floatingbubble.utils.EmergencyLog

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmergencyLog.logLifecycle("MainActivity", "onCreate")

        // ✅ Only overlay permission needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                EmergencyLog.log("Overlay permission already granted")
                startBubbleService()
                finish()
            } else {
                EmergencyLog.log("Opening app settings page")
                openOverlaySettings()
            }
        } else {
            startBubbleService()
            finish()
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            Toast.makeText(
                this,
                "🔵 Go to 'Display over other apps' and enable permission",
                Toast.LENGTH_LONG
            ).show()
            EmergencyLog.log("Opened app details settings")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "openOverlaySettings")
            Toast.makeText(
                this,
                "Please manually enable overlay permission from Settings",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun startBubbleService() {
        try {
            val intent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            EmergencyLog.log("FloatingBubbleService started")
        } catch (e: Exception) {
            EmergencyLog.logException(e, "startBubbleService")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "✅ Overlay permission granted!", Toast.LENGTH_SHORT).show()
                        EmergencyLog.log("Overlay permission granted")
                        startBubbleService()
                        finish()
                    } else {
                        Toast.makeText(
                            this,
                            "❌ Please enable 'Display over other apps' permission",
                            Toast.LENGTH_LONG
                        ).show()
                        EmergencyLog.logError("Overlay permission still denied")
                        finish()
                    }
                } else {
                    finish()
                }
            }, 500)
        }
    }
}
MAIN_EOF

echo "✅ MainActivity.kt - শুধু Overlay permission"

# ============================================================
# FILE 5: build.gradle (version বাড়ানো)
# ============================================================
cat > app/build.gradle << 'GRADLE_EOF'
plugins {
    id 'com.android.application'
    id 'kotlin-android'
}

android {
    namespace 'com.dip83287.floatingbubble'
    compileSdk 34

    defaultConfig {
        applicationId "com.dip83287.floatingbubble"
        minSdk 23
        targetSdk 34
        versionCode 3
        versionName "1.2"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
        release {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }

    buildTypes {
        debug {
            signingConfig signingConfigs.debug
            minifyEnabled false
            debuggable true
        }
        release {
            signingConfig signingConfigs.release
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'com.google.code.gson:gson:2.10.1'
}
GRADLE_EOF

echo "✅ build.gradle - version 1.2"

echo ""
echo "=========================================="
echo "✅ সংস্কার সম্পন্ন!"
echo "=========================================="
echo ""
echo "পরিবর্তন:"
echo "  ✅ Storage permission সরানো হয়েছে"
echo "  ✅ শুধু Overlay permission"
echo "  ✅ Google Drive Auto Backup চালু"
echo "  ✅ Play Store-এ সাবমিট করা যাবে"
echo ""

