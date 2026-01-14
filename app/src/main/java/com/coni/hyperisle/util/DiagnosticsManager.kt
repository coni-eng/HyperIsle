package com.coni.hyperisle.util

import android.content.Context
import com.coni.hyperisle.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject



/**
 * Manages diagnostic sessions with JSONL file persistence.
 * 
 * - startSession() / stopSession() for session lifecycle
 * - writeEvent() persists events to a rolling JSONL file
 * - Rolling file capped at ~5 MB
 * - Export creates a zip with logs + basic config
 */
object DiagnosticsManager {

    private const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB
    private const val LOG_FILENAME = "hyperisle_diagnostics.jsonl"
    private const val CONFIG_FILENAME = "diagnostics_config.json"

    @Volatile
    var currentSessionId: String? = null
        private set

    @Volatile
    private var sessionStartTime: Long = 0L

    private var logFile: File? = null
    private var appContext: Context? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    /**
     * Initialize the manager with application context.
     * Call this once from Application.onCreate() or similar.
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        appContext = context.applicationContext
        logFile = File(context.filesDir, LOG_FILENAME)
    }

    /**
     * Start a new diagnostics session.
     * @return The new session ID, or null if not in debug mode.
     */
    fun startSession(): String? {
        if (!BuildConfig.DEBUG) return null
        
        val sessionId = UUID.randomUUID().toString().take(8)
        currentSessionId = sessionId
        sessionStartTime = System.currentTimeMillis()

        writeEvent(
            tag = "HI_SESSION",
            event = "session_start",
            fields = mapOf("session_id" to sessionId),
            level = "I"
        )

        return sessionId
    }

    /**
     * Stop the current diagnostics session.
     */
    fun stopSession() {
        if (!BuildConfig.DEBUG) return
        
        val sessionId = currentSessionId ?: return
        val duration = System.currentTimeMillis() - sessionStartTime

        writeEvent(
            tag = "HI_SESSION",
            event = "session_stop",
            fields = mapOf(
                "session_id" to sessionId,
                "duration_ms" to duration
            ),
            level = "I"
        )

        currentSessionId = null
        sessionStartTime = 0L
    }

    /**
     * Check if a session is currently active.
     */
    fun isSessionActive(): Boolean = currentSessionId != null

    /**
     * Write an event to the JSONL log file.
     */
    fun writeEvent(
        tag: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
        level: String = "D",
        throwable: Throwable? = null
    ) {
        if (!BuildConfig.DEBUG) return
        val file = logFile ?: return

        try {
            // Enforce rolling file size
            enforceMaxFileSize(file)

            val json = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("time", dateFormat.format(Date()))
                put("session", currentSessionId)
                put("tag", tag)
                put("level", level)
                put("event", event)
                
                if (fields.isNotEmpty()) {
                    val fieldsObj = JSONObject()
                    fields.forEach { (key, value) ->
                        fieldsObj.put(key, value)
                    }
                    put("fields", fieldsObj)
                }

                throwable?.let {
                    put("error", it.message)
                    put("stacktrace", it.stackTraceToString().take(500))
                }
            }

            synchronized(this) {
                file.appendText(json.toString() + "\n")
            }
        } catch (e: Exception) {
            // Silently fail - don't crash the app for logging
        }
    }

    /**
     * Enforce maximum file size by truncating old entries.
     */
    private fun enforceMaxFileSize(file: File) {
        if (!file.exists() || file.length() <= MAX_FILE_SIZE_BYTES) return

        try {
            // Simple strategy: keep last ~80% of file
            val targetSize = (MAX_FILE_SIZE_BYTES * 0.8).toLong()
            val skipBytes = file.length() - targetSize

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(skipBytes)
                // Find next newline to avoid partial line
                var b: Int
                while (raf.read().also { b = it } != -1) {
                    if (b == '\n'.code) break
                }

                val remaining = ByteArray((file.length() - raf.filePointer).toInt())
                raf.readFully(remaining)

                file.writeBytes(remaining)
            }
        } catch (e: Exception) {
            // If truncation fails, just delete and start fresh
            file.delete()
        }
    }

    /**
     * Get the current log file size in bytes.
     */
    fun getLogFileSize(): Long {
        if (!BuildConfig.DEBUG) return 0L
        return logFile?.length() ?: 0L
    }

    /**
     * Get the number of lines in the log file.
     */
    fun getLogLineCount(): Int {
        if (!BuildConfig.DEBUG) return 0
        val file = logFile ?: return 0
        if (!file.exists()) return 0
        
        return try {
            file.useLines { it.count() }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Clear all diagnostic logs.
     */
    fun clearLogs() {
        if (!BuildConfig.DEBUG) return
        logFile?.delete()
    }

    /**
     * Export diagnostics to a shareable zip file.
     * @return The zip file in cache directory, or null on failure.
     */
    fun exportToZip(context: Context): File? {
        if (!BuildConfig.DEBUG) return null

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(context.cacheDir, "hyperisle_diagnostics_$timestamp.zip")

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // Add log file
                logFile?.let { log ->
                    if (log.exists()) {
                        zos.putNextEntry(ZipEntry(LOG_FILENAME))
                        log.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                // Add config/metadata
                val config = generateConfigJson(context)
                zos.putNextEntry(ZipEntry(CONFIG_FILENAME))
                zos.write(config.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            return zipFile
        } catch (e: Exception) {
            return null
        }
    }

    private fun generateConfigJson(context: Context): String {
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }

        return JSONObject().apply {
            put("app_name", "HyperIsle")
            put("package_name", context.packageName)
            put("version_name", packageInfo?.versionName ?: "unknown")
            @Suppress("DEPRECATION")
            put("version_code", packageInfo?.longVersionCode ?: 0)
            put("export_time", dateFormat.format(Date()))
            put("android_sdk", android.os.Build.VERSION.SDK_INT)
            put("device", android.os.Build.MODEL)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("log_file_size_bytes", logFile?.length() ?: 0)
            put("current_session_id", currentSessionId)
        }.toString(2)
    }

    /**
     * Read recent log entries (last N lines).
     */
    fun readRecentLogs(maxLines: Int = 100): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        val file = logFile ?: return emptyList()
        if (!file.exists()) return emptyList()

        return try {
            file.useLines { lines ->
                lines.toList().takeLast(maxLines)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
