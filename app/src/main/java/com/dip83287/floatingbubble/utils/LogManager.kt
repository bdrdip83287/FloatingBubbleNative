package com.dip83287.floatingbubble.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class LogManager private constructor(context: Context) {

    companion object {
        private const val TAG = "UltraLogger"
        private const val LOG_DIR = "floating_notes_logs"
        private const val MAX_LOG_SIZE = 5 * 1024 * 1024
        private const val MAX_LOG_FILES = 5
        private const val PREFS_NAME = "log_prefs"
        private const val KEY_APP_VERSION = "app_version"

        @Volatile
        private var instance: LogManager? = null

        fun getInstance(context: Context): LogManager {
            return instance ?: synchronized(this) {
                instance ?: LogManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val logQueue = ConcurrentLinkedQueue<String>()
    private lateinit var logFile: File
    private val logDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    enum class LogLevel(val emoji: String, val priority: Int) {
        VERBOSE("📖", Log.VERBOSE), DEBUG("🐛", Log.DEBUG), INFO("ℹ️", Log.INFO),
        WARN("⚠️", Log.WARN), ERROR("❌", Log.ERROR), FATAL("💀", Log.ASSERT),
        FLOW("🔄", Log.INFO), CRASH("💥", Log.ERROR), STATE("📊", Log.INFO)
    }

    init {
        val logDir = File(appContext.filesDir, LOG_DIR)
        val currentVersion = getAppVersion()
        val savedVersion = prefs.getString(KEY_APP_VERSION, "")

        if (savedVersion != currentVersion) {
            cleanAllLogs(logDir)
            prefs.edit().putString(KEY_APP_VERSION, currentVersion).apply()
        }

        if (!logDir.exists()) logDir.mkdirs()

        logFile = File(logDir, "floating_notes_${fileDateFormat.format(Date())}.log")
        startLogWriter()
        cleanOldLogs()
        logDeviceInfo()
    }

    private fun cleanAllLogs(logDir: File) { logDir.listFiles()?.forEach { it.delete() } }
    private fun getAppVersion(): String = try {
        val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${pkgInfo.versionName} (${pkgInfo.versionCode})"
    } catch (e: Exception) { "Unknown" }

    private fun startLogWriter() {
        Thread {
            while (true) {
                try {
                    logQueue.poll()?.let { writeLogToFile(it) }
                    Thread.sleep(100)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }.start()
    }

    private fun writeLogToFile(log: String) = synchronized(this) {
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) rotateLogFile()
            FileWriter(logFile, true).use { it.write("$log\n"); it.flush() }
        } catch (e: Exception) { Log.e(TAG, "Failed to write log", e) }
    }

    private fun rotateLogFile() {
        try {
            val rotatedFile = File(logFile.parent, "floating_notes_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())}.log")
            logFile.renameTo(rotatedFile)
            cleanOldLogs()
        } catch (e: Exception) { Log.e(TAG, "Failed to rotate", e) }
    }

    private fun cleanOldLogs() {
        try {
            val logs = File(appContext.filesDir, LOG_DIR).listFiles { f -> f.name.startsWith("floating_notes_") } ?: return
            logs.sortedByDescending { it.lastModified() }.drop(MAX_LOG_FILES).forEach { it.delete() }
        } catch (e: Exception) { Log.e(TAG, "Failed to clean", e) }
    }

    private fun logDeviceInfo() {
        i("=== DEVICE INFO ===")
        i("Manufacturer: ${Build.MANUFACTURER}")
        i("Model: ${Build.MODEL}")
        i("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        i("App Version: ${getAppVersion()}")
        i("=== END ===")
    }

    private fun formatLog(level: LogLevel, tag: String, msg: String, th: Throwable? = null): String {
        val ts = logDateFormat.format(Date())
        val thread = Thread.currentThread().name
        val thStr = th?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        return "$ts ${level.emoji} [${level.name}] [$thread] [$tag] $msg$thStr"
    }

    fun v(tag: String, msg: String) { logQueue.add(formatLog(LogLevel.VERBOSE, tag, msg)); Log.v(TAG, msg) }
    fun d(tag: String, msg: String) { logQueue.add(formatLog(LogLevel.DEBUG, tag, msg)); Log.d(TAG, msg) }
    fun i(tag: String, msg: String) { logQueue.add(formatLog(LogLevel.INFO, tag, msg)); Log.i(TAG, msg) }
    fun w(tag: String, msg: String, th: Throwable? = null) { logQueue.add(formatLog(LogLevel.WARN, tag, msg, th)); Log.w(TAG, msg, th) }
    fun e(tag: String, msg: String, th: Throwable? = null) { logQueue.add(formatLog(LogLevel.ERROR, tag, msg, th)); Log.e(TAG, msg, th) }
    fun fatal(tag: String, msg: String, th: Throwable? = null) { logQueue.add(formatLog(LogLevel.FATAL, tag, msg, th)); Log.e(TAG, "FATAL: $msg", th) }
    fun flow(tag: String, msg: String) { logQueue.add(formatLog(LogLevel.FLOW, tag, "🔄 $msg")); Log.i(TAG, "FLOW: $msg") }
    fun state(tag: String, msg: String) { logQueue.add(formatLog(LogLevel.STATE, tag, "📊 $msg")); Log.i(TAG, "STATE: $msg") }
    fun crash(tag: String, msg: String, th: Throwable) { logQueue.add(formatLog(LogLevel.CRASH, tag, "💥 $msg", th)); Log.e(TAG, "CRASH: $msg", th) }

    fun getAllLogs(): String {
        val logs = StringBuilder()
        File(appContext.filesDir, LOG_DIR).listFiles { f -> f.name.startsWith("floating_notes_") }?.sortedBy { it.name }?.forEach { file ->
            try { logs.append("=== ${file.name} ===\n${file.readText()}\n\n") }
            catch (e: Exception) { logs.append("Failed to read ${file.name}: ${e.message}\n\n") }
        }
        return logs.toString()
    }

    fun clearLogs() { getLogFiles()?.forEach { it.delete() }; i("LogManager", "Logs cleared") }
    private fun getLogFiles() = File(appContext.filesDir, LOG_DIR).listFiles { f -> f.name.startsWith("floating_notes_") }
}
