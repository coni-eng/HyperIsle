package com.coni.hyperisle.debug

import com.coni.hyperisle.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * MIUI-style runtime dump for Island state machine debugging.
 * 
 * Provides three ring buffers for tracking:
 * - Add/Remove events (island lifecycle)
 * - State transitions (UI state changes)
 * - Overlay events (iOS pill overlay)
 * 
 * **Release behavior**: ALL methods are NO-OP. Zero overhead.
 * **Debug behavior**: Thread-safe ring buffer storage with plain/JSON export.
 * 
 * Usage:
 * ```kotlin
 * IslandRuntimeDump.recordAdd(ctx, "NL_POSTED", mapOf("type" to "CALL"))
 * IslandRuntimeDump.recordState(ctx, IslandUiState.IDLE, IslandUiState.SHOWING_COMPACT)
 * val dump = IslandRuntimeDump.dumpToString()
 * ```
 */
object IslandRuntimeDump {

    private const val TAG = "HyperIsleIsland"

    // Ring buffer capacities
    private const val ADD_REMOVE_CAPACITY = 64
    private const val STATE_CAPACITY = 128
    private const val OVERLAY_CAPACITY = 64

    // Thread-safe ring buffers with read-write locks
    private val addRemoveLock = ReentrantReadWriteLock()
    private val stateLock = ReentrantReadWriteLock()
    private val overlayLock = ReentrantReadWriteLock()

    private val addRemoveHistory = ArrayDeque<IslandEventRecord>(ADD_REMOVE_CAPACITY)
    private val stateHistory = ArrayDeque<IslandStateRecord>(STATE_CAPACITY)
    private val overlayHistory = ArrayDeque<IslandOverlayRecord>(OVERLAY_CAPACITY)

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ==================== DATA CLASSES ====================

    /**
     * Record for island add/remove events.
     */
    data class IslandEventRecord(
        val ts: Long,
        val rid: String,
        val stage: String,
        val pkg: String,
        val notifKeyHash: Int,
        val groupKey: String?,
        val islandType: String?,
        val action: String,
        val reason: String?,
        val flagsJson: String?
    ) {
        fun toPlainString(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ts))
            val reasonPart = reason?.let { " reason=$it" } ?: ""
            val typePart = islandType?.let { " type=$it" } ?: ""
            val groupPart = groupKey?.let { " group=$it" } ?: ""
            return "[$time] RID=$rid STAGE=$stage ACTION=$action$reasonPart pkg=$pkg keyHash=$notifKeyHash$typePart$groupPart"
        }

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("ts", ts)
                put("rid", rid)
                put("stage", stage)
                put("pkg", pkg)
                put("notifKeyHash", notifKeyHash)
                groupKey?.let { put("groupKey", it) }
                islandType?.let { put("islandType", it) }
                put("action", action)
                reason?.let { put("reason", it) }
                flagsJson?.let { put("flags", it) }
            }
        }
    }

    /**
     * Record for island UI state transitions.
     */
    data class IslandStateRecord(
        val ts: Long,
        val rid: String,
        val groupKey: String,
        val prevState: String,
        val nextState: String,
        val rectInfo: String?,
        val animate: Boolean?,
        val blockAnim: Boolean?,
        val flagsJson: String?
    ) {
        fun toPlainString(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ts))
            val animPart = animate?.let { " animate=$it" } ?: ""
            val blockPart = blockAnim?.let { " blockAnim=$it" } ?: ""
            val rectPart = rectInfo?.let { " rect=$it" } ?: ""
            return "[$time] RID=$rid STATE $prevState -> $nextState group=$groupKey$animPart$blockPart$rectPart"
        }

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("ts", ts)
                put("rid", rid)
                put("groupKey", groupKey)
                put("prevState", prevState)
                put("nextState", nextState)
                rectInfo?.let { put("rectInfo", it) }
                animate?.let { put("animate", it) }
                blockAnim?.let { put("blockAnim", it) }
                flagsJson?.let { put("flags", it) }
            }
        }
    }

    /**
     * Record for overlay (iOS pill) events.
     */
    data class IslandOverlayRecord(
        val ts: Long,
        val rid: String,
        val action: String,
        val reason: String?,
        val pkg: String?,
        val overlayType: String?,
        val flagsJson: String?
    ) {
        fun toPlainString(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ts))
            val reasonPart = reason?.let { " reason=$it" } ?: ""
            val pkgPart = pkg?.let { " pkg=$it" } ?: ""
            val typePart = overlayType?.let { " type=$it" } ?: ""
            return "[$time] RID=$rid OVERLAY ACTION=$action$reasonPart$pkgPart$typePart"
        }

        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("ts", ts)
                put("rid", rid)
                put("action", action)
                reason?.let { put("reason", it) }
                pkg?.let { put("pkg", it) }
                overlayType?.let { put("overlayType", it) }
                flagsJson?.let { put("flags", it) }
            }
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Record an island add event.
     * NO-OP in release builds.
     */
    @JvmStatic
    fun recordAdd(
        ctx: ProcCtx?,
        reason: String?,
        flags: Map<String, Any?>? = null,
        groupKey: String? = null,
        islandType: String? = null
    ) {
        if (!BuildConfig.DEBUG) return
        val rid = ctx?.rid ?: DebugLog.rid()
        val record = IslandEventRecord(
            ts = System.currentTimeMillis(),
            rid = rid,
            stage = "ADD",
            pkg = ctx?.pkg ?: "unknown",
            notifKeyHash = ctx?.keyHash ?: 0,
            groupKey = groupKey,
            islandType = islandType,
            action = "ADD",
            reason = reason,
            flagsJson = flags?.let { mapToJson(it) }
        )
        addRemoveLock.write {
            if (addRemoveHistory.size >= ADD_REMOVE_CAPACITY) {
                addRemoveHistory.removeFirst()
            }
            addRemoveHistory.addLast(record)
        }
    }

    /**
     * Record an island remove event.
     * NO-OP in release builds.
     */
    @JvmStatic
    fun recordRemove(
        ctx: ProcCtx?,
        reason: String?,
        flags: Map<String, Any?>? = null,
        groupKey: String? = null,
        islandType: String? = null
    ) {
        if (!BuildConfig.DEBUG) return
        val rid = ctx?.rid ?: DebugLog.rid()
        val record = IslandEventRecord(
            ts = System.currentTimeMillis(),
            rid = rid,
            stage = "REMOVE",
            pkg = ctx?.pkg ?: "unknown",
            notifKeyHash = ctx?.keyHash ?: 0,
            groupKey = groupKey,
            islandType = islandType,
            action = "REMOVE",
            reason = reason,
            flagsJson = flags?.let { mapToJson(it) }
        )
        addRemoveLock.write {
            if (addRemoveHistory.size >= ADD_REMOVE_CAPACITY) {
                addRemoveHistory.removeFirst()
            }
            addRemoveHistory.addLast(record)
        }
    }

    /**
     * Record a generic island event (for stages like NL_POSTED, FILTER, TRANSLATE, etc.)
     * NO-OP in release builds.
     */
    @JvmStatic
    fun recordEvent(
        ctx: ProcCtx?,
        stage: String,
        action: String,
        reason: String? = null,
        flags: Map<String, Any?>? = null,
        groupKey: String? = null,
        islandType: String? = null
    ) {
        if (!BuildConfig.DEBUG) return
        val rid = ctx?.rid ?: DebugLog.rid()
        val record = IslandEventRecord(
            ts = System.currentTimeMillis(),
            rid = rid,
            stage = stage,
            pkg = ctx?.pkg ?: "unknown",
            notifKeyHash = ctx?.keyHash ?: 0,
            groupKey = groupKey,
            islandType = islandType,
            action = action,
            reason = reason,
            flagsJson = flags?.let { mapToJson(it) }
        )
        addRemoveLock.write {
            if (addRemoveHistory.size >= ADD_REMOVE_CAPACITY) {
                addRemoveHistory.removeFirst()
            }
            addRemoveHistory.addLast(record)
        }
    }

    /**
     * Record a state transition.
     * NO-OP in release builds.
     */
    @JvmStatic
    fun recordState(
        ctx: ProcCtx?,
        prevState: IslandUiState,
        nextState: IslandUiState,
        groupKey: String,
        rectInfo: String? = null,
        animate: Boolean? = null,
        blockAnim: Boolean? = null,
        flags: Map<String, Any?>? = null
    ) {
        if (!BuildConfig.DEBUG) return
        // Skip heartbeat (no state change)
        if (prevState == nextState) return
        
        val rid = ctx?.rid ?: DebugLog.rid()
        val record = IslandStateRecord(
            ts = System.currentTimeMillis(),
            rid = rid,
            groupKey = groupKey,
            prevState = prevState.name,
            nextState = nextState.name,
            rectInfo = rectInfo,
            animate = animate,
            blockAnim = blockAnim,
            flagsJson = flags?.let { mapToJson(it) }
        )
        stateLock.write {
            if (stateHistory.size >= STATE_CAPACITY) {
                stateHistory.removeFirst()
            }
            stateHistory.addLast(record)
        }
    }

    /**
     * Record an overlay event.
     * NO-OP in release builds.
     */
    @JvmStatic
    fun recordOverlay(
        ctx: ProcCtx?,
        action: String,
        reason: String? = null,
        pkg: String? = null,
        overlayType: String? = null,
        flags: Map<String, Any?>? = null
    ) {
        if (!BuildConfig.DEBUG) return
        val rid = ctx?.rid ?: DebugLog.rid()
        val record = IslandOverlayRecord(
            ts = System.currentTimeMillis(),
            rid = rid,
            action = action,
            reason = reason,
            pkg = pkg ?: ctx?.pkg,
            overlayType = overlayType,
            flagsJson = flags?.let { mapToJson(it) }
        )
        overlayLock.write {
            if (overlayHistory.size >= OVERLAY_CAPACITY) {
                overlayHistory.removeFirst()
            }
            overlayHistory.addLast(record)
        }
    }

    /**
     * Dump all buffers to plain text string (MIUI dump format).
     * Returns empty string in release builds.
     */
    @JvmStatic
    fun dumpToString(): String {
        if (!BuildConfig.DEBUG) return ""
        
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        
        sb.appendLine("========== HyperIsle Island Runtime Dump ==========")
        sb.appendLine("Dump Time: $now")
        sb.appendLine()
        
        // Add/Remove History
        sb.appendLine("--- Add/Remove History (${getAddRemoveCount()}/$ADD_REMOVE_CAPACITY) ---")
        addRemoveLock.read {
            addRemoveHistory.forEach { sb.appendLine(it.toPlainString()) }
        }
        sb.appendLine()
        
        // State History
        sb.appendLine("--- State History (${getStateCount()}/$STATE_CAPACITY) ---")
        stateLock.read {
            stateHistory.forEach { sb.appendLine(it.toPlainString()) }
        }
        sb.appendLine()
        
        // Overlay History
        sb.appendLine("--- Overlay History (${getOverlayCount()}/$OVERLAY_CAPACITY) ---")
        overlayLock.read {
            overlayHistory.forEach { sb.appendLine(it.toPlainString()) }
        }
        sb.appendLine()
        sb.appendLine("========== End Dump ==========")
        
        return sb.toString()
    }

    /**
     * Dump all buffers to JSON string.
     * Returns empty JSON object string in release builds.
     */
    @JvmStatic
    fun dumpToJson(): String {
        if (!BuildConfig.DEBUG) return "{}"
        
        return try {
            val json = JSONObject()
            json.put("dumpTime", System.currentTimeMillis())
            json.put("dumpTimeFormatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            
            // Add/Remove History
            val addRemoveArray = JSONArray()
            addRemoveLock.read {
                addRemoveHistory.forEach { addRemoveArray.put(it.toJson()) }
            }
            json.put("addRemoveHistory", addRemoveArray)
            json.put("addRemoveCount", addRemoveArray.length())
            json.put("addRemoveCapacity", ADD_REMOVE_CAPACITY)
            
            // State History
            val stateArray = JSONArray()
            stateLock.read {
                stateHistory.forEach { stateArray.put(it.toJson()) }
            }
            json.put("stateHistory", stateArray)
            json.put("stateCount", stateArray.length())
            json.put("stateCapacity", STATE_CAPACITY)
            
            // Overlay History
            val overlayArray = JSONArray()
            overlayLock.read {
                overlayHistory.forEach { overlayArray.put(it.toJson()) }
            }
            json.put("overlayHistory", overlayArray)
            json.put("overlayCount", overlayArray.length())
            json.put("overlayCapacity", OVERLAY_CAPACITY)
            
            json.toString(2)
        } catch (e: Exception) {
            // Fallback to plain text on JSON error
            "{\"error\": \"JSON serialization failed\", \"plainDump\": \"${dumpToString().replace("\"", "\\\"").replace("\n", "\\n")}\"}"
        }
    }

    /**
     * Clear all ring buffers.
     * NO-OP in release builds.
     */
    @JvmStatic
    fun clear() {
        if (!BuildConfig.DEBUG) return
        addRemoveLock.write { addRemoveHistory.clear() }
        stateLock.write { stateHistory.clear() }
        overlayLock.write { overlayHistory.clear() }
    }

    /**
     * Get current entry counts (for diagnostics UI).
     */
    @JvmStatic
    fun getAddRemoveCount(): Int {
        if (!BuildConfig.DEBUG) return 0
        return addRemoveLock.read { addRemoveHistory.size }
    }

    @JvmStatic
    fun getStateCount(): Int {
        if (!BuildConfig.DEBUG) return 0
        return stateLock.read { stateHistory.size }
    }

    @JvmStatic
    fun getOverlayCount(): Int {
        if (!BuildConfig.DEBUG) return 0
        return overlayLock.read { overlayHistory.size }
    }

    // ==================== HELPERS ====================

    private fun mapToJson(map: Map<String, Any?>): String {
        return try {
            val json = JSONObject()
            map.forEach { (key, value) ->
                when (value) {
                    null -> json.put(key, JSONObject.NULL)
                    is Number -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    else -> json.put(key, value.toString())
                }
            }
            json.toString()
        } catch (e: Exception) {
            "{}"
        }
    }
}

/**
 * Island UI state enum for deterministic state machine.
 * Matches MIUI StatusBarIslandControllerImpl states.
 */
enum class IslandUiState {
    /** No island visible */
    IDLE,
    /** Compact island shown (pill) */
    SHOWING_COMPACT,
    /** Expanded island shown (big island) */
    SHOWING_EXPANDED,
    /** Pinned ongoing notification (call, timer, nav) */
    PINNED_ONGOING,
    /** Suppressed by system (DND, focus mode, etc.) */
    SUPPRESSED_BY_SYSTEM
}
