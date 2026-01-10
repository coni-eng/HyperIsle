package com.coni.hyperisle.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.ActiveIsland
import com.coni.hyperisle.models.HyperIslandData
import com.coni.hyperisle.models.IslandLimitMode
import com.coni.hyperisle.models.MusicIslandMode
import com.coni.hyperisle.models.NotificationType
import com.coni.hyperisle.service.translators.CallTranslator
import com.coni.hyperisle.service.translators.NavTranslator
import com.coni.hyperisle.service.translators.ProgressTranslator
import com.coni.hyperisle.service.translators.StandardTranslator
import com.coni.hyperisle.service.translators.TimerTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalTime
import com.coni.hyperisle.data.db.AppDatabase
import com.coni.hyperisle.data.db.NotificationDigestItem
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.util.ContextStateManager
import com.coni.hyperisle.util.FocusActionHelper
import com.coni.hyperisle.util.Haptics
import com.coni.hyperisle.util.IslandActivityStateMachine
import com.coni.hyperisle.util.IslandCooldownManager
import com.coni.hyperisle.util.PriorityEngine
import com.coni.hyperisle.util.ActionDiagnostics
import com.coni.hyperisle.util.DebugTimeline
import com.coni.hyperisle.util.HiLog
import android.app.PendingIntent
import android.content.Intent
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.models.ShadeCancelMode
import com.coni.hyperisle.util.NotificationChannels
import com.coni.hyperisle.util.IslandStyleContract
import com.coni.hyperisle.service.IslandDecisionEngine
import com.coni.hyperisle.service.IslandKeyManager
import android.graphics.drawable.Icon
import com.coni.hyperisle.overlay.CallOverlayState
import com.coni.hyperisle.overlay.IosCallOverlayModel
import com.coni.hyperisle.overlay.IosNotificationOverlayModel
import com.coni.hyperisle.overlay.IosNotificationReplyAction
import com.coni.hyperisle.overlay.MediaAction
import com.coni.hyperisle.overlay.MediaOverlayModel
import com.coni.hyperisle.overlay.OverlayEventBus
import com.coni.hyperisle.overlay.TimerOverlayModel
import com.coni.hyperisle.util.AccessibilityContextState
import com.coni.hyperisle.util.ForegroundAppDetector
import com.coni.hyperisle.util.OverlayPermissionHelper
import com.coni.hyperisle.util.SystemHyperIslandPoster
import com.coni.hyperisle.util.getStringCompat
import com.coni.hyperisle.util.getStringCompatOrEmpty
import com.coni.hyperisle.debug.DebugLog
import com.coni.hyperisle.debug.IslandRuntimeDump
import com.coni.hyperisle.debug.IslandUiSnapshotLogger
import com.coni.hyperisle.debug.IslandUiState
import com.coni.hyperisle.debug.ProcCtx
import com.coni.hyperisle.util.toBitmap
import java.util.Locale

class NotificationReaderService : NotificationListenerService() {

    private val TAG = "HyperIsleService"
    private val ISLAND_CHANNEL_ID = "hyper_isle_island_channel"
    private val EXTRA_CHRONOMETER_COUNTDOWN = "android.chronometerCountDown"

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private enum class IslandRoute {
        MIUI_BRIDGE,
        APP_OVERLAY
    }

    // --- STATE ---
    private var allowedPackageSet: Set<String> = emptySet()
    private var currentMode = IslandLimitMode.MOST_RECENT
    private var appPriorityList = emptyList<String>()
    private val defaultTypePriorityOrder = listOf(
        NotificationType.CALL.name,
        NotificationType.NAVIGATION.name,
        NotificationType.TIMER.name,
        NotificationType.MEDIA.name,
        NotificationType.PROGRESS.name,
        NotificationType.STANDARD.name
    )
    private var typePriorityOrder: List<String> = defaultTypePriorityOrder

    // NEW: Cache for Global Blocked Terms (for synchronous check)
    private var globalBlockedTerms: Set<String> = emptySet()

    // Music Island Mode cache
    private var musicIslandMode = MusicIslandMode.SYSTEM_ONLY
    private var musicBlockApps: Set<String> = emptySet()

    // Smart Silence cache
    private var smartSilenceEnabled = true
    private var smartSilenceWindowMs = 10000L

    // Focus Automation cache
    private var focusEnabled = false
    private var focusQuietStart = "00:00"
    private var focusQuietEnd = "08:00"
    private var focusAllowedTypes: Set<String> = setOf("CALL", "TIMER")

    // Notification Summary cache
    private var summaryEnabled = false

    // Per-app mute/block cache
    private var perAppMuted: Set<String> = emptySet()
    private var perAppBlocked: Set<String> = emptySet()

    // Smart Priority cache
    private var smartPriorityEnabled = true
    private var smartPriorityAggressiveness = 1

    // Context-Aware Islands cache (v0.7.0)
    private var contextAwareEnabled = false
    private var contextScreenOffOnlyImportant = true
    private var contextScreenOffImportantTypes: Set<String> = setOf("CALL", "TIMER", "NAVIGATION")

    // Context Presets cache (v0.9.0)
    private var contextPreset = com.coni.hyperisle.models.ContextPreset.OFF
    private var useMiuiBridgeIsland = false

    // --- CACHES ---
    // Replace Policy: groupKey -> ActiveIsland (instead of sbn.key)
    private val activeIslands = ConcurrentHashMap<String, ActiveIsland>()
    private val activeTranslations = ConcurrentHashMap<String, Int>()
    private val activeRoutes = ConcurrentHashMap<String, IslandRoute>()
    private val lastUpdateMap = ConcurrentHashMap<String, Long>()
    // Smart Silence: groupKey -> (timestamp, contentHash)
    private val lastShownMap = ConcurrentHashMap<String, Pair<Long, Int>>()
    // Map original sbn.key to groupKey for cleanup
    private val sbnKeyToGroupKey = ConcurrentHashMap<String, String>()
    private val bridgePostConfirmations = ConcurrentHashMap<String, Long>()
    private val selfCancelKeys = ConcurrentHashMap<String, Long>()
    private val minVisibleUntil = ConcurrentHashMap<String, Long>()
    private val minVisibleJobs = ConcurrentHashMap<String, Job>()
    private val pendingDismissJobs = ConcurrentHashMap<String, Job>()

    // Call timer tracking: groupKey -> (startTime, timerJob)
    private val activeCallTimers = ConcurrentHashMap<String, Pair<Long, Job>>()
    private val callOverlayVisibility = ConcurrentHashMap<String, Boolean>()

    private lateinit var database: AppDatabase

    private val UPDATE_INTERVAL_MS = 200L
    private val MAX_ISLANDS = 9
    private val MAX_PRIORITY_APPS = 5
    private val BRIDGE_CONFIRM_TIMEOUT_MS = 250L
    private val BRIDGE_CONFIRM_POLL_MS = 50L
    private val MIN_VISIBLE_MS = 2500L
    private val SELF_CANCEL_WINDOW_MS = 5000L
    private val SYS_CANCEL_POST_DELAY_MS = 200L
    private val OVERLAY_DEFAULT_COLLAPSE_MS = 4000L
    private val SHADE_CANCEL_HINT_NOTIFICATION_ID = 9105
    private val SHADE_CANCEL_HINT_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    private val SHADE_CANCEL_HINT_PREFS = "shade_cancel_hint"

    private lateinit var preferences: AppPreferences
    private lateinit var callTranslator: CallTranslator
    private lateinit var navTranslator: NavTranslator
    private lateinit var timerTranslator: TimerTranslator
    private lateinit var progressTranslator: ProgressTranslator
    private lateinit var standardTranslator: StandardTranslator

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(applicationContext)
        createIslandChannel()

        callTranslator = CallTranslator(this)
        navTranslator = NavTranslator(this)
        timerTranslator = TimerTranslator(this)
        progressTranslator = ProgressTranslator(this)
        standardTranslator = StandardTranslator(this)

        // Observe settings
        serviceScope.launch { preferences.allowedPackagesFlow.collectLatest { allowedPackageSet = it } }
        serviceScope.launch { preferences.limitModeFlow.collectLatest { currentMode = it } }
        serviceScope.launch {
            preferences.appPriorityListFlow.collectLatest { list ->
                if (list.size > MAX_PRIORITY_APPS) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=LIMIT EVT=APP_PRIORITY_TRIM size=${list.size} max=$MAX_PRIORITY_APPS"
                    )
                }
                appPriorityList = list.take(MAX_PRIORITY_APPS)
            }
        }
        serviceScope.launch {
            preferences.typePriorityOrderFlow.collectLatest { order ->
                typePriorityOrder = order
                Log.d(
                    "HyperIsleIsland",
                    "RID=TYPE_ORDER EVT=TYPE_ORDER_UPDATE order=${order.joinToString("|")}"
                )
            }
        }

        // NEW: Observe Global Blocklist
        serviceScope.launch { preferences.globalBlockedTermsFlow.collectLatest { globalBlockedTerms = it } }

        // Music Island Mode observers
        serviceScope.launch { preferences.musicIslandModeFlow.collectLatest { musicIslandMode = it } }
        serviceScope.launch { preferences.musicBlockAppsFlow.collectLatest { musicBlockApps = it } }

        // Smart Silence observers
        serviceScope.launch { preferences.smartSilenceEnabledFlow.collectLatest { smartSilenceEnabled = it } }
        serviceScope.launch { preferences.smartSilenceWindowMsFlow.collectLatest { smartSilenceWindowMs = it } }

        // Focus Automation observers
        serviceScope.launch { preferences.focusEnabledFlow.collectLatest { focusEnabled = it } }
        serviceScope.launch { preferences.focusQuietStartFlow.collectLatest { focusQuietStart = it } }
        serviceScope.launch { preferences.focusQuietEndFlow.collectLatest { focusQuietEnd = it } }
        serviceScope.launch { preferences.focusAllowedTypesFlow.collectLatest { focusAllowedTypes = it } }

        // Summary observer
        serviceScope.launch { preferences.summaryEnabledFlow.collectLatest { summaryEnabled = it } }

        // Per-app mute/block observers
        serviceScope.launch { preferences.perAppMutedFlow.collectLatest { perAppMuted = it } }
        serviceScope.launch { preferences.perAppBlockedFlow.collectLatest { perAppBlocked = it } }

        // Smart Priority observers
        serviceScope.launch { preferences.smartPriorityEnabledFlow.collectLatest { smartPriorityEnabled = it } }
        serviceScope.launch { preferences.smartPriorityAggressivenessFlow.collectLatest { smartPriorityAggressiveness = it } }

        // Context-Aware Islands observers (v0.7.0)
        serviceScope.launch { preferences.contextAwareEnabledFlow.collectLatest { contextAwareEnabled = it } }
        serviceScope.launch { preferences.contextScreenOffOnlyImportantFlow.collectLatest { contextScreenOffOnlyImportant = it } }
        serviceScope.launch { preferences.contextScreenOffImportantTypesFlow.collectLatest { contextScreenOffImportantTypes = it } }

        // Context Presets observer (v0.9.0)
        serviceScope.launch { preferences.contextPresetFlow.collectLatest { contextPreset = it } }
        serviceScope.launch { preferences.useMiuiBridgeIslandFlow.collectLatest { useMiuiBridgeIsland = it } }

        // Initialize ContextStateManager
        ContextStateManager.initialize(applicationContext)

        // Database for digest
        database = AppDatabase.getDatabase(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "HyperIsle Connected")
        // INSTRUMENTATION: Service lifecycle
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=SVC_CONN STAGE=LIFECYCLE ACTION=CONNECTED")
            IslandRuntimeDump.recordEvent(null, "LIFECYCLE", "NL_CONNECTED", reason = "onListenerConnected")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // INSTRUMENTATION: Service lifecycle
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=SVC_DISC STAGE=LIFECYCLE ACTION=DISCONNECTED")
            IslandRuntimeDump.recordEvent(null, "LIFECYCLE", "NL_DISCONNECTED", reason = "onListenerDisconnected")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // INSTRUMENTATION: Service lifecycle
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=SVC_DEST STAGE=LIFECYCLE ACTION=DESTROYED")
            IslandRuntimeDump.recordEvent(null, "LIFECYCLE", "SERVICE_DESTROYED", reason = "onDestroy")
        }
        serviceScope.cancel()
        IslandKeyManager.clearAll()
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            // Generate correlation ID for this notification flow
            val ctx = ProcCtx.from(it)
            
            // CRITICAL: Log ALL notifications to catch WhatsApp/Telegram
            Log.d("HyperIsleIsland", "RID=${ctx.rid} EVT=NOTIF_RECEIVED pkg=${it.packageName} keyHash=${ctx.keyHash}")
            
            // STEP: NL_POSTED - Raw notification received
            DebugLog.event("NL_POSTED", ctx.rid, "RAW", kv = DebugLog.lazyKv {
                val extras = it.notification.extras
                mapOf(
                    "pkg" to it.packageName,
                    "sbnId" to it.id,
                    "tag" to it.tag,
                    "keyHash" to ctx.keyHash,
                    "isOngoing" to ((it.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0),
                    "isClearable" to it.isClearable,
                    "category" to it.notification.category,
                    "hasActions" to ((it.notification.actions?.size ?: 0) > 0),
                    "actionCount" to (it.notification.actions?.size ?: 0),
                    "hasFullScreenIntent" to (it.notification.fullScreenIntent != null)
                )
            })
            
            // UI Snapshot: NOTIF_POSTED - Initial intake
            if (BuildConfig.DEBUG) {
                val snapshotCtx = IslandUiSnapshotLogger.ctxFromSbn(ctx.rid, it, "UNKNOWN")
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "NOTIF_POSTED",
                    route = IslandUiSnapshotLogger.Route.IGNORED,
                    extra = mapOf(
                        "category" to it.notification.category,
                        "importance" to null,
                        "isOngoing" to ctx.isOngoing
                    )
                )
            }
            
            if (shouldIgnore(it.packageName)) {
                DebugLog.event("FILTER_CHECK", ctx.rid, "FILTER", reason = "BLOCKED_SYSTEM_PKG", kv = mapOf("pkg" to it.packageName))
                return
            }

            // Check Global Junk + Blocked Terms
            if (isJunkNotification(it, ctx)) return

            if (!isAppAllowed(it.packageName)) {
                DebugLog.event("FILTER_CHECK", ctx.rid, "FILTER", reason = "BLOCKED_NOT_SELECTED", kv = mapOf("pkg" to it.packageName, "allowedCount" to allowedPackageSet.size))
                return
            }
            
            DebugLog.event("FILTER_CHECK", ctx.rid, "FILTER", reason = "ALLOWED", kv = mapOf("pkg" to it.packageName))
            
            if (shouldSkipUpdate(it)) {
                DebugLog.event("DEDUP_CHECK", ctx.rid, "DEDUP", reason = "DROP_RATE_LIMIT", kv = mapOf("pkg" to it.packageName))
                return
            }
            
            serviceScope.launch { processAndPost(it, ctx) }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        sbn?.let {
            val key = it.key
            val keyHash = key.hashCode()
            val groupKey = sbnKeyToGroupKey[key]
            val reasonName = mapRemovalReason(reason)
            val now = System.currentTimeMillis()
            val route = groupKey?.let { keyValue -> activeRoutes[keyValue] }
            val isActiveIsland = route != null
            val selfCancel = reason == REASON_LISTENER_CANCEL && isSelfCancel(key, now)
            
            // Debug-only logging (PII-free)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "event=onRemoved pkg=${it.packageName} keyHash=${key.hashCode()} reason=$reasonName")
            }
            
            // Timeline: onNotificationRemoved event
            DebugTimeline.log(
                "onNotificationRemoved",
                it.packageName,
                key.hashCode(),
                mapOf("reason" to reasonName)
            )
            
            // UI Snapshot: NOTIF_REMOVED
            if (BuildConfig.DEBUG) {
                val lastRoute = when (route) {
                    IslandRoute.MIUI_BRIDGE -> IslandUiSnapshotLogger.Route.MIUI_ISLAND_BRIDGE
                    IslandRoute.APP_OVERLAY -> IslandUiSnapshotLogger.Route.APP_OVERLAY
                    else -> IslandUiSnapshotLogger.Route.SYSTEM_NOTIFICATION
                }
                val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                    rid = IslandUiSnapshotLogger.rid(),
                    pkg = it.packageName,
                    type = groupKey?.substringAfterLast(":") ?: "UNKNOWN",
                    keyHash = key.hashCode().toString(),
                    groupKey = groupKey
                )
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "NOTIF_REMOVED",
                    route = lastRoute,
                    reason = reasonName
                )
            }

            if (isActiveIsland) {
                val safeGroupKey = groupKey ?: return
                val activeRoute = route ?: return
                val remainingVisibleMs = remainingMinVisibleMs(safeGroupKey, now)
                val delayMs = when {
                    selfCancel -> if (remainingVisibleMs > 0L) remainingVisibleMs else MIN_VISIBLE_MS
                    remainingVisibleMs > 0L -> remainingVisibleMs
                    else -> 0L
                }
                val action = if (delayMs > 0L) "DELAY_HIDE" else "HIDE_NOW"

                Log.d(
                    "HyperIsleIsland",
                    "RID=$keyHash EVT=ON_REMOVED reason=$reasonName selfCancel=$selfCancel action=$action"
                )

                when (action) {
                    "DELAY_HIDE" -> {
                        scheduleDeferredDismiss(
                            pkg = it.packageName,
                            key = key,
                            keyHash = keyHash,
                            groupKey = safeGroupKey,
                            reasonName = reasonName,
                            reason = reason,
                            delayMs = delayMs,
                            route = activeRoute
                        )
                    }
                    else -> {
                        dismissIslandNow(
                            pkg = it.packageName,
                            key = key,
                            keyHash = keyHash,
                            groupKey = safeGroupKey,
                            reasonName = reasonName,
                            reason = reason,
                            route = activeRoute
                        )
                    }
                }
            } else {
                if (OverlayEventBus.emitDismiss(key)) {
                    Log.d("HyperIsleIsland", "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=NOTIF_REMOVED_$reasonName")
                }
            }
            sbnKeyToGroupKey.remove(key)
            bridgePostConfirmations.remove(key)
            selfCancelKeys.remove(key)

            if (!isActiveIsland && activeIslands.isEmpty()) {
                IslandCooldownManager.clearLastActiveIsland()
                if (OverlayEventBus.emitDismissAll()) {
                    Log.d("HyperIsleIsland", "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=ACTIVE_ISLANDS_EMPTY_$reasonName")
                }
                Log.d("HyperIsleIsland", "RID=$keyHash EVT=STATE_RESET_DONE reason=ACTIVE_ISLANDS_EMPTY_$reasonName")
            }
        }
    }
    
    private fun mapRemovalReason(reason: Int): String {
        return when (reason) {
            REASON_CLICK -> "CLICK"
            REASON_CANCEL -> "CANCEL"
            REASON_CANCEL_ALL -> "CANCEL_ALL"
            REASON_ERROR -> "ERROR"
            REASON_PACKAGE_CHANGED -> "PACKAGE_CHANGED"
            REASON_USER_STOPPED -> "USER_STOPPED"
            REASON_PACKAGE_BANNED -> "PACKAGE_BANNED"
            REASON_APP_CANCEL -> "APP_CANCEL"
            REASON_APP_CANCEL_ALL -> "APP_CANCEL_ALL"
            REASON_LISTENER_CANCEL -> "LISTENER_CANCEL"
            REASON_LISTENER_CANCEL_ALL -> "LISTENER_CANCEL_ALL"
            REASON_GROUP_SUMMARY_CANCELED -> "GROUP_SUMMARY_CANCELED"
            REASON_GROUP_OPTIMIZATION -> "GROUP_OPTIMIZATION"
            REASON_PACKAGE_SUSPENDED -> "PACKAGE_SUSPENDED"
            REASON_PROFILE_TURNED_OFF -> "PROFILE_TURNED_OFF"
            REASON_UNAUTOBUNDLED -> "UNAUTOBUNDLED"
            REASON_CHANNEL_BANNED -> "CHANNEL_BANNED"
            REASON_SNOOZED -> "SNOOZED"
            REASON_TIMEOUT -> "TIMEOUT"
            REASON_CHANNEL_REMOVED -> "CHANNEL_REMOVED"
            REASON_CLEAR_DATA -> "CLEAR_DATA"
            REASON_ASSISTANT_CANCEL -> "ASSISTANT_CANCEL"
            else -> "UNKNOWN($reason)"
        }
    }

    private fun markSelfCancel(key: String, keyHash: Int) {
        val now = System.currentTimeMillis()
        selfCancelKeys[key] = now
        Log.d("HyperIsleIsland", "RID=$keyHash EVT=SELF_CANCEL_MARK key=$keyHash rid=$keyHash ttlMs=$SELF_CANCEL_WINDOW_MS")
        serviceScope.launch {
            delay(SELF_CANCEL_WINDOW_MS)
            if (selfCancelKeys[key] == now) {
                selfCancelKeys.remove(key)
            }
        }
    }

    private fun isSelfCancel(key: String, now: Long): Boolean {
        val markTime = selfCancelKeys[key] ?: return false
        val isSelfCancel = now - markTime <= SELF_CANCEL_WINDOW_MS
        if (!isSelfCancel) {
            selfCancelKeys.remove(key)
        }
        return isSelfCancel
    }

    private fun remainingMinVisibleMs(groupKey: String, now: Long): Long {
        val until = minVisibleUntil[groupKey] ?: return 0L
        val remaining = until - now
        return if (remaining > 0L) remaining else 0L
    }

    private fun startMinVisibleTimer(groupKey: String, keyHash: Int) {
        val now = System.currentTimeMillis()
        minVisibleUntil[groupKey] = now + MIN_VISIBLE_MS
        minVisibleJobs[groupKey]?.cancel()
        Log.d("HyperIsleIsland", "RID=$keyHash EVT=MIN_VISIBLE_TIMER_START gk=$groupKey ms=$MIN_VISIBLE_MS")
        minVisibleJobs[groupKey] = serviceScope.launch {
            delay(MIN_VISIBLE_MS)
            Log.d("HyperIsleIsland", "RID=$keyHash EVT=MIN_VISIBLE_TIMER_END gk=$groupKey")
            minVisibleJobs.remove(groupKey)
        }
    }

    private fun scheduleDeferredDismiss(
        pkg: String,
        key: String,
        keyHash: Int,
        groupKey: String,
        reasonName: String,
        reason: Int,
        delayMs: Long,
        route: IslandRoute
    ) {
        pendingDismissJobs[groupKey]?.cancel()
        pendingDismissJobs[groupKey] = serviceScope.launch {
            delay(delayMs)
            pendingDismissJobs.remove(groupKey)
            Log.d("HyperIsleIsland", "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=MIN_VISIBLE_ELAPSED")
            dismissIslandNow(
                pkg = pkg,
                key = key,
                keyHash = keyHash,
                groupKey = groupKey,
                reasonName = reasonName,
                reason = reason,
                route = route
            )
        }
    }

    private fun dismissIslandNow(
        pkg: String,
        key: String,
        keyHash: Int,
        groupKey: String,
        reasonName: String,
        reason: Int,
        route: IslandRoute
    ) {
        val hyperId = activeTranslations[groupKey]
        if (route == IslandRoute.MIUI_BRIDGE && hyperId == null) return

        if (OverlayEventBus.emitDismiss(key)) {
            Log.d("HyperIsleIsland", "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=NOTIF_REMOVED_$reasonName")
        }

        // Debug-only: Log auto-dismiss for call islands
        if (BuildConfig.DEBUG && groupKey.endsWith(":CALL")) {
            val dismissReason = when (reason) {
                REASON_APP_CANCEL, REASON_CANCEL -> "CALL_ENDED"
                REASON_CLICK -> "DIALER_OPENED"
                else -> mapRemovalReason(reason)
            }
            Log.d(TAG, "event=autoDismiss reason=$dismissReason pkg=$pkg keyHash=$keyHash")
        }

        if (BuildConfig.DEBUG) {
            val islandType = groupKey.substringAfterLast(":")
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = pkg,
                type = islandType,
                keyHash = keyHash.toString(),
                groupKey = groupKey
            )
            if (route == IslandRoute.MIUI_BRIDGE) {
                Log.d("HyperIsleIsland", "RID=$keyHash STAGE=REMOVE ACTION=ISLAND_DISMISS reason=$reasonName pkg=$pkg type=$islandType")
                IslandRuntimeDump.recordRemove(
                    ctx = ProcCtx.synthetic(pkg, "onRemoved"),
                    reason = reasonName,
                    groupKey = groupKey,
                    islandType = islandType,
                    flags = mapOf("bridgeId" to hyperId, "removalReason" to reason)
                )
                // Record state transition to IDLE
                IslandRuntimeDump.recordState(
                    ctx = null,
                    prevState = IslandUiState.SHOWING_COMPACT,
                    nextState = IslandUiState.IDLE,
                    groupKey = groupKey,
                    flags = mapOf("trigger" to "onNotificationRemoved")
                )
            }
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "ISLAND_HIDE",
                route = if (route == IslandRoute.MIUI_BRIDGE) {
                    IslandUiSnapshotLogger.Route.MIUI_ISLAND_BRIDGE
                } else {
                    IslandUiSnapshotLogger.Route.APP_OVERLAY
                },
                reason = reasonName
            )
        }

        if (route == IslandRoute.MIUI_BRIDGE && hyperId != null) {
            try { NotificationManagerCompat.from(this).cancel(hyperId) } catch (e: Exception) {}
        }

        clearIslandState(groupKey)

        if (activeIslands.isEmpty()) {
            IslandCooldownManager.clearLastActiveIsland()
            if (OverlayEventBus.emitDismissAll()) {
                Log.d("HyperIsleIsland", "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=ACTIVE_ISLANDS_EMPTY_$reasonName")
            }
            Log.d("HyperIsleIsland", "RID=$keyHash EVT=STATE_RESET_DONE reason=ACTIVE_ISLANDS_EMPTY_$reasonName")
        }
    }

    private fun clearIslandState(groupKey: String) {
        activeIslands.remove(groupKey)
        activeTranslations.remove(groupKey)
        activeRoutes.remove(groupKey)
        lastUpdateMap.remove(groupKey)
        lastShownMap.remove(groupKey)
        minVisibleUntil.remove(groupKey)
        minVisibleJobs.remove(groupKey)?.cancel()
        pendingDismissJobs.remove(groupKey)?.cancel()
        activeCallTimers[groupKey]?.second?.cancel()
        activeCallTimers.remove(groupKey)
        callOverlayVisibility.remove(groupKey)
    }

    private fun shouldSkipUpdate(sbn: StatusBarNotification): Boolean {
        val key = sbn.key
        val now = System.currentTimeMillis()
        val lastTime = lastUpdateMap[key] ?: 0L
        val previousIsland = activeIslands[key]

        if (previousIsland == null) {
            lastUpdateMap[key] = now
            return false
        }

        val extras = sbn.notification.extras
        val currTitle = extras.getStringCompatOrEmpty(Notification.EXTRA_TITLE)
        val currText = extras.getStringCompatOrEmpty(Notification.EXTRA_TEXT)
        val currSub = extras.getStringCompatOrEmpty(Notification.EXTRA_SUB_TEXT)

        if (currTitle != previousIsland.title || currText != previousIsland.text || currSub != previousIsland.subText) {
            lastUpdateMap[key] = now
            return false
        }

        if (now - lastTime < UPDATE_INTERVAL_MS) return true

        lastUpdateMap[key] = now
        return false
    }

    private fun shouldForceShadeCancel(type: NotificationType): Boolean {
        return type == NotificationType.STANDARD || type == NotificationType.NAVIGATION
    }

    private fun cancelGroupSummaryIfNeeded(
        sbn: StatusBarNotification,
        type: NotificationType,
        keyHash: Int
    ) {
        if (!shouldForceShadeCancel(type)) return
        if (!NotificationChannels.isSafeToCancel(applicationContext)) {
            Log.d(
                "HyperIsleIsland",
                "RID=$keyHash EVT=GROUP_SUMMARY_CANCEL_SKIP reason=ISLAND_CHANNEL_DISABLED pkg=${sbn.packageName}"
            )
            return
        }
        try {
            cancelNotification(sbn.key)
            markSelfCancel(sbn.key, keyHash)
            Log.d(
                "HyperIsleIsland",
                "RID=$keyHash EVT=GROUP_SUMMARY_CANCEL_OK type=${type.name} pkg=${sbn.packageName}"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel group summary: ${e.message}")
            Log.d(
                "HyperIsleIsland",
                "RID=$keyHash EVT=GROUP_SUMMARY_CANCEL_FAIL reason=${e.javaClass.simpleName} pkg=${sbn.packageName}"
            )
        }
    }

    private fun isJunkNotification(sbn: StatusBarNotification, ctx: ProcCtx? = null): Boolean {
        val notification = sbn.notification
        val extras = notification.extras
        val pkg = sbn.packageName
        val rid = ctx?.rid ?: "N/A"

        val title = extras.getStringCompatOrEmpty(Notification.EXTRA_TITLE).trim()
        val text = extras.getStringCompatOrEmpty(Notification.EXTRA_TEXT).trim()
        val subText = extras.getStringCompatOrEmpty(Notification.EXTRA_SUB_TEXT).trim()

        // --- 1. CONTENT CHECKS ---
        if (title.isEmpty() && text.isEmpty() && subText.isEmpty()) {
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "EMPTY_CONTENT", kv = mapOf("pkg" to pkg))
            return true
        }

        // Package Name Leaks
        if (title.equals(pkg, ignoreCase = true) || text.equals(pkg, ignoreCase = true) || subText.equals(pkg, ignoreCase = true)) {
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "PKG_NAME_LEAK", kv = mapOf("pkg" to pkg))
            return true
        }
        if (title.contains("com.google.android", ignoreCase = true)) {
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "GOOGLE_PKG_LEAK", kv = mapOf("pkg" to pkg))
            return true
        }

        // *** GLOBAL BLOCKLIST CHECK ***
        if (globalBlockedTerms.isNotEmpty()) {
            val content = "$title $text"
            val matchedTerm = globalBlockedTerms.firstOrNull { term -> content.contains(term, ignoreCase = true) }
            if (matchedTerm != null) {
                DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "GLOBAL_BLOCKLIST", kv = mapOf("pkg" to pkg, "matchedTerm" to matchedTerm))
                return true
            }
        }

        // Placeholder Titles
        val appName = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "" }
        if (title == appName && text.isEmpty() && subText.isEmpty()) {
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "PLACEHOLDER_TITLE", kv = mapOf("pkg" to pkg))
            return true
        }

        // System Noise
        if (title.contains("running in background", true)) {
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "SYSTEM_NOISE_BACKGROUND", kv = mapOf("pkg" to pkg))
            return true
        }
        if (text.contains("tap for more info", true)) {
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "SYSTEM_NOISE_TAP_INFO", kv = mapOf("pkg" to pkg))
            return true
        }
        if (text.contains("displaying over other apps", true)) {
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "SYSTEM_NOISE_OVERLAY", kv = mapOf("pkg" to pkg))
            return true
        }

        // --- 2. GROUP SUMMARIES ---
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        Log.d("HyperIsleIsland", "RID=$rid EVT=GROUP_CHECK pkg=$pkg isGroupSummary=$isGroupSummary flags=${notification.flags}")
        
        if (isGroupSummary) {
            if (isAppAllowed(pkg)) {
                val type = inferNotificationType(sbn)
                val keyHash = ctx?.keyHash ?: sbn.key.hashCode()
                cancelGroupSummaryIfNeeded(sbn, type, keyHash)
            }
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "GROUP_SUMMARY", kv = mapOf("pkg" to pkg))
            Log.d("HyperIsleIsland", "RID=$rid EVT=BLOCKED_GROUP_SUMMARY pkg=$pkg")
            return true
        }

        // --- 3. PRIORITY PASS ---
        val hasProgress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val isSpecial = notification.category == Notification.CATEGORY_TRANSPORT ||
                notification.category == Notification.CATEGORY_CALL ||
                notification.category == Notification.CATEGORY_NAVIGATION ||
                extras.getStringCompatOrEmpty(Notification.EXTRA_TEMPLATE).contains("MediaStyle")

        if (hasProgress || isSpecial) return false

        return false
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun processAndPost(sbn: StatusBarNotification, ctx: ProcCtx) {
        try {
            val extras = sbn.notification.extras
            val rid = ctx.rid

            // Timeline: onNotificationPosted event (PII-safe)
            val isOngoingFlag = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val hasFullScreenIntent = sbn.notification.fullScreenIntent != null
            DebugTimeline.log(
                "onNotificationPosted",
                sbn.packageName,
                sbn.key.hashCode(),
                mapOf(
                    "category" to sbn.notification.category,
                    "isOngoing" to isOngoingFlag,
                    "hasFullScreenIntent" to hasFullScreenIntent
                )
            )

            val title = extras.getStringCompat(Notification.EXTRA_TITLE)?.trim()?.ifBlank { null } ?: sbn.packageName
            val text = extras.getStringCompatOrEmpty(Notification.EXTRA_TEXT).trim()
            val subText = extras.getStringCompatOrEmpty(Notification.EXTRA_SUB_TEXT).trim()

            // *** APP-SPECIFIC BLOCKLIST CHECK ***
            val appBlockedTerms = preferences.getAppBlockedTerms(sbn.packageName).first()
            if (appBlockedTerms.isNotEmpty()) {
                val content = "$title $text"
                if (appBlockedTerms.any { term -> content.contains(term, ignoreCase = true) }) {
                    // v0.9.1: Record suppressed notification to digest before returning
                    val type = inferNotificationType(sbn)
                    if (summaryEnabled && type != NotificationType.MEDIA) {
                        val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                        insertDigestItem(sbn.packageName, title, text, type.name, "APP_BLOCKLIST", keyHash)
                    }
                    return
                }
            }

            // Check if notification is ONGOING (Music, Calls, etc.) - these are preserved
            val isOngoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            
            val isCall = sbn.notification.category == Notification.CATEGORY_CALL ||
                    isDialerPackage(sbn.packageName)
            val isNavigation = sbn.notification.category == Notification.CATEGORY_NAVIGATION ||
                    sbn.packageName.contains("maps") || sbn.packageName.contains("waze")
            val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val hasProgress = progressMax > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
            val chronometerBase = sbn.notification.`when`
            val isTimer = (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ||
                    sbn.notification.category == Notification.CATEGORY_ALARM ||
                    sbn.notification.category == Notification.CATEGORY_STOPWATCH) && chronometerBase > 0
            val isMedia = extras.getStringCompatOrEmpty(Notification.EXTRA_TEMPLATE).contains("MediaStyle")

            val type = when {
                isCall -> NotificationType.CALL
                isNavigation -> NotificationType.NAVIGATION
                isTimer -> NotificationType.TIMER
                isMedia -> NotificationType.MEDIA
                hasProgress -> NotificationType.PROGRESS
                else -> NotificationType.STANDARD
            }

            val config = preferences.getAppConfig(sbn.packageName).first()
            if (!config.contains(type.name)) {
                // v0.9.1: Record suppressed notification to digest before returning
                if (summaryEnabled && type != NotificationType.MEDIA) {
                    val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                    insertDigestItem(sbn.packageName, title, text, type.name, "TYPE_CONFIG_DENY", keyHash)
                }
                return
            }

            // --- PER-APP BLOCK CHECK ---
            if (perAppBlocked.contains(sbn.packageName)) {
                if (summaryEnabled && type != NotificationType.MEDIA) {
                    val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                    insertDigestItem(sbn.packageName, title, text, type.name, "PER_APP_BLOCKED", keyHash)
                }
                return
            }

            // --- PER-APP MUTE CHECK (uses cooldown) ---
            if (perAppMuted.contains(sbn.packageName)) {
                if (summaryEnabled && type != NotificationType.MEDIA) {
                    val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                    insertDigestItem(sbn.packageName, title, text, type.name, "PER_APP_MUTED", keyHash)
                }
                return
            }

            // --- COOLDOWN CHECK (after explicit dismiss) ---
            if (IslandCooldownManager.isInCooldown(applicationContext, sbn.packageName, type.name)) {
                if (summaryEnabled && type != NotificationType.MEDIA) {
                    val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                    insertDigestItem(sbn.packageName, title, text, type.name, "COOLDOWN_DENY", keyHash)
                }
                return
            }

            if (isMedia) {
                emitMediaOverlayEvent(sbn, title, text, subText)
                when (musicIslandMode) {
                    MusicIslandMode.SYSTEM_ONLY -> return
                    MusicIslandMode.BLOCK_SYSTEM -> {
                        // Only cancel if NOT ongoing (preserve music player notifications)
                        if (musicBlockApps.contains(sbn.packageName) && !isOngoing) {
                            try { cancelNotification(sbn.key) } catch (e: Exception) {
                                Log.w(TAG, "Failed to cancel media notification: ${e.message}")
                            }
                        }
                        return
                    }
                }
            } else if (isTimer) {
                emitTimerOverlayEvent(sbn, title)
            }

            // --- SMART PRIORITY CHECK ---
            // v0.9.3: Context Presets integration with Smart Priority
            // - MEETING/DRIVING: CALL/TIMER/NAV bypass Smart Priority (never throttled)
            // - MEETING/DRIVING: STANDARD gets stricter burst threshold (presetBias=1)
            // - HEADPHONES: No bias (keep existing behavior, preset may block CALL separately)
            val sbnKeyHash = sbn.key.hashCode()
            val criticalTypes = setOf(NotificationType.CALL, NotificationType.TIMER, NotificationType.NAVIGATION)
            val isPresetActive = contextPreset == com.coni.hyperisle.models.ContextPreset.MEETING || 
                                 contextPreset == com.coni.hyperisle.models.ContextPreset.DRIVING
            
            // Bypass Smart Priority for critical types under MEETING/DRIVING
            val shouldBypassPriority = isPresetActive && type in criticalTypes
            
            // Apply conservative bias for STANDARD under MEETING/DRIVING
            val presetBias = if (isPresetActive && type == NotificationType.STANDARD) 1 else 0
            
            // v0.9.4: Get per-app Smart Priority profile (NORMAL/LENIENT/STRICT)
            val appProfile = preferences.getSmartPriorityProfile(sbn.packageName)
            
            val priorityDecision = if (shouldBypassPriority) {
                // Critical types bypass Smart Priority under MEETING/DRIVING
                PriorityEngine.allowPresetBypass(sbn.packageName, sbnKeyHash)
            } else {
                PriorityEngine.evaluate(
                    applicationContext,
                    preferences,
                    sbn.packageName,
                    type.name,
                    smartPriorityEnabled,
                    smartPriorityAggressiveness,
                    sbnKeyHash,
                    presetBias,
                    appProfile
                )
            }
            when (priorityDecision) {
                is PriorityEngine.Decision.BlockBurst -> {
                    // v0.9.1: Log to digest with suppression reason
                    if (summaryEnabled && type != NotificationType.MEDIA) {
                        val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                        val reason = if (presetBias > 0) "PRIORITY_BURST_PRESET" else "PRIORITY_BURST"
                        insertDigestItem(sbn.packageName, title, text, type.name, reason, keyHash)
                    }
                    return
                }
                is PriorityEngine.Decision.BlockThrottle -> {
                    // v0.9.1: Log to digest with suppression reason
                    if (summaryEnabled && type != NotificationType.MEDIA) {
                        val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                        val reason = if (presetBias > 0) "PRIORITY_THROTTLE_PRESET" else "PRIORITY_THROTTLE"
                        insertDigestItem(sbn.packageName, title, text, type.name, reason, keyHash)
                    }
                    return
                }
                is PriorityEngine.Decision.Allow -> { /* continue */ }
            }

            // --- CONTEXT PRESETS (v0.9.0) ---
            // Applied to non-media notifications only, before Focus mode
            // Focus Mode is the strongest override
            val isFocusActive = focusEnabled && isInQuietHours()
            if (!isFocusActive && contextPreset != com.coni.hyperisle.models.ContextPreset.OFF && type != NotificationType.MEDIA) {
                val shouldBlock = when (contextPreset) {
                    com.coni.hyperisle.models.ContextPreset.MEETING -> {
                        // Block STANDARD and PROGRESS, allow CALL, TIMER, NAVIGATION
                        type == NotificationType.STANDARD || type == NotificationType.PROGRESS
                    }
                    com.coni.hyperisle.models.ContextPreset.DRIVING -> {
                        // Block STANDARD and PROGRESS, allow CALL, TIMER, NAVIGATION
                        type == NotificationType.STANDARD || type == NotificationType.PROGRESS
                    }
                    com.coni.hyperisle.models.ContextPreset.HEADPHONES -> {
                        // Block CALL only (let user enjoy media/content without interruption)
                        type == NotificationType.CALL
                    }
                    com.coni.hyperisle.models.ContextPreset.OFF -> false
                }
                
                if (shouldBlock) {
                    // v0.9.1: Log to digest with suppression reason
                    if (summaryEnabled) {
                        val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                        insertDigestItem(sbn.packageName, title, text, type.name, "CONTEXT_PRESET_${contextPreset.name}", keyHash)
                    }
                    Log.d(TAG, "Context preset ${contextPreset.name}: Blocked ${type.name} for ${sbn.packageName}")
                    return
                }
            }

            // --- CONTEXT-AWARE FILTERING (v0.7.0) ---
            // Applied ONLY when contextAwareEnabled is true
            // Focus Mode is stronger - if focus is active, focus rules already applied above
            if (contextAwareEnabled && !isFocusActive) {
                val isScreenOn = ContextStateManager.getEffectiveScreenOn(applicationContext)
                
                // Screen OFF filtering: only allow important types
                if (!isScreenOn && contextScreenOffOnlyImportant) {
                    // Do NOT affect MediaStyle path (already returned above for media)
                    if (!contextScreenOffImportantTypes.contains(type.name)) {
                        // v0.9.1: Type not allowed when screen is off - skip island but log to digest
                        if (summaryEnabled) {
                            val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                            insertDigestItem(sbn.packageName, title, text, type.name, "CONTEXT_SCREEN_OFF", keyHash)
                        }
                        Log.d(TAG, "Context-aware: Blocked ${type.name} for ${sbn.packageName} (screen off)")
                        return
                    }
                }
            }

            // --- REPLACE POLICY: Use groupKey instead of sbn.key ---
            val groupKey = "${sbn.packageName}:${type.name}"
            val bridgeId = groupKey.hashCode()
            sbnKeyToGroupKey[sbn.key] = groupKey
            pendingDismissJobs.remove(groupKey)?.cancel()

            val isUpdate = activeIslands.containsKey(groupKey)
            
            // UI Snapshot: NOTIF_DECISION - Decision to show in island
            if (BuildConfig.DEBUG) {
                val snapshotCtx = IslandUiSnapshotLogger.ctxFromSbn(rid, sbn, type.name).copy(groupKey = groupKey)
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "NOTIF_DECISION",
                    route = IslandUiSnapshotLogger.Route.MIUI_ISLAND_BRIDGE,
                    reason = if (isUpdate) "UPDATE" else "NEW",
                    extra = mapOf("bridgeId" to bridgeId, "isUpdate" to isUpdate)
                )
            }

            // --- SMART SILENCE (anti-spam) for non-media ---
            val contentHash = "$title$text$subText${type.name}".hashCode()
            if (smartSilenceEnabled && type != NotificationType.MEDIA) {
                val lastShown = lastShownMap[groupKey]
                val now = System.currentTimeMillis()
                if (lastShown != null) {
                    val (lastTime, lastHash) = lastShown
                    if (now - lastTime < smartSilenceWindowMs && lastHash == contentHash) {
                        // v0.9.1: Same content within window - skip showing but still log to digest
                        if (summaryEnabled) {
                            val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                            insertDigestItem(sbn.packageName, title, text, type.name, "SMART_SILENCE", keyHash)
                        }
                        return
                    }
                }
                lastShownMap[groupKey] = now to contentHash
            }

            // --- FOCUS AUTOMATION ---
            // Note: isFocusActive already computed above for context-aware check
            if (isFocusActive && !focusAllowedTypes.contains(type.name)) {
                // v0.9.1: Type not allowed during focus - skip island but log to digest
                if (summaryEnabled && type != NotificationType.MEDIA) {
                    val keyHash = if (ActionDiagnostics.isEnabled()) sbn.key.hashCode() else null
                    insertDigestItem(sbn.packageName, title, text, type.name, "FOCUS_MODE_DENY", keyHash)
                }
                return
            }

            // --- INSERT DIGEST ITEM (for summary) ---
            if (summaryEnabled) {
                insertDigestItem(sbn.packageName, title, text, type.name)
            }

            // --- EARLY INTERCEPTION: Cancel clearable STANDARD notifications immediately ---
            // This "steals" the notification: shows it in Dynamic Island, cancels system popup
            // Scope: Only STANDARD type (messages, alerts). Does NOT affect PROGRESS, NAVIGATION, MEDIA, TIMER.
            if (type == NotificationType.STANDARD && sbn.isClearable && !isOngoing) {
                val contentIntent = sbn.notification.contentIntent
                if (contentIntent != null) {
                    val bridgeIdForIntent = groupKey.hashCode()
                    IslandCooldownManager.setContentIntent(bridgeIdForIntent, contentIntent)
                }
                
                // Cancel notification immediately to prevent system heads-up popup
                try {
                    cancelNotification(sbn.key)
                    markSelfCancel(sbn.key, sbn.key.hashCode())
                    DebugLog.event("EARLY_INTERCEPT", rid, "INTERCEPT", reason = "CLEARABLE_STANDARD_CANCEL_OK", kv = mapOf(
                        "pkg" to sbn.packageName,
                        "isClearable" to sbn.isClearable,
                        "isOngoing" to isOngoing
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel clearable notification: ${e.message}")
                    DebugLog.event("EARLY_INTERCEPT", rid, "INTERCEPT", reason = "CLEARABLE_STANDARD_CANCEL_FAIL", kv = mapOf(
                        "pkg" to sbn.packageName,
                        "error" to (e.message ?: "unknown")
                    ))
                }
                // Continue processing to show in Dynamic Island (don't return)
            }

            // --- CALL INTERCEPTION: Suppress system heads-up for ALL calls (incoming + ongoing) ---
            // Shows call UI only in Dynamic Island, cancels/snoozes system popup
            // - Incoming calls: Full-screen island with accept/decline buttons
            // - Ongoing calls: Collapsed (small) island with timer
            // WARNING: On some devices, cancelling call notification may affect ringtone.
            // If ringtone stops during incoming calls, consider using snoozeNotification instead.
            if (type == NotificationType.CALL) {
                val contentIntent = sbn.notification.contentIntent
                if (contentIntent != null) {
                    val bridgeIdForIntent = groupKey.hashCode()
                    IslandCooldownManager.setContentIntent(bridgeIdForIntent, contentIntent)
                }
                
                val callState = if (isOngoing) "ONGOING" else "INCOMING"
                
                // Try to suppress system call popup
                // Using snooze as primary method for incoming (safer, preserves ringtone)
                // Using cancel for ongoing (no ringtone concern)
                if (isOngoing) {
                    // Ongoing call: safe to cancel directly
                    try {
                        cancelNotification(sbn.key)
                        markSelfCancel(sbn.key, sbn.key.hashCode())
                        DebugLog.event("CALL_INTERCEPT", rid, "INTERCEPT", reason = "CALL_ONGOING_CANCEL_OK", kv = mapOf(
                            "pkg" to sbn.packageName,
                            "callState" to callState,
                            "method" to "cancel"
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cancel ongoing call notification: ${e.message}")
                        DebugLog.event("CALL_INTERCEPT", rid, "INTERCEPT", reason = "CALL_ONGOING_CANCEL_FAIL", kv = mapOf(
                            "pkg" to sbn.packageName,
                            "callState" to callState,
                            "error" to (e.message ?: "unknown")
                        ))
                    }
                } else {
                    // Incoming call: use snooze first (preserves ringtone)
                    try {
                        // Snooze for 60 seconds - call will either be answered/declined by then
                        snoozeNotification(sbn.key, 60_000L)
                        markSelfCancel(sbn.key, sbn.key.hashCode())
                        DebugLog.event("CALL_INTERCEPT", rid, "INTERCEPT", reason = "CALL_INCOMING_SNOOZE_OK", kv = mapOf(
                            "pkg" to sbn.packageName,
                            "callState" to callState,
                            "method" to "snooze",
                            "durationMs" to 60_000L
                        ))
                    } catch (e: Exception) {
                        // Fallback: try cancelNotification if snooze fails
                        // NOTE: This may stop ringtone on some devices - test carefully!
                        Log.w(TAG, "Snooze failed for incoming call, trying cancel: ${e.message}")
                        try {
                            cancelNotification(sbn.key)
                            markSelfCancel(sbn.key, sbn.key.hashCode())
                            DebugLog.event("CALL_INTERCEPT", rid, "INTERCEPT", reason = "CALL_INCOMING_CANCEL_FALLBACK", kv = mapOf(
                                "pkg" to sbn.packageName,
                                "callState" to callState,
                                "method" to "cancel"
                            ))
                        } catch (e2: Exception) {
                            Log.w(TAG, "Failed to suppress incoming call notification: ${e2.message}")
                            DebugLog.event("CALL_INTERCEPT", rid, "INTERCEPT", reason = "CALL_INCOMING_SUPPRESS_FAIL", kv = mapOf(
                                "pkg" to sbn.packageName,
                                "callState" to callState,
                                "error" to (e2.message ?: "unknown")
                            ))
                        }
                    }
                }
                // Continue processing to show call UI in Dynamic Island (don't return)
            }

            // --- MAX_ISLANDS check with replacement ---
            if (!isUpdate && activeIslands.size >= MAX_ISLANDS) {
                handleLimitReached(type, sbn.packageName)
                if (activeIslands.size >= MAX_ISLANDS) return
            }

            val picKey = "pic_${bridgeId}"

            val appIslandConfig = preferences.getAppIslandConfig(sbn.packageName).first()
            val globalConfig = preferences.globalConfigFlow.first()
            var finalConfig = appIslandConfig.mergeWith(globalConfig)

            // --- FOCUS OVERRIDE: Reduce island visibility during quiet hours ---
            if (isFocusActive) {
                finalConfig = IslandConfig(
                    isFloat = false,
                    isShowShade = false,
                    timeout = 3000L
                )
            }

            // v0.9.7: Detect if call is ongoing (for calls-only-island feature)
            val isOngoingCall = if (type == NotificationType.CALL) {
                val isChronometerShown = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
                val isOngoingFlag = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                val actions = sbn.notification.actions ?: emptyArray()
                val answerKeywords = resources.getStringArray(R.array.call_keywords_answer).toList()
                val hasAnswerAction = actions.any { action ->
                    val txt = action.title.toString().lowercase(java.util.Locale.getDefault())
                    answerKeywords.any { k -> txt.contains(k) }
                }
                !hasAnswerAction && (isChronometerShown || isOngoingFlag)
            } else false

            // --- ISLAND STYLE CONTRACT ---
            // Resolve style and block legacy action-row layouts
            val actionCount = sbn.notification.actions?.size ?: 0
            val styleResult = IslandStyleContract.resolveStyle(sbn, type, actionCount)
            
            // Log style selection (deterministic, logged each time an island is shown)
            IslandStyleContract.logStyleSelected(sbn, styleResult)

            // STEP: TRANSLATOR_PICK - Log which translator will be used
            DebugLog.event("TRANSLATOR_PICK", rid, "TRANSLATE", reason = type.name, kv = mapOf(
                "pkg" to sbn.packageName,
                "isOngoingCall" to isOngoingCall,
                "actionCount" to actionCount
            ))

            var callDurationSeconds: Long? = null
            val data: HyperIslandData = when (type) {
                NotificationType.CALL -> {
                    // Calculate duration for ongoing calls
                    val durationSeconds = if (isOngoingCall) {
                        val existingTimer = activeCallTimers[groupKey]
                        if (existingTimer != null) {
                            // Use existing start time
                            (System.currentTimeMillis() - existingTimer.first) / 1000
                        } else {
                            // New ongoing call - start timer
                            val startTime = System.currentTimeMillis()
                            activeCallTimers[groupKey] = startTime to startCallTimer(sbn, groupKey, picKey, finalConfig, startTime)
                            0L
                        }
                    } else {
                        // Incoming call or call ended - stop timer if exists
                        activeCallTimers[groupKey]?.second?.cancel()
                        activeCallTimers.remove(groupKey)
                        null
                    }
                    callDurationSeconds = durationSeconds

                    callTranslator.translate(sbn, picKey, finalConfig, durationSeconds)
                }
                NotificationType.NAVIGATION -> {
                    val navLayout = preferences.getEffectiveNavLayout(sbn.packageName).first()
                    navTranslator.translate(sbn, picKey, finalConfig, navLayout.first, navLayout.second)
                }
                NotificationType.TIMER -> timerTranslator.translate(sbn, picKey, finalConfig)
                NotificationType.PROGRESS -> progressTranslator.translate(sbn, title, picKey, finalConfig)
                else -> standardTranslator.translate(sbn, picKey, finalConfig, bridgeId, styleResult)
            }

            // STEP: TRANSLATOR_RESULT - Log translation output summary
            DebugLog.event("TRANSLATOR_RESULT", rid, "TRANSLATE", kv = DebugLog.lazyKv {
                mapOf(
                    "islandType" to type.name,
                    "jsonLength" to data.jsonParam.length,
                    "jsonHash" to data.jsonParam.hashCode().toString(16).takeLast(8),
                    "pkg" to sbn.packageName
                )
            })

            // UI Snapshot: ISLAND_RENDER - Island payload created, about to render
            if (BuildConfig.DEBUG) {
                val slots = when (type) {
                    NotificationType.CALL -> IslandUiSnapshotLogger.slotsCall(
                        hasAvatar = true,
                        hasCallerName = title.isNotEmpty(),
                        hasTimer = isOngoingCall,
                        actionLabels = (sbn.notification.actions ?: emptyArray()).take(2).mapNotNull { it.title?.toString()?.take(10) },
                        isIncoming = !isOngoingCall,
                        isOngoing = isOngoingCall
                    )
                    NotificationType.PROGRESS -> IslandUiSnapshotLogger.slotsProgress(
                        hasAppIcon = true,
                        hasTitle = title.isNotEmpty(),
                        hasProgressBar = true,
                        progressPercent = extras.getInt(Notification.EXTRA_PROGRESS, 0).takeIf { extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 }?.let { p -> 
                            (p * 100) / extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100) 
                        },
                        style = styleResult.style.name
                    )
                    NotificationType.TIMER -> IslandUiSnapshotLogger.slotsTimer(
                        hasTimerIcon = true,
                        hasTitle = title.isNotEmpty(),
                        hasChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
                        style = "timer"
                    )
                    NotificationType.NAVIGATION -> IslandUiSnapshotLogger.slotsNavigation(
                        hasNavIcon = true,
                        hasDirection = true,
                        hasDistance = text.isNotEmpty(),
                        hasEta = subText.isNotEmpty(),
                        style = "navigation"
                    )
                    else -> IslandUiSnapshotLogger.slotsStandard(
                        hasAppIcon = true,
                        hasTitle = title.isNotEmpty(),
                        hasSubtitle = text.isNotEmpty(),
                        hasBadge = false,
                        hasTime = true,
                        actionLabels = (sbn.notification.actions ?: emptyArray()).take(3).mapNotNull { it.title?.toString()?.take(10) },
                        style = styleResult.style.name
                    )
                }
                val snapshotCtx = IslandUiSnapshotLogger.ctxFromSbn(rid, sbn, type.name).copy(groupKey = groupKey)
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "ISLAND_RENDER",
                    route = IslandUiSnapshotLogger.Route.MIUI_ISLAND_BRIDGE,
                    slots = slots,
                    extra = mapOf("bridgeId" to bridgeId, "jsonLen" to data.jsonParam.length)
                )
            }

            val newContentHash = data.jsonParam.hashCode()
            val previousIsland = activeIslands[groupKey]

            if (isUpdate && previousIsland != null) {
                if (previousIsland.lastContentHash == newContentHash) return
            }

            // --- LIVE ACTIVITY STATE MACHINE ---
            val progress = if (type == NotificationType.PROGRESS) {
                val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                val progressCurrent = extras.getInt(Notification.EXTRA_PROGRESS, 0)
                if (progressMax > 0) ((progressCurrent.toFloat() / progressMax.toFloat()) * 100).toInt() else null
            } else null

            val activityResult = IslandActivityStateMachine.processUpdate(groupKey, bridgeId, progress, contentHash)

            val overlaySupported = isOverlaySupported(type, isOngoingCall)
            val canUseOverlay = overlaySupported && OverlayPermissionHelper.hasOverlayPermission(applicationContext)
            val forceOverlayRoute = type == NotificationType.STANDARD || type == NotificationType.NAVIGATION
            val preferOverlay = forceOverlayRoute && canUseOverlay
            if (preferOverlay) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=$rid EVT=ROUTE_SELECTED route=APP_OVERLAY reason=FORCE_OVERLAY type=${type.name}"
                    )
                }
                if (useMiuiBridgeIsland) {
                    try {
                        NotificationManagerCompat.from(this).cancel(bridgeId)
                        Log.d(
                            "HyperIsleIsland",
                            "RID=$rid EVT=MIUI_BRIDGE_CANCEL_OK reason=FORCE_OVERLAY type=${type.name}"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cancel MIUI bridge notification: ${e.message}")
                        Log.d(
                            "HyperIsleIsland",
                            "RID=$rid EVT=MIUI_BRIDGE_CANCEL_FAIL reason=FORCE_OVERLAY type=${type.name}"
                        )
                    }
                }
                val overlayDelivered = emitOverlayEvent(
                    sbn,
                    type,
                    title,
                    text,
                    isOngoingCall,
                    callDurationSeconds,
                    finalConfig
                )
                if (overlayDelivered) {
                    startMinVisibleTimer(groupKey, sbn.key.hashCode())
                }
                attemptShadeCancel(
                    sbn = sbn,
                    type = type,
                    isOngoingCall = isOngoingCall,
                    route = IslandRoute.APP_OVERLAY,
                    deliveryConfirmed = overlayDelivered
                )
                if (overlayDelivered) {
                    activeIslands[groupKey] = ActiveIsland(
                        id = bridgeId,
                        type = type,
                        postTime = System.currentTimeMillis(),
                        packageName = sbn.packageName,
                        title = title,
                        text = text,
                        subText = subText,
                        lastContentHash = newContentHash
                    )
                    activeRoutes[groupKey] = IslandRoute.APP_OVERLAY
                }
                return
            }
            val route = when {
                useMiuiBridgeIsland -> IslandRoute.MIUI_BRIDGE
                canUseOverlay -> IslandRoute.APP_OVERLAY
                else -> null
            }

            when (route) {
                IslandRoute.MIUI_BRIDGE -> {
                    when (activityResult) {
                        is IslandActivityStateMachine.ActivityResult.Completed -> {
                            postNotification(sbn, bridgeId, groupKey, type.name, data, ctx.rid)
                            attemptShadeCancel(sbn, type, isOngoingCall, route = IslandRoute.MIUI_BRIDGE)
                            Haptics.hapticOnIslandSuccess(applicationContext)
                            serviceScope.launch {
                                delay(activityResult.timeoutMs)
                                try {
                                    NotificationManagerCompat.from(this@NotificationReaderService).cancel(bridgeId)
                                    clearIslandState(groupKey)
                                    IslandActivityStateMachine.remove(groupKey)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to dismiss completed island: ${e.message}")
                                }
                            }
                        }
                        else -> {
                            postNotification(sbn, bridgeId, groupKey, type.name, data, ctx.rid)
                            attemptShadeCancel(sbn, type, isOngoingCall, route = IslandRoute.MIUI_BRIDGE)
                        }
                    }

                    activeIslands[groupKey] = ActiveIsland(
                        id = bridgeId,
                        type = type,
                        postTime = System.currentTimeMillis(),
                        packageName = sbn.packageName,
                        title = title,
                        text = text,
                        subText = subText,
                        lastContentHash = newContentHash
                    )
                    activeRoutes[groupKey] = IslandRoute.MIUI_BRIDGE

                    // Emit overlay event in addition to MIUI island (both systems work together)
                    emitOverlayEvent(
                        sbn,
                        type,
                        title,
                        text,
                        isOngoingCall,
                        callDurationSeconds,
                        finalConfig
                    )
                }
                IslandRoute.APP_OVERLAY -> {
                    val overlayDelivered = emitOverlayEvent(
                        sbn,
                        type,
                        title,
                        text,
                        isOngoingCall,
                        callDurationSeconds,
                        finalConfig
                    )
                    if (overlayDelivered) {
                        startMinVisibleTimer(groupKey, sbn.key.hashCode())
                    }
                    attemptShadeCancel(
                        sbn = sbn,
                        type = type,
                        isOngoingCall = isOngoingCall,
                        route = IslandRoute.APP_OVERLAY,
                        deliveryConfirmed = overlayDelivered
                    )
                    if (overlayDelivered) {
                        activeIslands[groupKey] = ActiveIsland(
                            id = bridgeId,
                            type = type,
                            postTime = System.currentTimeMillis(),
                            packageName = sbn.packageName,
                            title = title,
                            text = text,
                            subText = subText,
                            lastContentHash = newContentHash
                        )
                        activeRoutes[groupKey] = IslandRoute.APP_OVERLAY
                    }
                }
                null -> {
                    // Fallback to system notification when overlay is unavailable.
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postNotification(
        sbn: StatusBarNotification,
        bridgeId: Int,
        groupKey: String,
        notificationType: String,
        data: HyperIslandData,
        rid: String
    ) {
        val keyHash = sbn.key.hashCode()
        Log.d("HyperIsleIsland", "RID=$keyHash EVT=BRIDGE_POST_TRY pkg=${sbn.packageName} bridgeId=$bridgeId type=$notificationType")

        val notificationBuilder = NotificationCompat.Builder(this, ISLAND_CHANNEL_ID)
            .setSmallIcon(R.drawable.`ic_launcher_foreground`)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_active))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addExtras(data.resources)

        // Set contentIntent: wrap original in tap-open action to dismiss island on open
        val originalContentIntent = sbn.notification.contentIntent ?: createLaunchIntent(sbn.packageName)
        if (originalContentIntent != null) {
            // Store original intent for later retrieval by IslandActionReceiver
            IslandCooldownManager.setContentIntent(bridgeId, originalContentIntent)
            
            // Create wrapper PendingIntent that broadcasts tap-open action
            // Must use explicit component for receiver to receive dynamic action strings
            val tapOpenActionString = FocusActionHelper.buildActionString(FocusActionHelper.TYPE_TAP_OPEN, bridgeId)
            val tapOpenIntent = Intent(tapOpenActionString).apply {
                setPackage(packageName)
                setClass(this@NotificationReaderService, com.coni.hyperisle.receiver.IslandActionReceiver::class.java)
            }
            val wrapperIntent = PendingIntent.getBroadcast(
                this,
                "tapopen_$bridgeId".hashCode(),
                tapOpenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            notificationBuilder.setContentIntent(wrapperIntent)
            
            // Debug log: tapOpen setup
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "event=tapOpenSetup pkg=${sbn.packageName} keyHash=${sbn.key.hashCode()} bridgeId=$bridgeId")
            }
        }

        val notification = notificationBuilder.build()
        notification.extras.putString("miui.focus.param", data.jsonParam)

        try {
            NotificationManagerCompat.from(this).notify(bridgeId, notification)
        } catch (e: Exception) {
            Log.d(
                "HyperIsleIsland",
                "RID=$keyHash EVT=BRIDGE_POST_FAIL pkg=${sbn.packageName} bridgeId=$bridgeId reason=${e.javaClass.simpleName}"
            )
            throw e
        }
        bridgePostConfirmations[sbn.key] = System.currentTimeMillis()
        Log.d("HyperIsleIsland", "RID=$keyHash EVT=BRIDGE_POST_OK pkg=${sbn.packageName} bridgeId=$bridgeId")
        activeTranslations[groupKey] = bridgeId
        startMinVisibleTimer(groupKey, keyHash)

        // STEP: MIUI_POST_OK - Successfully posted island notification
        DebugLog.event("MIUI_POST_OK", rid, "POST", kv = mapOf(
            "pkg" to sbn.packageName,
            "bridgeId" to bridgeId,
            "type" to notificationType,
            "jsonLength" to data.jsonParam.length
        ))
        
        // UI Snapshot: BRIDGE_POST_OK - Successfully posted bridge notification
        if (BuildConfig.DEBUG) {
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = sbn.packageName,
                type = notificationType,
                keyHash = sbn.key.hashCode().toString(),
                groupKey = groupKey
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "BRIDGE_POST_OK",
                route = IslandUiSnapshotLogger.Route.MIUI_ISLAND_BRIDGE,
                extra = mapOf("bridgeId" to bridgeId, "channelId" to ISLAND_CHANNEL_ID)
            )
        }
        
        // INSTRUMENTATION: Island add with state transition
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=${sbn.key.hashCode()} STAGE=ADD ACTION=ISLAND_SHOWN pkg=${sbn.packageName} type=$notificationType bridgeId=$bridgeId")
            IslandRuntimeDump.recordAdd(
                ctx = ProcCtx.synthetic(sbn.packageName, "postNotification"),
                reason = "MIUI_POST_OK",
                groupKey = groupKey,
                islandType = notificationType,
                flags = mapOf("bridgeId" to bridgeId, "jsonLength" to data.jsonParam.length)
            )
            // Record state transition IDLE -> SHOWING
            val isOngoing = notificationType in listOf("CALL", "TIMER", "NAVIGATION")
            val nextState = if (isOngoing) IslandUiState.PINNED_ONGOING else IslandUiState.SHOWING_COMPACT
            IslandRuntimeDump.recordState(
                ctx = null,
                prevState = IslandUiState.IDLE,
                nextState = nextState,
                groupKey = groupKey,
                flags = mapOf("trigger" to "postNotification", "isOngoing" to isOngoing)
            )
        }

        // Record shown for PriorityEngine burst tracking
        PriorityEngine.recordShown(sbn.packageName, notificationType)
        
        // v0.9.2: Record island shown timestamp for fast dismiss detection
        PriorityEngine.recordIslandShown(sbn.packageName)
        
        // Timeline: islandShown event
        DebugTimeline.log(
            "islandShown",
            sbn.packageName,
            sbn.key.hashCode(),
            mapOf("type" to notificationType, "bridgeId" to bridgeId)
        )
        
        // Telemetry: ISLAND_SHOWN with window flags, style, dimensions
        // Note: Island is rendered by HyperOS system, so we log what we control
        HiLog.d(HiLog.TAG_INPUT, "ISLAND_SHOWN", mapOf(
            "pkg" to sbn.packageName,
            "notifKeyHash" to HiLog.hashKey(sbn.key),
            "islandStyle" to notificationType,
            "touchable" to true, // Islands are always touchable when shown
            "focusable" to false, // Islands don't take focus by default
            "bridgeId" to bridgeId
        ))

        // Store island meta for per-island action handling
        IslandCooldownManager.setIslandMeta(bridgeId, sbn.packageName, notificationType)

        // Store last active island info for receiver usage (fallback)
        IslandCooldownManager.setLastActiveIsland(bridgeId, sbn.packageName, notificationType)

        // Haptic feedback when island is shown
        Haptics.hapticOnIslandShown(applicationContext)
    }

    private suspend fun awaitBridgeConfirmation(sbn: StatusBarNotification): Boolean {
        val key = sbn.key
        val deadline = System.currentTimeMillis() + BRIDGE_CONFIRM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (bridgePostConfirmations.containsKey(key)) {
                return true
            }
            delay(BRIDGE_CONFIRM_POLL_MS)
        }
        return bridgePostConfirmations.containsKey(key)
    }

    /**
     * v0.9.9: Decoupled shade cancel using IslandDecisionEngine.
     * 
     * Contract B (Shade cancel): If hide-system-notification is enabled for that app, 
     * attempt to cancel the system notification ONLY when it is safe/eligible. 
     * If not eligible, do NOT cancel system notification, but Island was already shown.
     * 
     * Contract C (Safety gate): If HyperIsle notifications are disabled or the Island 
     * channel importance is NONE, never cancel the system notification.
     * 
     * This method is called AFTER the Island has been shown (Contract A fulfilled).
     */
    private suspend fun attemptShadeCancel(
        sbn: StatusBarNotification,
        type: NotificationType,
        isOngoingCall: Boolean = false,
        route: IslandRoute = IslandRoute.MIUI_BRIDGE,
        deliveryConfirmed: Boolean = true
    ) {
        val pkg = sbn.packageName
        val keyHash = sbn.key.hashCode()
        val clearable = sbn.isClearable
        
        // v1.0.0: Always intercept STANDARD and CALL notifications
        // STANDARD: Always cancel clearable notifications (messages, alerts)
        // CALL: Always suppress incoming call popups (handled via snooze/cancel earlier)
        // NAVIGATION: Always suppress to avoid system islands
        // Other types (MEDIA, PROGRESS, TIMER): Preserve existing behavior
        val shadeCancelEnabled = when (type) {
            NotificationType.STANDARD -> true  // Always intercept messages     
            NotificationType.CALL -> true      // Always intercept calls        
            NotificationType.NAVIGATION -> true // Always intercept navigation
            else -> preferences.isShadeCancel(pkg)  // Legacy behavior for other types
        }
        
        val shadeCancelMode = preferences.getShadeCancelMode(pkg)
        
        // Use IslandDecisionEngine for decoupled decision
        val decision = IslandDecisionEngine.computeDecision(
            context = applicationContext,
            sbn = sbn,
            type = type,
            isAppAllowedForIsland = true, // Already verified by caller
            shadeCancelEnabled = shadeCancelEnabled,
            shadeCancelMode = shadeCancelMode,
            isOngoingCall = isOngoingCall
        )
        val routeConfirmed = when (route) {
            IslandRoute.MIUI_BRIDGE -> if (decision.cancelShade) {
                awaitBridgeConfirmation(sbn)
            } else {
                bridgePostConfirmations.containsKey(sbn.key)
            }
            IslandRoute.APP_OVERLAY -> deliveryConfirmed
        }
        val forceNavigationCancel = type == NotificationType.NAVIGATION
        val forceCancelReady = forceNavigationCancel &&
            decision.cancelShadeAllowed &&
            decision.cancelShadeSafe &&
            routeConfirmed
        val cancelReady = decision.cancelShade && routeConfirmed
        val guardResult = when {
            forceCancelReady -> "FORCE"
            cancelReady -> "TRY"
            else -> "SKIP"
        }
        val guardReason = when {
            !decision.cancelShadeAllowed -> "SHADE_CANCEL_OFF"
            !decision.cancelShadeEligible -> decision.ineligibilityReason ?: "NOT_ELIGIBLE"
            !decision.cancelShadeSafe -> "ISLAND_CHANNEL_DISABLED"
            !routeConfirmed -> if (route == IslandRoute.MIUI_BRIDGE) "BRIDGE_NOT_CONFIRMED" else "OVERLAY_NOT_CONFIRMED"
            else -> "OK"
        }

        if (!decision.cancelShadeEligible && decision.ineligibilityReason == "NOT_CLEARABLE" && shadeCancelEnabled) {
            maybeShowNotClearableHint(pkg, keyHash)
        }
        
        // INSTRUMENTATION: Shade cancel decision
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=$keyHash STAGE=HIDE_SYS_NOTIF ACTION=DECISION cancelShade=${decision.cancelShade} allowed=${decision.cancelShadeAllowed} eligible=${decision.cancelShadeEligible} safe=${decision.cancelShadeSafe} reason=${decision.ineligibilityReason ?: "OK"} pkg=$pkg")
            IslandRuntimeDump.recordEvent(
                ctx = ProcCtx.synthetic(pkg, "shadeCancel"),
                stage = "HIDE_SYS_NOTIF",
                action = "DECISION",
                reason = if (decision.cancelShade) "WILL_CANCEL" else (decision.ineligibilityReason ?: "NOT_ALLOWED"),
                flags = mapOf(
                    "cancelShade" to decision.cancelShade,
                    "allowed" to decision.cancelShadeAllowed,
                    "eligible" to decision.cancelShadeEligible,
                    "safe" to decision.cancelShadeSafe
                )
            )
        }

        Log.d(
            "HyperIsleIsland",
            "RID=$keyHash EVT=CANCEL_GUARD reason=$guardReason decision={clearable=$clearable,eligible=${decision.cancelShadeEligible},safe=${decision.cancelShadeSafe},routeConfirmed=$routeConfirmed} result=$guardResult pkg=$pkg"
        )

        // v1.0.0: SHADE_CANCEL_GUARD - Log when shade cancel is enabled but cannot be performed
        // This helps identify apps where users should disable notifications in system settings
        // to avoid duplicate islands (MIUI/HyperOS often forces system notifications to show)
        if (decision.cancelShadeAllowed && !decision.cancelShade && type != NotificationType.MEDIA) {
            Log.d(
                "HyperIsleIsland",
                "RID=$keyHash EVT=SHADE_CANCEL_GUARD reason=$guardReason type=${type.name} pkg=$pkg"
            )
        }

        if (forceNavigationCancel) {
            if (!forceCancelReady) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=$keyHash EVT=SYS_NOTIF_CANCEL_SKIP reason=FORCE_NAV_GUARD pkg=$pkg"
                )
                return
            }
            val groupKey = sbnKeyToGroupKey[sbn.key]
            Log.d("HyperIsleIsland", "RID=$keyHash EVT=SYS_NOTIF_CANCEL_TRY mode=FORCE_NAV")
            try {
                delay(SYS_CANCEL_POST_DELAY_MS)
                cancelNotification(sbn.key)
                markSelfCancel(sbn.key, keyHash)
                if (groupKey != null) {
                    startMinVisibleTimer(groupKey, keyHash)
                }
                Log.d("HyperIsleIsland", "RID=$keyHash EVT=SYS_NOTIF_CANCEL_OK mode=FORCE_NAV")
            } catch (e: Exception) {
                selfCancelKeys.remove(sbn.key)
                Log.w(TAG, "Force navigation cancel failed: ${e.message}")
                Log.d(
                    "HyperIsleIsland",
                    "RID=$keyHash EVT=SYS_NOTIF_CANCEL_FAIL mode=FORCE_NAV reason=${e.javaClass.simpleName}"
                )
            }
            return
        }

        if (decision.cancelShade && !routeConfirmed) {
            val skipReason = if (route == IslandRoute.MIUI_BRIDGE) "BRIDGE_NOT_CONFIRMED" else "OVERLAY_NOT_CONFIRMED"
            Log.d("HyperIsleIsland", "RID=$keyHash EVT=CANCEL_SKIPPED_$skipReason")
            return
        }
        
        // Only cancel if all conditions are met
        if (decision.cancelShade) {
            // UI Snapshot: SYS_NOTIF_CANCEL_TRY
            if (BuildConfig.DEBUG) {
                val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                    rid = IslandUiSnapshotLogger.rid(),
                    pkg = pkg,
                    type = type.name,
                    keyHash = keyHash.toString()
                )
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "SYS_NOTIF_CANCEL_TRY",
                    route = IslandUiSnapshotLogger.Route.SYSTEM_NOTIFICATION
                )
            }
            
            val groupKey = sbnKeyToGroupKey[sbn.key]
            try {
                delay(SYS_CANCEL_POST_DELAY_MS)
                cancelNotification(sbn.key)
                markSelfCancel(sbn.key, keyHash)
                if (groupKey != null) {
                    startMinVisibleTimer(groupKey, keyHash)
                }
                Log.d("HyperIsleIsland", "RID=$keyHash EVT=SYS_NOTIF_CANCEL_OK")
                if (BuildConfig.DEBUG) {
                    Log.d("HyperIsleIsland", "RID=$keyHash STAGE=HIDE_SYS_NOTIF ACTION=CANCEL_OK pkg=$pkg")
                    Log.d("HI_NOTIF", "event=shadeCancelled pkg=$pkg keyHash=$keyHash")
                    IslandRuntimeDump.recordEvent(
                        ctx = ProcCtx.synthetic(pkg, "shadeCancel"),
                        stage = "HIDE_SYS_NOTIF",
                        action = "CANCEL_OK",
                        reason = "SUCCESS"
                    )
                    // UI Snapshot: SYS_NOTIF_CANCEL_OK
                    val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                        rid = IslandUiSnapshotLogger.rid(),
                        pkg = pkg,
                        type = type.name,
                        keyHash = keyHash.toString()
                    )
                    IslandUiSnapshotLogger.logEvent(
                        ctx = snapshotCtx,
                        evt = "SYS_NOTIF_CANCEL_OK",
                        route = IslandUiSnapshotLogger.Route.SYSTEM_NOTIFICATION
                    )
                }
            } catch (e: Exception) {
                selfCancelKeys.remove(sbn.key)
                Log.w(TAG, "Failed to cancel notification from shade: ${e.message}")
                Log.d("HyperIsleIsland", "RID=$keyHash EVT=SYS_NOTIF_CANCEL_FAIL reason=${e.javaClass.simpleName}")
                if (BuildConfig.DEBUG) {
                    Log.d("HyperIsleIsland", "RID=$keyHash STAGE=HIDE_SYS_NOTIF ACTION=CANCEL_FAIL reason=${e.message} pkg=$pkg")
                    IslandRuntimeDump.recordEvent(
                        ctx = ProcCtx.synthetic(pkg, "shadeCancel"),
                        stage = "HIDE_SYS_NOTIF",
                        action = "CANCEL_FAIL",
                        reason = e.message ?: "EXCEPTION",
                        flags = mapOf("exceptionType" to e.javaClass.simpleName)
                    )
                    // UI Snapshot: SYS_NOTIF_CANCEL_FAIL
                    val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                        rid = IslandUiSnapshotLogger.rid(),
                        pkg = pkg,
                        type = type.name,
                        keyHash = keyHash.toString()
                    )
                    IslandUiSnapshotLogger.logEvent(
                        ctx = snapshotCtx,
                        evt = "SYS_NOTIF_CANCEL_FAIL",
                        route = IslandUiSnapshotLogger.Route.SYSTEM_NOTIFICATION,
                        reason = e.javaClass.simpleName
                    )
                }
            }
        }

        if (!cancelReady &&
            decision.cancelShadeAllowed &&
            decision.cancelShadeSafe &&
            routeConfirmed &&
            decision.ineligibilityReason == "NOT_CLEARABLE" &&
            shadeCancelMode == ShadeCancelMode.AGGRESSIVE &&
            type != NotificationType.CALL
        ) {
            attemptAggressiveShadeCancel(sbn, type, keyHash)
        }
    }

    private fun isInQuietHours(): Boolean {
        return try {
            val now = LocalTime.now()
            val start = LocalTime.parse(focusQuietStart)
            val end = LocalTime.parse(focusQuietEnd)
            if (start <= end) {
                now in start..end
            } else {
                // Overnight range (e.g., 22:00 to 08:00)
                now >= start || now <= end
            }
        } catch (e: Exception) {
            false
        }
    }
    private val digestDedupeCache = ConcurrentHashMap<String, Long>()
    private val DIGEST_DEDUPE_WINDOW_MS = 2000L

    /**
     * v0.9.1: Insert digest item with optional suppression reason for diagnostics.
     * 
     * Safety rules:
     * - Never record media notifications (caller must check type != MEDIA)
     * - Deduplication prevents duplicate entries within 2 seconds
     * - Diagnostics are NO-OP when ActionDiagnostics is disabled (release builds)
     * - No PII (title/text) is logged to diagnostics, only pkg and keyHash
     * 
     * @param suppressionReason Optional reason code for why notification was suppressed (null = normal post)
     * @param keyHash Optional sbn.key.hashCode() for diagnostics (only computed when diagnostics enabled)
     */
    private suspend fun insertDigestItem(
        packageName: String,
        title: String,
        text: String,
        type: String,
        suppressionReason: String? = null,
        keyHash: Int? = null
    ) {
        try {
            // Deduplication: skip if same pkg+title+text within 2 seconds
            val dedupeKey = "$packageName:$title:$text"
            val now = System.currentTimeMillis()
            val lastInsert = digestDedupeCache[dedupeKey]
            if (lastInsert != null && (now - lastInsert) < DIGEST_DEDUPE_WINDOW_MS) {
                return
            }
            digestDedupeCache[dedupeKey] = now

            database.digestDao().insert(
                NotificationDigestItem(
                    packageName = packageName,
                    title = title,
                    text = text,
                    postTime = System.currentTimeMillis(),
                    type = type
                )
            )

            // v0.9.1: Record diagnostic line for suppressed notifications (DEBUG-ONLY)
            if (suppressionReason != null && ActionDiagnostics.isEnabled()) {
                val diagLine = "Digest record on suppression: pkg=$packageName keyHash=${keyHash ?: "N/A"} reason=$suppressionReason"
                ActionDiagnostics.record(diagLine)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to insert digest item: ${e.message}")
        }
    }

    private fun handleLimitReached(newType: NotificationType, newPkg: String) {
        when (currentMode) {
            IslandLimitMode.FIRST_COME -> return
            IslandLimitMode.MOST_RECENT -> {
                val oldest = activeIslands.minByOrNull { it.value.postTime }
                oldest?.let {
                    if (activeRoutes[it.key] == IslandRoute.MIUI_BRIDGE) {
                        NotificationManagerCompat.from(this).cancel(it.value.id)
                    }
                    clearIslandState(it.key)
                }
            }
            IslandLimitMode.PRIORITY -> {
                val newTypeRank = typePriorityRank(newType)
                val newAppRank = appPriorityRank(newPkg)
                val entryComparator = Comparator<Map.Entry<String, ActiveIsland>> { a, b ->
                    val typeDiff = typePriorityRank(a.value.type) - typePriorityRank(b.value.type)
                    if (typeDiff != 0) return@Comparator typeDiff
                    val appDiff = appPriorityRank(a.value.packageName) - appPriorityRank(b.value.packageName)
                    if (appDiff != 0) return@Comparator appDiff
                    a.value.postTime.compareTo(b.value.postTime)
                }
                val lowestActiveEntry = activeIslands.maxWithOrNull(entryComparator)
                if (lowestActiveEntry != null) {
                    val lowestTypeRank = typePriorityRank(lowestActiveEntry.value.type)
                    val lowestAppRank = appPriorityRank(lowestActiveEntry.value.packageName)
                    val shouldReplace = when {
                        newTypeRank != lowestTypeRank -> newTypeRank < lowestTypeRank
                        newAppRank != lowestAppRank -> newAppRank < lowestAppRank
                        else -> false
                    }
                    Log.d(
                        "HyperIsleIsland",
                        "RID=LIMIT EVT=PRIORITY_EVAL newType=${newType.name} newTypeRank=$newTypeRank newAppRank=$newAppRank lowestType=${lowestActiveEntry.value.type.name} lowestTypeRank=$lowestTypeRank lowestAppRank=$lowestAppRank decision=${if (shouldReplace) "REPLACE" else "KEEP"}"
                    )
                    if (shouldReplace) {
                        if (activeRoutes[lowestActiveEntry.key] == IslandRoute.MIUI_BRIDGE) {
                            NotificationManagerCompat.from(this).cancel(lowestActiveEntry.value.id)
                        }
                        clearIslandState(lowestActiveEntry.key)
                    }
                }
            }
        }

    }

    private fun typePriorityRank(type: NotificationType): Int {
        val index = typePriorityOrder.indexOf(type.name)
        return if (index != -1) index else Int.MAX_VALUE
    }

    private fun appPriorityRank(packageName: String): Int {
        return appPriorityList.indexOf(packageName).takeIf { it != -1 } ?: Int.MAX_VALUE
    }

    private fun maybeShowNotClearableHint(pkg: String, keyHash: Int) {
        val prefs = getSharedPreferences(SHADE_CANCEL_HINT_PREFS, MODE_PRIVATE)
        val lastShown = prefs.getLong("not_clearable_last_shown_ms", 0L)
        val now = System.currentTimeMillis()
        if (now - lastShown < SHADE_CANCEL_HINT_COOLDOWN_MS) return

        prefs.edit {
            putLong("not_clearable_last_shown_ms", now)
        }

        val poster = SystemHyperIslandPoster(this)
        if (!canPostNotifications() || !poster.hasNotificationPermission()) {
            Log.d(
                "HyperIsleIsland",
                "RID=$keyHash EVT=SHADE_CANCEL_HINT_SKIP reason=NO_POST_PERMISSION pkg=$pkg"
            )
            return
        }

        try {
            poster.postSystemNotification(
                SHADE_CANCEL_HINT_NOTIFICATION_ID,
                getString(R.string.app_name),
                getString(R.string.shade_cancel_not_clearable_banner)
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Shade cancel hint post blocked: ${e.message}")
            Log.d(
                "HyperIsleIsland",
                "RID=$keyHash EVT=SHADE_CANCEL_HINT_SKIP reason=SECURITY_EXCEPTION pkg=$pkg"
            )
            return
        }
        Log.d("HyperIsleIsland", "RID=$keyHash EVT=SHADE_CANCEL_HINT_SHOWN reason=NOT_CLEARABLE pkg=$pkg")
    }

    private suspend fun attemptAggressiveShadeCancel(
        sbn: StatusBarNotification,
        type: NotificationType,
        keyHash: Int
    ) {
        val pkg = sbn.packageName
        val groupKey = sbnKeyToGroupKey[sbn.key]
        Log.d("HyperIsleIsland", "RID=$keyHash EVT=SYS_NOTIF_CANCEL_TRY mode=AGGRESSIVE_TRY")

        try {
            delay(SYS_CANCEL_POST_DELAY_MS)
            cancelNotification(sbn.key)
            markSelfCancel(sbn.key, keyHash)
            if (groupKey != null) {
                startMinVisibleTimer(groupKey, keyHash)
            }
            Log.d("HyperIsleIsland", "RID=$keyHash EVT=SYS_NOTIF_CANCEL_OK mode=AGGRESSIVE_TRY")
        } catch (e: Exception) {
            selfCancelKeys.remove(sbn.key)
            Log.w(TAG, "Aggressive cancel failed: ${e.message}")
            Log.d("HyperIsleIsland", "RID=$keyHash EVT=SYS_NOTIF_CANCEL_FAIL mode=AGGRESSIVE_TRY reason=${e.javaClass.simpleName}")
            if (BuildConfig.DEBUG) {
                IslandRuntimeDump.recordEvent(
                    ctx = ProcCtx.synthetic(pkg, "shadeCancel"),
                    stage = "HIDE_SYS_NOTIF",
                    action = "CANCEL_FAIL",
                    reason = e.message ?: "EXCEPTION",
                    flags = mapOf("exceptionType" to e.javaClass.simpleName, "mode" to "AGGRESSIVE_TRY", "type" to type.name)
                )
            }
        }
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun createLaunchIntent(packageName: String): android.app.PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            android.app.PendingIntent.getActivity(
                this,
                packageName.hashCode(),
                launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            // Fallback to app details settings
            val settingsIntent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
            android.app.PendingIntent.getActivity(
                this,
                packageName.hashCode(),
                settingsIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun canPostNotifications(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCallTimer(sbn: StatusBarNotification, groupKey: String, picKey: String, config: IslandConfig, startTime: Long): Job {
        return serviceScope.launch {
            while (true) {
                delay(1000) // Update every second
                
                // Check if call still exists
                if (!activeCallTimers.containsKey(groupKey)) break
                
                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
                val data = callTranslator.translate(sbn, picKey, config, durationSeconds)
                emitCallOverlayUpdate(sbn, isOngoingCall = true, durationSeconds = durationSeconds)
                val bridgeId = activeTranslations[groupKey]

                // Check POST_NOTIFICATIONS permission before notify
                if (bridgeId == null) {
                    continue
                }
                if (!canPostNotifications()) {
                    if (BuildConfig.DEBUG) {
                        DebugTimeline.log(
                            "notifySkipped",
                            sbn.packageName,
                            sbn.key.hashCode(),
                            mapOf("reason" to "no_post_notifications")
                        )
                    }
                    continue
                }
                
                // Post updated notification
                try {
                    val notificationBuilder = NotificationCompat.Builder(this@NotificationReaderService, ISLAND_CHANNEL_ID)
                        .setSmallIcon(R.drawable.`ic_launcher_foreground`)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.service_active))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .addExtras(data.resources)
                    
                    // Use tap-open wrapper for call timer updates too
                    val originalContentIntent = sbn.notification.contentIntent ?: createLaunchIntent(sbn.packageName)
                    if (originalContentIntent != null) {
                        IslandCooldownManager.setContentIntent(bridgeId, originalContentIntent)
                        val tapOpenActionString = FocusActionHelper.buildActionString(FocusActionHelper.TYPE_TAP_OPEN, bridgeId)
                        val tapOpenIntent = Intent(tapOpenActionString).apply {
                            setPackage(packageName)
                            setClass(this@NotificationReaderService, com.coni.hyperisle.receiver.IslandActionReceiver::class.java)
                        }
                        val wrapperIntent = PendingIntent.getBroadcast(
                            this@NotificationReaderService,
                            "tapopen_$bridgeId".hashCode(),
                            tapOpenIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        notificationBuilder.setContentIntent(wrapperIntent)
                    }
                    
                    val notification = notificationBuilder.build()
                    notification.extras.putString("miui.focus.param", data.jsonParam)
                    
                    try {
                        NotificationManagerCompat.from(this@NotificationReaderService).notify(bridgeId, notification)
                    } catch (se: SecurityException) {
                        Log.w(TAG, "SecurityException during notify: ${se.message}")
                        if (BuildConfig.DEBUG) {
                            DebugTimeline.log(
                                "notifyFailed",
                                sbn.packageName,
                                sbn.key.hashCode(),
                                mapOf("reason" to "security_exception")
                            )
                        }
                        continue
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update call timer: ${e.message}")
                    continue
                }
            }
        }
    }

    private fun shouldIgnore(packageName: String): Boolean {
        return packageName == this.packageName ||
                packageName == "android" ||
                packageName == "com.android.systemui" ||
                packageName.contains("miui.notification")
    }

    /**
     * Checks if the package is a known dialer/incallui package.
     * Used to detect call notifications that may not have CATEGORY_CALL set.
     */
    private fun isDialerPackage(packageName: String): Boolean {
        return packageName in DIALER_PACKAGES
    }

    private val DIALER_PACKAGES = setOf(
        "com.google.android.dialer",
        "com.android.incallui",
        "com.android.dialer",
        "com.samsung.android.incallui",
        "com.miui.incallui",
        "com.android.phone"
    )

    private fun createIslandChannel() {
        val name = getString(R.string.channel_active_islands)
        val channel = NotificationChannel(
            ISLAND_CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun isAppAllowed(packageName: String): Boolean {
        return allowedPackageSet.contains(packageName)
    }

    /**
     * Emit overlay event for iOS-style pill overlay.
     * This runs in addition to MIUI island (both systems work together).
     * Only emits if overlay permission is granted.
     */
    private fun isOverlaySupported(type: NotificationType, isOngoingCall: Boolean): Boolean {
        return when (type) {
            NotificationType.CALL -> true
            NotificationType.STANDARD -> true
            NotificationType.NAVIGATION -> true
            else -> false
        }
    }

    private fun emitOverlayEvent(
        sbn: StatusBarNotification,
        type: NotificationType,
        title: String,
        text: String,
        isOngoingCall: Boolean,
        durationSeconds: Long?,
        config: IslandConfig
    ): Boolean {
        val rid = sbn.key.hashCode()
        if (!isOverlaySupported(type, isOngoingCall)) {
            return false
        }

        if (!shouldRenderOverlay(type, rid)) {
            return false
        }

        // Skip if overlay permission not granted
        if (!OverlayPermissionHelper.hasOverlayPermission(applicationContext)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Overlay permission not granted, skipping pill overlay")
            }
            return false
        }

        if (!OverlayPermissionHelper.startOverlayServiceIfPermitted(applicationContext)) {
            Log.w(TAG, "Overlay service unavailable, skipping pill overlay")
            return false
        }

        return try {
            when (type) {
                NotificationType.CALL -> {
                    emitCallOverlay(sbn, title, isOngoingCall, durationSeconds)
                }
                NotificationType.STANDARD -> {
                    val replyAction = extractReplyAction(sbn)
                    val collapseAfterMs = resolveOverlayCollapseMs(config)
                    val notifModel = buildNotificationOverlayModel(sbn, title, text, collapseAfterMs, replyAction)
                    OverlayEventBus.emitNotification(notifModel)
                }
                NotificationType.NAVIGATION -> {
                    val collapseAfterMs = 0L
                    val navModel = buildNotificationOverlayModel(sbn, title, text, collapseAfterMs, replyAction = null)
                    val emitted = OverlayEventBus.emitNotification(navModel)
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${sbn.key.hashCode()} EVT=OVERLAY_NAV_EMIT pkg=${sbn.packageName} result=${if (emitted) "OK" else "DROP"}"
                        )
                    }
                    emitted
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to emit overlay event: ${e.message}")
            false
        }
    }

    private fun emitMediaOverlayEvent(
        sbn: StatusBarNotification,
        title: String,
        text: String,
        subText: String
    ): Boolean {
        if (!OverlayPermissionHelper.hasOverlayPermission(applicationContext)) {
            return false
        }
        if (!OverlayPermissionHelper.startOverlayServiceIfPermitted(applicationContext)) {
            return false
        }
        val model = buildMediaOverlayModel(sbn, title, text, subText) ?: return false
        val emitted = OverlayEventBus.emitMedia(model)
        if (BuildConfig.DEBUG) {
            Log.d(
                "HyperIsleIsland",
                "RID=${sbn.key.hashCode()} EVT=OVERLAY_ACTIVITY_EMIT type=MEDIA pkg=${sbn.packageName} titleLen=${model.title.length} subtitleLen=${model.subtitle.length} actions=${model.actions.size} hasArt=${model.albumArt != null} result=${if (emitted) "OK" else "DROP"}"
            )
        }
        return emitted
    }

    private fun emitTimerOverlayEvent(
        sbn: StatusBarNotification,
        title: String
    ): Boolean {
        if (!OverlayPermissionHelper.hasOverlayPermission(applicationContext)) {
            return false
        }
        if (!OverlayPermissionHelper.startOverlayServiceIfPermitted(applicationContext)) {
            return false
        }
        val model = buildTimerOverlayModel(sbn, title) ?: return false
        val emitted = OverlayEventBus.emitTimer(model)
        if (BuildConfig.DEBUG) {
            Log.d(
                "HyperIsleIsland",
                "RID=${sbn.key.hashCode()} EVT=OVERLAY_ACTIVITY_EMIT type=TIMER pkg=${sbn.packageName} labelLen=${model.label.length} countdown=${model.isCountdown} result=${if (emitted) "OK" else "DROP"}"
            )
        }
        return emitted
    }

    private fun emitCallOverlay(
        sbn: StatusBarNotification,
        title: String,
        isOngoingCall: Boolean,
        durationSeconds: Long?
    ): Boolean {
        val groupKey = sbnKeyToGroupKey[sbn.key] ?: "${sbn.packageName}:CALL"
        val shouldShow = shouldShowCallOverlay(sbn, groupKey)
        val wasVisible = callOverlayVisibility[groupKey] ?: false
        if (!shouldShow) {
            if (wasVisible) {
                callOverlayVisibility[groupKey] = false
                OverlayEventBus.emitDismiss(sbn.key)
            }
            return false
        }

        val callModel = buildCallOverlayModel(sbn, title, isOngoingCall, durationSeconds)
            ?: return false
        callOverlayVisibility[groupKey] = true
        val emitted = OverlayEventBus.emitCall(callModel)
        if (BuildConfig.DEBUG) {
            Log.d(
                "HyperIsleIsland",
                "RID=${sbn.key.hashCode()} EVT=CALL_OVERLAY_EMIT state=${callModel.state.name} pkg=${sbn.packageName} result=${if (emitted) "OK" else "DROP"}"
            )
        }
        return emitted
    }

    private fun emitCallOverlayUpdate(
        sbn: StatusBarNotification,
        isOngoingCall: Boolean,
        durationSeconds: Long?
    ) {
        val rid = sbn.key.hashCode()
        if (!shouldRenderOverlay(NotificationType.CALL, rid)) return
        if (!OverlayPermissionHelper.hasOverlayPermission(applicationContext)) return
        if (!OverlayPermissionHelper.startOverlayServiceIfPermitted(applicationContext)) return
        val title = resolveCallTitle(sbn)
        emitCallOverlay(sbn, title, isOngoingCall, durationSeconds)
    }

    private fun shouldShowCallOverlay(sbn: StatusBarNotification, groupKey: String): Boolean {
        if (!ForegroundAppDetector.hasUsageAccess(applicationContext)) {
            val accForeground = AccessibilityContextState.snapshot().foregroundPackage
            if (accForeground != null) {
                val isForeground = accForeground == sbn.packageName
                val isDialerForeground = accForeground in CALL_FOREGROUND_PACKAGES
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=${sbn.key.hashCode()} EVT=OVERLAY_FG_FALLBACK source=ACCESSIBILITY fg=$accForeground pkg=${sbn.packageName}"
                    )
                }
                if (isForeground || isDialerForeground) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${sbn.key.hashCode()} EVT=OVERLAY_SKIP reason=CALL_UI_FOREGROUND pkg=${sbn.packageName} fg=$accForeground"
                        )
                    }
                    callOverlayVisibility[groupKey] = false
                    return false
                }
            } else if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=${sbn.key.hashCode()} EVT=OVERLAY_FG_UNKNOWN reason=USAGE_STATS_DENIED pkg=${sbn.packageName}"
                )
            }
            return true
        }

        // Check if the call app itself is in foreground
        val isForeground = ForegroundAppDetector.isPackageForeground(
            applicationContext,
            sbn.packageName
        )
        
        // Also check common dialer/incallui packages
        val foregroundPkg = ForegroundAppDetector.getForegroundPackage(applicationContext)
        val isDialerForeground = foregroundPkg in CALL_FOREGROUND_PACKAGES
        
        if (isForeground || isDialerForeground) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=${sbn.key.hashCode()} EVT=OVERLAY_SKIP reason=CALL_UI_FOREGROUND pkg=${sbn.packageName} fg=$foregroundPkg"
                )
            }
            callOverlayVisibility[groupKey] = false
            return false
        }
        
        callOverlayVisibility[groupKey] = true
        return true
    }
    
    companion object {
        private val CALL_FOREGROUND_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.incallui",
            "com.android.dialer",
            "com.samsung.android.incallui",
            "com.miui.incallui"
        )
    }

    private fun resolveCallTitle(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return extras.getStringCompat(Notification.EXTRA_TITLE)?.trim()?.ifBlank { null } ?: "Call"
    }

    private fun shouldRenderOverlay(type: NotificationType, rid: Int): Boolean {
        val screenOn = ContextStateManager.getEffectiveScreenOn(applicationContext)
        if (!screenOn) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_SKIP reason=SCREEN_OFF")
            return false
        }

        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        if (isKeyguardLocked && type != NotificationType.CALL) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_SKIP reason=KEYGUARD_LOCKED")
            return false
        }

        return true
    }

    private fun resolveOverlayCollapseMs(config: IslandConfig): Long? {
        val rawTimeout = config.timeout
        if (rawTimeout == null) return OVERLAY_DEFAULT_COLLAPSE_MS
        if (rawTimeout <= 0L) return null
        return if (rawTimeout <= 60L) rawTimeout * 1000L else rawTimeout
    }

    private fun resolveCallDurationText(extras: android.os.Bundle, durationSeconds: Long?): String {
        val subText = extras.getStringCompatOrEmpty(Notification.EXTRA_TEXT).trim().ifBlank { null }
        return when {
            !subText.isNullOrEmpty() && subText.contains(":") -> subText
            durationSeconds != null -> formatDuration(durationSeconds)
            else -> ""
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
        }
    }

    private fun extractReplyAction(sbn: StatusBarNotification): IosNotificationReplyAction? {
        val actions = sbn.notification.actions ?: return null
        val candidates = actions.filter { action ->
            val inputs = action.remoteInputs
            inputs != null && inputs.isNotEmpty() && action.actionIntent != null
        }
        if (candidates.isEmpty()) return null

        val replyAction = candidates.firstOrNull { action ->
            action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: candidates.first()

        val remoteInputs = replyAction.remoteInputs ?: return null
        val pendingIntent = replyAction.actionIntent ?: return null
        val label = replyAction.title?.toString()?.trim()?.ifBlank { null }
            ?: getString(R.string.overlay_reply)
        return IosNotificationReplyAction(
            title = label,
            pendingIntent = pendingIntent,
            remoteInputs = remoteInputs
        )
    }

    /**
     * Build IosCallOverlayModel from StatusBarNotification.
     * Extracts caller name and accept/decline PendingIntents from notification actions.
     */
    private fun buildCallOverlayModel(
        sbn: StatusBarNotification,
        title: String,
        isOngoingCall: Boolean,
        durationSeconds: Long?
    ): IosCallOverlayModel? {
        val extras = sbn.notification.extras
        val callerName = extras.getStringCompat(Notification.EXTRA_TITLE)?.trim()?.ifBlank { null } ?: title

        val actions = sbn.notification.actions ?: emptyArray()

        // Find accept/decline/speaker/mute actions
        val answerKeywords = resources.getStringArray(R.array.call_keywords_answer).toList()
        val hangUpKeywords = resources.getStringArray(R.array.call_keywords_hangup).toList()
        val speakerKeywords = resources.getStringArray(R.array.call_keywords_speaker).toList()
        val muteKeywords = resources.getStringArray(R.array.call_keywords_mute).toList()

        var acceptIntent: PendingIntent? = null
        var declineIntent: PendingIntent? = null
        var hangUpIntent: PendingIntent? = null
        var speakerIntent: PendingIntent? = null
        var muteIntent: PendingIntent? = null

        for (action in actions) {
            val actionTitle = action.title?.toString()?.lowercase(java.util.Locale.getDefault()) ?: continue
            when {
                answerKeywords.any { actionTitle.contains(it) } -> acceptIntent = action.actionIntent
                hangUpKeywords.any { actionTitle.contains(it) } -> {
                    declineIntent = action.actionIntent
                    hangUpIntent = action.actionIntent
                }
                speakerKeywords.any { actionTitle.contains(it) } -> speakerIntent = action.actionIntent
                muteKeywords.any { actionTitle.contains(it) } -> muteIntent = action.actionIntent
            }
        }

        val durationText = if (isOngoingCall) {
            resolveCallDurationText(extras, durationSeconds)
        } else {
            ""
        }

        return IosCallOverlayModel(
            title = if (isOngoingCall) getString(R.string.call_ongoing) else getString(R.string.call_incoming),
            callerName = callerName,
            avatarBitmap = null, // Could extract from notification largeIcon if needed
            contentIntent = sbn.notification.contentIntent,
            acceptIntent = acceptIntent,
            declineIntent = declineIntent,
            hangUpIntent = hangUpIntent,
            speakerIntent = speakerIntent,
            muteIntent = muteIntent,
            durationText = durationText,
            state = if (isOngoingCall) CallOverlayState.ONGOING else CallOverlayState.INCOMING,
            packageName = sbn.packageName,
            notificationKey = sbn.key
        )
    }

    private fun buildMediaOverlayModel(
        sbn: StatusBarNotification,
        title: String,
        text: String,
        subText: String
    ): MediaOverlayModel? {
        val displayTitle = title.ifBlank { sbn.packageName.substringAfterLast('.') }
        val displaySubtitle = when {
            text.isNotEmpty() && subText.isNotEmpty() -> "$text - $subText"
            text.isNotEmpty() -> text
            subText.isNotEmpty() -> subText
            else -> getString(R.string.status_now_playing)
        }
        val art = resolveOverlayBitmap(sbn)
        val actions = extractMediaActions(sbn)
        return MediaOverlayModel(
            title = displayTitle,
            subtitle = displaySubtitle,
            albumArt = art,
            actions = actions,
            contentIntent = sbn.notification.contentIntent,
            packageName = sbn.packageName,
            notificationKey = sbn.key
        )
    }

    private fun buildTimerOverlayModel(
        sbn: StatusBarNotification,
        title: String
    ): TimerOverlayModel? {
        val baseTime = sbn.notification.`when`
        if (baseTime <= 0L) return null
        val extras = sbn.notification.extras
        val isCountdown = extras.getBoolean(EXTRA_CHRONOMETER_COUNTDOWN) ||
            baseTime > System.currentTimeMillis()
        val label = title.ifBlank { getString(R.string.fallback_timer) }
        return TimerOverlayModel(
            label = label,
            baseTimeMs = baseTime,
            isCountdown = isCountdown,
            contentIntent = sbn.notification.contentIntent,
            packageName = sbn.packageName,
            notificationKey = sbn.key
        )
    }

    private fun extractMediaActions(sbn: StatusBarNotification): List<MediaAction> {
        val actions = sbn.notification.actions ?: return emptyList()
        return actions.mapNotNull { action ->
            val intent = action.actionIntent ?: return@mapNotNull null
            val label = action.title?.toString()?.trim().orEmpty()
            val iconBitmap = action.getIcon()?.let { loadIconBitmap(it, sbn.packageName) }
            MediaAction(label = label, iconBitmap = iconBitmap, actionIntent = intent)
        }.take(3)
    }

    private fun resolveOverlayBitmap(sbn: StatusBarNotification): Bitmap? {
        val extras = sbn.notification.extras
        val picture = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        if (picture != null) return picture
        val largeIconBitmap = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
        if (largeIconBitmap != null) return largeIconBitmap
        val largeIcon = sbn.notification.getLargeIcon()
        if (largeIcon != null) {
            val bitmap = loadIconBitmap(largeIcon, sbn.packageName)
            if (bitmap != null) return bitmap
        }
        val smallIcon = sbn.notification.smallIcon
        if (smallIcon != null) {
            val bitmap = loadIconBitmap(smallIcon, sbn.packageName)
            if (bitmap != null) return bitmap
        }
        return getAppIconBitmap(sbn.packageName)
    }

    private fun loadIconBitmap(icon: Icon, packageName: String): Bitmap? {
        return try {
            val drawable = if (icon.type == Icon.TYPE_RESOURCE) {
                try {
                    val targetContext = createPackageContext(packageName, 0)
                    icon.loadDrawable(targetContext)
                } catch (e: Exception) {
                    icon.loadDrawable(this)
                }
            } else {
                icon.loadDrawable(this)
            }
            drawable?.toBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private fun getAppIconBitmap(packageName: String): Bitmap? {
        return try {
            packageManager.getApplicationIcon(packageName).toBitmap()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build IosNotificationOverlayModel from StatusBarNotification.
     */
    private fun buildNotificationOverlayModel(
        sbn: StatusBarNotification,
        title: String,
        text: String,
        collapseAfterMs: Long?,
        replyAction: IosNotificationReplyAction?
    ): IosNotificationOverlayModel {
        // Get app name as sender
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName.substringAfterLast('.')
        }

        val effectiveCollapse = if (sbn.notification.category == Notification.CATEGORY_NAVIGATION) {
            0L
        } else {
            collapseAfterMs
        }
        return IosNotificationOverlayModel(
            sender = title.ifEmpty { appName },
            timeLabel = "now",
            message = text,
            avatarBitmap = null, // Could extract from notification largeIcon if needed
            contentIntent = sbn.notification.contentIntent,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            collapseAfterMs = effectiveCollapse,
            replyAction = replyAction
        )
    }

    /**
     * v0.9.1: Helper to infer notification type from StatusBarNotification.
     * Used for early suppression points where type hasn't been computed yet.
     */
    private fun inferNotificationType(sbn: StatusBarNotification): NotificationType {
        val extras = sbn.notification.extras
        val isCall = sbn.notification.category == Notification.CATEGORY_CALL
        val isNavigation = sbn.notification.category == Notification.CATEGORY_NAVIGATION ||
                sbn.packageName.contains("maps") || sbn.packageName.contains("waze")
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val hasProgress = progressMax > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val chronometerBase = sbn.notification.`when`
        val isTimer = (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ||
                sbn.notification.category == Notification.CATEGORY_ALARM ||
                sbn.notification.category == Notification.CATEGORY_STOPWATCH) && chronometerBase > 0
        val isMedia = extras.getStringCompatOrEmpty(Notification.EXTRA_TEMPLATE).contains("MediaStyle")

        return when {
            isCall -> NotificationType.CALL
            isNavigation -> NotificationType.NAVIGATION
            isTimer -> NotificationType.TIMER
            isMedia -> NotificationType.MEDIA
            hasProgress -> NotificationType.PROGRESS
            else -> NotificationType.STANDARD
        }
    }

    // Regression checklist:
    // - Telegram notification (spannable title/text): no crash; overlay expands then collapses to mini.
    // - Swipe dismiss works; tap expands first, then opens content.
    // - WhatsApp: clearable=true cancels system notif; clearable=false shows hint banner.
    // - Overlay service stops after overlays are dismissed.
}
