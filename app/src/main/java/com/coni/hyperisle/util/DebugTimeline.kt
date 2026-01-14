package com.coni.hyperisle.util

import android.content.Context
import android.os.SystemClock
import com.coni.hyperisle.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject



/**
 * In-memory event timeline for island lifecycle troubleshooting.
 * 
 * This is a DEBUG-ONLY utility for tracking island events (show/expand/collapse,
 * notification posted/removed, call state transitions, actions, etc.).
 * When disabled (or in release builds), ALL methods are NO-OP with zero overhead.
 * 
 * PII-safe: Only logs packageName, keyHash, timestamps, reason codes, and booleans.
 * Never logs notification title/text or action labels (only hashed identifiers).
 * No disk I/O, no network calls.
 */
object DebugTimeline {

    @Volatile
    private var enabled: Boolean = false

    // Public data class for timeline entries (accessible for export)
    data class TimelineEntry(
        val elapsedRealtime: Long,
        val wallClockMs: Long,
        val eventName: String,
        val pkg: String?,
        val keyHash: Int?,
        val fields: Map<String, Any?>
    )

    private val ringBuffer = ArrayDeque<TimelineEntry>(MAX_BUFFER_SIZE)
    private const val MAX_BUFFER_SIZE = 200

    private val wallClockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Sets the enabled state. Only effective in debug builds.
     */
    fun setEnabled(value: Boolean) {
        if (!BuildConfig.DEBUG) return
        enabled = value
    }

    /**
     * Returns true if timeline is enabled AND this is a debug build.
     */
    fun isEnabled(): Boolean = BuildConfig.DEBUG && enabled

    /**
     * Logs an event to the timeline. NO-OP if disabled or release build.
     * 
     * @param eventName Event identifier (e.g., "onNotificationPosted", "islandShown")
     * @param pkg Package name (safe to log)
     * @param keyHash Hash of sbn.key or notification ID (safe to log, no PII)
     * @param fields Additional event-specific fields (must be PII-safe)
     */
    fun log(eventName: String, pkg: String? = null, keyHash: Int? = null, fields: Map<String, Any?> = emptyMap()) {
        if (!isEnabled()) return

        val entry = TimelineEntry(
            elapsedRealtime = SystemClock.elapsedRealtime(),
            wallClockMs = System.currentTimeMillis(),
            eventName = eventName,
            pkg = pkg,
            keyHash = keyHash,
            fields = fields
        )

        synchronized(ringBuffer) {
            if (ringBuffer.size >= MAX_BUFFER_SIZE) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(entry)
        }
    }

    /**
     * Returns entries filtered by time range.
     * @param sinceMs Only return entries from the last N milliseconds (0 = all entries)
     */
    fun getEntriesFiltered(sinceMs: Long = 0): List<TimelineEntry> {
        if (!BuildConfig.DEBUG) return emptyList()

        val cutoffTime = if (sinceMs > 0) System.currentTimeMillis() - sinceMs else 0L

        return synchronized(ringBuffer) {
            ringBuffer.filter { it.wallClockMs >= cutoffTime }.toList()
        }
    }

    /**
     * Returns a summary string with recent timeline entries.
     * @param timeRangeMs Time range in milliseconds (0 = all entries)
     */
    fun summary(timeRangeMs: Long = 0): String {
        if (!BuildConfig.DEBUG) return "Timeline unavailable in release builds"

        val filteredEntries = getEntriesFiltered(timeRangeMs)

        val sb = StringBuilder()
        sb.appendLine("=== Event Timeline ===")
        sb.appendLine("Enabled: $enabled")
        sb.appendLine("Entry Count: ${filteredEntries.size}")
        sb.appendLine()
        sb.appendLine("Events:")
        filteredEntries.forEachIndexed { index, entry ->
            val timeStr = wallClockFormat.format(Date(entry.wallClockMs))
            val fieldsStr = if (entry.fields.isNotEmpty()) {
                entry.fields.entries.joinToString(", ") { "${it.key}=${it.value}" }
            } else ""
            val pkgStr = entry.pkg?.let { " pkg=$it" } ?: ""
            val keyStr = entry.keyHash?.let { " keyHash=$it" } ?: ""
            sb.appendLine("  ${index + 1}. [$timeStr] ${entry.eventName}$pkgStr$keyStr $fieldsStr".trimEnd())
        }
        return sb.toString()
    }

    /**
     * Generates export content with app metadata and time range info.
     * @param context Application context (for session header)
     * @param appName Application name
     * @param versionName Version name
     * @param versionCode Build number
     * @param timeRangeMs Time range in milliseconds
     * @param timeRangeLabel Human-readable time range label
     * @param format Export format ("plain" or "json")
     */
    fun exportContent(
        context: Context,
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeMs: Long,
        timeRangeLabel: String,
        format: String = "plain"
    ): String {
        if (!BuildConfig.DEBUG) return "Export unavailable in release builds"

        return if (format == "json") {
            exportContentJson(context, appName, versionName, versionCode, timeRangeMs, timeRangeLabel)
        } else {
            exportContentPlain(context, appName, versionName, versionCode, timeRangeMs, timeRangeLabel)
        }
    }

    private fun exportContentPlain(
        context: Context,
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeMs: Long,
        timeRangeLabel: String
    ): String {
        val sb = StringBuilder()
        
        // DEBUG-ONLY: Prepend session header
        if (BuildConfig.DEBUG) {
            val sessionHeader = SessionHeaderHelper.generatePlainTextHeader(
                context = context,
                appName = appName,
                versionName = versionName,
                versionCode = versionCode,
                timeRangeLabel = timeRangeLabel,
                timelineEnabled = isEnabled()
            )
            sb.append(sessionHeader)
        }
        
        sb.appendLine("$appName Event Timeline Export")
        sb.appendLine("Version: $versionName (Build $versionCode)")
        sb.appendLine("Time Range: $timeRangeLabel")
        sb.appendLine("Export Format: Plain Text")
        sb.appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine()
        sb.appendLine(summary(timeRangeMs))
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("No notification content included. Only package names, key hashes, and reason codes.")
        return sb.toString()
    }

    private fun exportContentJson(
        context: Context,
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeMs: Long,
        timeRangeLabel: String
    ): String {
        val filteredEntries = getEntriesFiltered(timeRangeMs)

        val json = JSONObject()
        
        // DEBUG-ONLY: Add session header as top-level object
        if (BuildConfig.DEBUG) {
            val sessionHeader = SessionHeaderHelper.generateJsonHeader(
                context = context,
                appName = appName,
                versionName = versionName,
                versionCode = versionCode,
                timeRangeLabel = timeRangeLabel,
                timelineEnabled = isEnabled()
            )
            sessionHeader?.let { json.put("session", it) }
        }
        
        json.put("export_type", "event_timeline")
        json.put("app_name", appName)
        json.put("version_name", versionName)
        json.put("version_code", versionCode)
        json.put("time_range", timeRangeLabel)
        json.put("export_format", "JSON")
        json.put("exported_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        json.put("enabled", enabled)

        val timeline = JSONArray()
        filteredEntries.forEach { entry ->
            val entryObj = JSONObject()
            entryObj.put("ts", entry.wallClockMs)
            entryObj.put("elapsed_realtime", entry.elapsedRealtime)
            entryObj.put("time", wallClockFormat.format(Date(entry.wallClockMs)))
            entryObj.put("event", entry.eventName)
            entry.pkg?.let { entryObj.put("pkg", it) }
            entry.keyHash?.let { entryObj.put("keyHash", it) }
            
            if (entry.fields.isNotEmpty()) {
                val fieldsObj = JSONObject()
                entry.fields.forEach { (key, value) ->
                    fieldsObj.put(key, value)
                }
                entryObj.put("fields", fieldsObj)
            }
            
            timeline.put(entryObj)
        }
        json.put("timeline", timeline)
        json.put("entry_count", filteredEntries.size)

        json.put("privacy_note", "No notification content included. Only package names, key hashes, and reason codes.")

        return json.toString(2)
    }

    /**
     * Clears all entries from the ring buffer.
     */
    fun clear() {
        if (!BuildConfig.DEBUG) return
        synchronized(ringBuffer) {
            ringBuffer.clear()
        }
    }

    /**
     * Returns the current entry count (for diagnostics UI).
     */
    fun getEntryCount(): Int {
        if (!BuildConfig.DEBUG) return 0
        return synchronized(ringBuffer) { ringBuffer.size }
    }
}
