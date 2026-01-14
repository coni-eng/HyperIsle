package com.coni.hyperisle.util

import android.util.Log
import com.coni.hyperisle.BuildConfig
import java.security.MessageDigest



/**
 * HyperIsle logging wrapper with consistent tags and privacy protection.
 * 
 * - Logs to Logcat with HI_ prefixed tags
 * - Persists logs to JSONL via DiagnosticsManager when a session is active
 * - Never logs notification text or contact names
 * - Hashes notification keys for privacy
 */
object HiLog {

    // Tag constants
    const val TAG_ISLAND = "HI_ISLAND"
    const val TAG_INPUT = "HI_INPUT"
    const val TAG_NOTIF = "HI_NOTIF"
    const val TAG_STYLE = "HI_STYLE"
    const val TAG_CALL = "HI_CALL"
    const val TAG_PREF = "HI_PREF"
    const val TAG_PERF = "HI_PERF"

    /**
     * Log a debug message.
     */
    fun d(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        log(Log.DEBUG, tag, event, fields)
    }

    /**
     * Log an info message.
     */
    fun i(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        log(Log.INFO, tag, event, fields)
    }

    /**
     * Log a warning message.
     */
    fun w(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        log(Log.WARN, tag, event, fields)
    }

    /**
     * Log an error message.
     */
    fun e(tag: String, event: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) {
        log(Log.ERROR, tag, event, fields, throwable)
    }

    /**
     * Log a verbose message.
     */
    fun v(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        log(Log.VERBOSE, tag, event, fields)
    }

    private fun log(
        level: Int,
        tag: String,
        event: String,
        fields: Map<String, Any?>,
        throwable: Throwable? = null
    ) {
        if (!BuildConfig.DEBUG) return

        val sessionId = DiagnosticsManager.currentSessionId
        val fieldsStr = if (fields.isNotEmpty()) {
            fields.entries.joinToString(", ") { "${it.key}=${it.value}" }
        } else ""

        val message = if (fieldsStr.isNotEmpty()) "$event | $fieldsStr" else event

        // Log to Logcat
        when (level) {
            Log.VERBOSE -> Log.v(tag, message, throwable)
            Log.DEBUG -> Log.d(tag, message, throwable)
            Log.INFO -> Log.i(tag, message, throwable)
            Log.WARN -> Log.w(tag, message, throwable)
            Log.ERROR -> Log.e(tag, message, throwable)
        }

        // Persist to file if session is active
        if (sessionId != null) {
            DiagnosticsManager.writeEvent(
                tag = tag,
                event = event,
                fields = fields,
                level = levelToString(level),
                throwable = throwable
            )
        }
    }

    private fun levelToString(level: Int): String = when (level) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        else -> "?"
    }

    /**
     * Hash a notification key for privacy-safe logging.
     * Returns a short hex string (first 8 chars of SHA-256).
     */
    fun hashKey(key: String?): String? {
        if (key == null) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key.toByteArray(Charsets.UTF_8))
            hash.take(4).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            key.hashCode().toString(16)
        }
    }

    /**
     * Sanitize a map of fields to remove sensitive data.
     * Removes: text, title, content, name, contact, message
     */
    fun sanitize(fields: Map<String, Any?>): Map<String, Any?> {
        val sensitiveKeys = setOf("text", "title", "content", "name", "contact", "message", "body")
        return fields.filterKeys { key ->
            sensitiveKeys.none { sensitive -> key.lowercase().contains(sensitive) }
        }
    }
}