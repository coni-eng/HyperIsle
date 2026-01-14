package com.coni.hyperisle.debug

import android.util.Log
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * DEBUG-ONLY UI Snapshot Logger for HyperIsle Island events.
 * 
 * Provides grep-friendly single-line logs under TAG "HyperIsleIsland" to answer:
 * 1) Did notification show as SYSTEM_NOTIFICATION, MIUI_ISLAND_BRIDGE, APP_OVERLAY, or IN_APP_UI?
 * 2) What was rendered in island/overlay (LEFT/CENTER/RIGHT slots, actions, style)?
 * 3) For calls: what was shown on phone screen + what was shown in island?
 * 4) RID correlation across the whole chain.
 * 
 * **Release behavior**: ALL methods are NO-OP. Zero overhead.
 * **Debug behavior**: Single-line structured logs for end-to-end tracing.
 * 
 * Log format:
 * RID=<rid> EVT=<evt> ROUTE=<route> TYPE=<type> PKG=<pkg> KEY=<keyHash> GK=<groupKey> SLOTS={L:...,C:...,R:...} ACTIONS=[...] STYLE=<style> REASON=<reason> EXTRA=<json-like>
 */
object IslandUiSnapshotLogger {

    private const val TAG = "HyperIsleIsland"

    // Counter for unique RID generation
    private val counter = AtomicLong(0)

    /**
     * Route enum - where the notification/event was displayed.
     */
    enum class Route {
        /** Shown in system notification shade only */
        SYSTEM_NOTIFICATION,
        /** Shown via MIUI/HyperOS island bridge notification */
        MIUI_ISLAND_BRIDGE,
        /** Shown via app's own TYPE_APPLICATION_OVERLAY */
        APP_OVERLAY,
        /** Shown via in-app UI (activity/fragment) */
        IN_APP_UI,
        /** Not shown anywhere (filtered/blocked) */
        IGNORED
    }

    /**
     * UI slot labels for island/overlay rendering.
     * Contains structural labels only (no PII).
     */
    data class UiSlots(
        val left: String? = null,
        val center: String? = null,
        val right: String? = null,
        val actions: List<String> = emptyList(),
        val style: String? = null
    ) {
        fun toLogString(): String {
            val l = left ?: "-"
            val c = center ?: "-"
            val r = right ?: "-"
            val actStr = if (actions.isEmpty()) "[]" else "[${actions.joinToString(",")}]"
            val styleStr = style ?: "-"
            return "SLOTS={L:$l,C:$c,R:$r} ACTIONS=$actStr STYLE=$styleStr"
        }
    }

    /**
     * Event context for correlation.
     * Contains only PII-safe identifiers.
     */
    data class EventCtx(
        val rid: String,
        val pkg: String?,
        val keyHash: String?,
        val groupKey: String?,
        val type: String,
        val category: String? = null,
        val importance: Int? = null,
        val isOngoing: Boolean? = null
    ) {
        fun toLogString(): String {
            val pkgStr = pkg ?: "-"
            val keyStr = keyHash ?: "-"
            val gkStr = groupKey ?: "-"
            return "TYPE=$type PKG=$pkgStr KEY=$keyStr GK=$gkStr"
        }
    }

    /**
     * Generates a unique Request ID for correlation.
     * Format: timestamp_base36 + counter_base36 (e.g., "lz4k2_1a")
     * 
     * @return RID string in debug builds, empty string in release
     */
    @JvmStatic
    fun rid(): String {
        if (!BuildConfig.DEBUG) return ""
        val ts = (System.currentTimeMillis() % 100000000).toString(36)
        val cnt = counter.incrementAndGet().toString(36)
        return "${ts}_$cnt"
    }

    /**
     * Log an island-related event with structured format.
     * NO-OP in release builds.
     * 
     * @param ctx Event context with RID and notification metadata
     * @param evt Event name (e.g., "NOTIF_POSTED", "NOTIF_DECISION", "ISLAND_RENDER")
     * @param route Where the event was routed to
     * @param reason Optional reason code
     * @param slots Optional UI slot labels
     * @param extra Optional extra key-value pairs
     */
    @JvmStatic
    fun logEvent(
        ctx: EventCtx,
        evt: String,
        route: Route,
        reason: String? = null,
        slots: UiSlots? = null,
        extra: Map<String, Any?> = emptyMap()
    ) {
        if (!BuildConfig.DEBUG) return

        val sb = StringBuilder()
        sb.append("RID=${ctx.rid} EVT=$evt ROUTE=${route.name} ")
        sb.append(ctx.toLogString())

        if (slots != null) {
            sb.append(" ")
            sb.append(slots.toLogString())
        }

        if (reason != null) {
            sb.append(" REASON=$reason")
        }

        if (extra.isNotEmpty()) {
            sb.append(" EXTRA={")
            sb.append(extra.entries.joinToString(",") { (k, v) ->
                "$k=${formatValue(v)}"
            })
            sb.append("}")
        }

        HiLog.d(HiLog.TAG_ISLAND, sb.toString())
    }

    /**
     * Create EventCtx from a StatusBarNotification.
     * Extracts PII-safe metadata only.
     */
    @JvmStatic
    fun ctxFromSbn(
        rid: String,
        sbn: android.service.notification.StatusBarNotification,
        type: String
    ): EventCtx {
        if (!BuildConfig.DEBUG) return EventCtx(rid = "", pkg = null, keyHash = null, groupKey = null, type = type)
        
        val notification = sbn.notification
        return EventCtx(
            rid = rid,
            pkg = sbn.packageName,
            keyHash = sbn.key.hashCode().toString(),
            groupKey = notification.group,
            type = type,
            category = notification.category,
            importance = null, // Would need NotificationManager to get this
            isOngoing = (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
        )
    }

    /**
     * Create EventCtx for synthetic events (no SBN).
     */
    @JvmStatic
    fun ctxSynthetic(
        rid: String,
        pkg: String?,
        type: String,
        keyHash: String? = null,
        groupKey: String? = null
    ): EventCtx {
        if (!BuildConfig.DEBUG) return EventCtx(rid = "", pkg = null, keyHash = null, groupKey = null, type = type)
        
        return EventCtx(
            rid = rid,
            pkg = pkg,
            keyHash = keyHash,
            groupKey = groupKey,
            type = type
        )
    }

    /**
     * Build UiSlots for standard notification island.
     */
    @JvmStatic
    fun slotsStandard(
        hasAppIcon: Boolean,
        hasTitle: Boolean,
        hasSubtitle: Boolean,
        hasBadge: Boolean,
        hasTime: Boolean,
        actionLabels: List<String> = emptyList(),
        style: String? = null
    ): UiSlots {
        if (!BuildConfig.DEBUG) return UiSlots()
        
        val left = if (hasAppIcon) "appIcon" else null
        val centerParts = mutableListOf<String>()
        if (hasTitle) centerParts.add("title")
        if (hasSubtitle) centerParts.add("subtitle")
        val center = if (centerParts.isNotEmpty()) centerParts.joinToString("+") else null
        
        val rightParts = mutableListOf<String>()
        if (hasBadge) rightParts.add("badge")
        if (hasTime) rightParts.add("time")
        val right = if (rightParts.isNotEmpty()) rightParts.joinToString("/") else null
        
        return UiSlots(left = left, center = center, right = right, actions = actionLabels, style = style)
    }

    /**
     * Build UiSlots for call island.
     */
    @JvmStatic
    fun slotsCall(
        hasAvatar: Boolean,
        hasCallerName: Boolean,
        hasTimer: Boolean,
        actionLabels: List<String> = emptyList(),
        isIncoming: Boolean,
        isOngoing: Boolean
    ): UiSlots {
        if (!BuildConfig.DEBUG) return UiSlots()
        
        val left = if (hasAvatar) "callerAvatar" else "appIcon"
        val centerParts = mutableListOf<String>()
        if (hasCallerName) centerParts.add("callerName")
        if (hasTimer) centerParts.add("callTimer")
        val center = if (centerParts.isNotEmpty()) centerParts.joinToString("+") else null
        
        val style = when {
            isIncoming -> "call_incoming"
            isOngoing -> "call_ongoing"
            else -> "call_ended"
        }
        
        return UiSlots(left = left, center = center, right = null, actions = actionLabels, style = style)
    }

    /**
     * Build UiSlots for progress island.
     */
    @JvmStatic
    fun slotsProgress(
        hasAppIcon: Boolean,
        hasTitle: Boolean,
        hasProgressBar: Boolean,
        progressPercent: Int?,
        style: String? = null
    ): UiSlots {
        if (!BuildConfig.DEBUG) return UiSlots()
        
        val left = if (hasAppIcon) "appIcon" else null
        val center = if (hasTitle) "title" else null
        val right = when {
            hasProgressBar && progressPercent != null -> "progress:$progressPercent%"
            hasProgressBar -> "progress:indeterminate"
            else -> null
        }
        
        return UiSlots(left = left, center = center, right = right, style = style ?: "progress")
    }

    /**
     * Build UiSlots for timer island.
     */
    @JvmStatic
    fun slotsTimer(
        hasTimerIcon: Boolean,
        hasTitle: Boolean,
        hasChronometer: Boolean,
        style: String? = null
    ): UiSlots {
        if (!BuildConfig.DEBUG) return UiSlots()
        
        val left = if (hasTimerIcon) "timerIcon" else "appIcon"
        val center = if (hasTitle) "title" else null
        val right = if (hasChronometer) "chronometer" else null
        
        return UiSlots(left = left, center = center, right = right, style = style ?: "timer")
    }

    /**
     * Build UiSlots for navigation island.
     */
    @JvmStatic
    fun slotsNavigation(
        hasNavIcon: Boolean,
        hasDirection: Boolean,
        hasDistance: Boolean,
        hasEta: Boolean,
        style: String? = null
    ): UiSlots {
        if (!BuildConfig.DEBUG) return UiSlots()
        
        val left = if (hasNavIcon) "navIcon" else "appIcon"
        val centerParts = mutableListOf<String>()
        if (hasDirection) centerParts.add("direction")
        if (hasDistance) centerParts.add("distance")
        val center = if (centerParts.isNotEmpty()) centerParts.joinToString("+") else null
        val right = if (hasEta) "eta" else null
        
        return UiSlots(left = left, center = center, right = right, style = style ?: "navigation")
    }

    /**
     * Build UiSlots for overlay pill.
     */
    @JvmStatic
    fun slotsOverlay(
        hasAvatar: Boolean,
        hasSender: Boolean,
        hasMessage: Boolean,
        hasTime: Boolean,
        overlayType: String
    ): UiSlots {
        if (!BuildConfig.DEBUG) return UiSlots()
        
        val left = if (hasAvatar) "avatar" else "appIcon"
        val centerParts = mutableListOf<String>()
        if (hasSender) centerParts.add("sender")
        if (hasMessage) centerParts.add("message")
        val center = if (centerParts.isNotEmpty()) centerParts.joinToString("+") else null
        val right = if (hasTime) "time" else null
        
        return UiSlots(left = left, center = center, right = right, style = "overlay_$overlayType")
    }

    /**
     * Check if logging is enabled.
     */
    @JvmStatic
    fun isEnabled(): Boolean = BuildConfig.DEBUG

    // --- Helpers ---

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value.take(50)
            is Number -> value.toString()
            is Boolean -> value.toString()
            is Collection<*> -> "[${value.size}]"
            is Map<*, *> -> "{${value.size}}"
            else -> value.toString().take(30)
        }
    }
}
