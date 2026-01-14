package com.coni.hyperisle.debug

import android.util.Log
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.util.HiLog
import java.util.concurrent.atomic.AtomicLong



/**
 * Debug-only logging system for HyperIsle notification flow tracing.
 * 
 * **Release behavior**: ALL methods are NO-OP. No logs, no string building, no performance cost.
 * **Debug behavior**: Logs to Logcat with structured event format for end-to-end tracing.
 * 
 * Log format:
 * [TS][EVT][RID=<rid>][TYPE=<type>][STEP=<step>][REASON=<reason>] message {key=value...}
 * 
 * Usage:
 * ```kotlin
 * val rid = DebugLog.rid()
 * DebugLog.event("NL_POSTED", rid, "RAW", kv = mapOf("pkg" to pkg, "id" to id))
 * DebugLog.event("FILTER_CHECK", rid, "FILTER", reason = "ALLOWED")
 * DebugLog.ex("MIUI_POST_FAIL", rid, "ERROR", exception, mapOf("bridgeId" to id))
 * ```
 */
object DebugLog {

    private const val TAG = "HYPERISLE"
    
    // Counter for unique RID generation
    private val counter = AtomicLong(0)
    
    // PII patterns for masking
    private val phonePattern = Regex("\\+?\\d{7,15}")
    private val emailPattern = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

    /**
     * Generates a short unique Request ID for correlation.
     * Format: timestamp_base36 + counter_base36 (e.g., "lz4k2_1a")
     */
    @JvmStatic
    fun rid(): String {
        if (!BuildConfig.DEBUG) return ""
        val ts = (System.currentTimeMillis() % 100000000).toString(36)
        val cnt = counter.incrementAndGet().toString(36)
        return "${ts}_$cnt"
    }

    /**
     * Log an event with structured format.
     * NO-OP in release builds.
     * 
     * @param step Step identifier (e.g., "NL_POSTED", "FILTER_CHECK", "MIUI_POST_OK")
     * @param rid Request ID for correlation across the flow
     * @param type Event type (e.g., "RAW", "FILTER", "TRANSLATE", "POST")
     * @param reason Optional reason code (e.g., "ALLOWED", "BLOCKED_NOT_SELECTED")
     * @param kv Key-value pairs for additional context (PII will be masked)
     */
    @JvmStatic
    fun event(
        step: String,
        rid: String,
        type: String,
        reason: String? = null,
        kv: Map<String, Any?> = emptyMap()
    ) {
        if (!BuildConfig.DEBUG) return
        
        val ts = System.currentTimeMillis()
        val reasonPart = if (reason != null) "[REASON=$reason]" else ""
        val kvPart = if (kv.isNotEmpty()) " {${formatKv(kv)}}" else ""
        
        val msg = "[$ts][EVT][RID=$rid][TYPE=$type][STEP=$step]$reasonPart$kvPart"
        HiLog.d(HiLog.TAG_ISLAND, msg)
    }

    /**
     * Log an exception event with structured format.
     * NO-OP in release builds.
     * 
     * @param step Step identifier where exception occurred
     * @param rid Request ID for correlation
     * @param type Event type
     * @param throwable The exception
     * @param kv Additional context
     */
    @JvmStatic
    fun ex(
        step: String,
        rid: String,
        type: String,
        throwable: Throwable,
        kv: Map<String, Any?> = emptyMap()
    ) {
        if (!BuildConfig.DEBUG) return
        
        val ts = System.currentTimeMillis()
        val exInfo = "${throwable.javaClass.simpleName}: ${throwable.message?.take(100) ?: "null"}"
        val kvPart = if (kv.isNotEmpty()) " {${formatKv(kv)}}" else ""
        
        val msg = "[$ts][EXC][RID=$rid][TYPE=$type][STEP=$step] $exInfo$kvPart"
        HiLog.e(HiLog.TAG_ISLAND, msg, emptyMap(), throwable)
    }

    // --- Standard log levels (for simpler logging needs) ---

    @JvmStatic
    fun v(message: String, kv: Map<String, Any?> = emptyMap()) {
        if (!BuildConfig.DEBUG) return
        val kvPart = if (kv.isNotEmpty()) " {${formatKv(kv)}}" else ""
        Log.v(TAG, "$message$kvPart")
    }

    @JvmStatic
    fun d(message: String, kv: Map<String, Any?> = emptyMap()) {
        if (!BuildConfig.DEBUG) return
        val kvPart = if (kv.isNotEmpty()) " {${formatKv(kv)}}" else ""
        HiLog.d(HiLog.TAG_ISLAND, "$message$kvPart")
    }

    @JvmStatic
    fun i(message: String, kv: Map<String, Any?> = emptyMap()) {
        if (!BuildConfig.DEBUG) return
        val kvPart = if (kv.isNotEmpty()) " {${formatKv(kv)}}" else ""
        HiLog.i(HiLog.TAG_ISLAND, "$message$kvPart")
    }

    @JvmStatic
    fun w(message: String, kv: Map<String, Any?> = emptyMap()) {
        if (!BuildConfig.DEBUG) return
        val kvPart = if (kv.isNotEmpty()) " {${formatKv(kv)}}" else ""
        HiLog.w(HiLog.TAG_ISLAND, "$message$kvPart")
    }

    @JvmStatic
    fun e(message: String, throwable: Throwable? = null, kv: Map<String, Any?> = emptyMap()) {
        if (!BuildConfig.DEBUG) return
        val kvPart = if (kv.isNotEmpty()) " {${formatKv(kv)}}" else ""
        if (throwable != null) {
            HiLog.e(HiLog.TAG_ISLAND, "$message$kvPart", emptyMap(), throwable)
        } else {
            HiLog.e(HiLog.TAG_ISLAND, "$message$kvPart")
        }
    }

    // --- Helpers ---

    /**
     * Format key-value pairs, masking PII and truncating long values.
     */
    private fun formatKv(kv: Map<String, Any?>): String {
        return kv.entries.joinToString(", ") { (key, value) ->
            val safeValue = when (value) {
                null -> "null"
                is String -> maskPii(truncate(value, 80))
                is Collection<*> -> "[${value.size} items]"
                is Map<*, *> -> "{${value.size} entries}"
                else -> value.toString().take(50)
            }
            "$key=$safeValue"
        }
    }

    /**
     * Truncate string to max length with ellipsis.
     */
    private fun truncate(s: String, maxLen: Int): String {
        return if (s.length > maxLen) s.take(maxLen - 3) + "..." else s
    }

    /**
     * Mask potential PII (phone numbers, emails).
     */
    private fun maskPii(s: String): String {
        var result = s
        // Mask phone numbers: keep last 2 digits
        result = phonePattern.replace(result) { match ->
            val num = match.value
            if (num.length > 2) "***${num.takeLast(2)}" else "***"
        }
        // Mask emails: keep first char and domain
        result = emailPattern.replace(result) { match ->
            val email = match.value
            val atIndex = email.indexOf('@')
            if (atIndex > 0) "${email.first()}***${email.substring(atIndex)}" else "***@***"
        }
        return result
    }

    /**
     * Check if debug logging is enabled.
     * Always false in release builds.
     */
    @JvmStatic
    fun isEnabled(): Boolean = BuildConfig.DEBUG

    /**
     * Create a lazy map builder to avoid allocation when logging is disabled.
     * Usage: DebugLog.event(..., kv = DebugLog.lazyKv { mapOf("key" to value) })
     */
    @JvmStatic
    inline fun lazyKv(crossinline builder: () -> Map<String, Any?>): Map<String, Any?> {
        return if (BuildConfig.DEBUG) builder() else emptyMap()
    }
}

/**
 * Processing context for notification flow correlation.
 * Carries the RID and basic notification metadata through the processing pipeline.
 * 
 * @param rid Request ID for log correlation
 * @param pkg Package name of the source app
 * @param key StatusBarNotification.key (nullable for synthetic events)
 * @param id StatusBarNotification.id
 * @param tag StatusBarNotification.tag (nullable)
 * @param whenMs Notification timestamp (notification.when)
 * @param category Notification category (nullable)
 * @param isOngoing Whether FLAG_ONGOING_EVENT is set
 * @param groupKey Notification group key (nullable)
 */
data class ProcCtx(
    val rid: String,
    val pkg: String,
    val key: String?,
    val id: Int,
    val tag: String?,
    val whenMs: Long,
    val category: String? = null,
    val isOngoing: Boolean = false,
    val groupKey: String? = null
) {
    /**
     * Hash of the key for PII-safe logging.
     */
    val keyHash: Int get() = key?.hashCode() ?: 0
    
    companion object {
        /**
         * Create a ProcCtx from a StatusBarNotification.
         * Generates a new RID automatically.
         */
        @JvmStatic
        fun from(sbn: android.service.notification.StatusBarNotification): ProcCtx {
            val notification = sbn.notification
            return ProcCtx(
                rid = DebugLog.rid(),
                pkg = sbn.packageName,
                key = sbn.key,
                id = sbn.id,
                tag = sbn.tag,
                whenMs = notification.`when`,
                category = notification.category,
                isOngoing = (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0,
                groupKey = notification.group
            )
        }
        
        /**
         * Create a synthetic ProcCtx for events without an SBN.
         */
        @JvmStatic
        fun synthetic(pkg: String, reason: String = "synthetic"): ProcCtx {
            return ProcCtx(
                rid = DebugLog.rid(),
                pkg = pkg,
                key = null,
                id = 0,
                tag = reason,
                whenMs = System.currentTimeMillis()
            )
        }
    }
}