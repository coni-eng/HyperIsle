package com.coni.hyperisle.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.data.db.AppDatabase
import com.coni.hyperisle.data.db.NotificationDigestItem
import com.coni.hyperisle.debug.DebugLog
import com.coni.hyperisle.debug.IslandRuntimeDump
import com.coni.hyperisle.debug.IslandUiSnapshotLogger
import com.coni.hyperisle.debug.IslandUiState
import com.coni.hyperisle.debug.ProcCtx
import com.coni.hyperisle.models.ActiveIsland
import com.coni.hyperisle.models.HyperIslandData
import com.coni.hyperisle.models.IslandConfig
import com.coni.hyperisle.models.IslandLimitMode
import com.coni.hyperisle.models.MusicIslandMode
import com.coni.hyperisle.models.NotificationType
import com.coni.hyperisle.models.ShadeCancelMode
import com.coni.hyperisle.overlay.CallOverlayState
import com.coni.hyperisle.overlay.IosCallOverlayModel
import com.coni.hyperisle.overlay.IosNotificationOverlayModel
import com.coni.hyperisle.overlay.IosNotificationReplyAction
import com.coni.hyperisle.overlay.MediaAction
import com.coni.hyperisle.overlay.MediaOverlayModel
import com.coni.hyperisle.overlay.NavIslandSize
import com.coni.hyperisle.overlay.NavigationOverlayModel
import com.coni.hyperisle.overlay.OverlayEventBus
import com.coni.hyperisle.overlay.TimerOverlayModel
import com.coni.hyperisle.service.IslandDecisionEngine
import com.coni.hyperisle.service.IslandKeyManager
import com.coni.hyperisle.service.translators.CallTranslator
import com.coni.hyperisle.service.translators.NavTranslator
import com.coni.hyperisle.service.translators.ProgressTranslator
import com.coni.hyperisle.service.translators.StandardTranslator
import com.coni.hyperisle.service.translators.TimerTranslator
import com.coni.hyperisle.util.AccessibilityContextState
import com.coni.hyperisle.util.ActionDiagnostics
import com.coni.hyperisle.util.ContextStateManager
import com.coni.hyperisle.util.DebugTimeline
import com.coni.hyperisle.util.FocusActionHelper
import com.coni.hyperisle.util.ForegroundAppDetector
import com.coni.hyperisle.util.Haptics
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.util.IslandActivityStateMachine
import com.coni.hyperisle.util.IslandCooldownManager
import com.coni.hyperisle.util.IslandStyleContract
import com.coni.hyperisle.util.NotificationChannels
import com.coni.hyperisle.util.NotificationListenerDiagnostics
import com.coni.hyperisle.util.OverlayPermissionHelper
import com.coni.hyperisle.util.PriorityEngine
import com.coni.hyperisle.util.SystemHyperIslandPoster
import com.coni.hyperisle.util.getStringCompat
import com.coni.hyperisle.util.getStringCompatOrEmpty
import com.coni.hyperisle.util.toBitmap
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch



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

    // Call action intent cache (key: notification key)
    private data class CallActionCache(
        val hangUpIntent: PendingIntent?,
        val speakerIntent: PendingIntent?,
        val muteIntent: PendingIntent?
    )
    private val callActionCache = mutableMapOf<String, CallActionCache>()

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
    private val POPUP_SUPPRESS_SNOOZE_MS = 500L  // Snooze duration to suppress popup, then show silently in status bar
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

    // Heartbeat job for diagnostics
    private var heartbeatJob: Job? = null
    private val HEARTBEAT_INTERVAL_MS = 30_000L

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(applicationContext)
        createIslandChannel()
        
        // Notify diagnostics of service creation
        NotificationListenerDiagnostics.onServiceCreated()
        HiLog.i(HiLog.TAG_NOTIF, "HyperIsle Service Created")

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
                    HiLog.d(HiLog.TAG_NOTIF,
                        "RID=LIMIT EVT=APP_PRIORITY_TRIM size=${list.size} max=$MAX_PRIORITY_APPS"
                    )
                }
                appPriorityList = list.take(MAX_PRIORITY_APPS)
            }
        }
        serviceScope.launch {
            preferences.typePriorityOrderFlow.collectLatest { order ->
                typePriorityOrder = order
                HiLog.d(HiLog.TAG_NOTIF,
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
        
        // Get active notification count for diagnostics
        val activeCount = try {
            activeNotifications?.size ?: 0
        } catch (e: Exception) {
            HiLog.w(HiLog.TAG_NOTIF, "Failed to get active notifications: ${e.message}")
            0
        }
        
        HiLog.i(HiLog.TAG_NOTIF, "HyperIsle Connected - activeNotifications=$activeCount")
        
        // Notify diagnostics
        NotificationListenerDiagnostics.onListenerConnected(activeCount)
        
        // INSTRUMENTATION: Service lifecycle
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_NOTIF, "RID=SVC_CONN STAGE=LIFECYCLE ACTION=CONNECTED activeNotifications=$activeCount")
            IslandRuntimeDump.recordEvent(null, "LIFECYCLE", "NL_CONNECTED", reason = "onListenerConnected", flags = mapOf("activeNotifications" to activeCount))
            
            // Log enabled_notification_listeners status
            val isListed = NotificationListenerDiagnostics.isListedInEnabledListeners(applicationContext)
            val isBatteryOptimized = NotificationListenerDiagnostics.isBatteryOptimized(applicationContext)
            HiLog.d(HiLog.TAG_NOTIF, "RID=SVC_CONN EVT=LISTENER_STATUS isListed=$isListed batteryOptimized=$isBatteryOptimized")
        }
        
        // Start heartbeat monitoring (debug only)
        startHeartbeat()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        
        HiLog.w(HiLog.TAG_NOTIF, "HyperIsle DISCONNECTED - notifications will not be received!")
        
        // Notify diagnostics
        NotificationListenerDiagnostics.onListenerDisconnected()
        
        // Stop heartbeat
        stopHeartbeat()
        
        // INSTRUMENTATION: Service lifecycle
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_NOTIF, "RID=SVC_DISC STAGE=LIFECYCLE ACTION=DISCONNECTED")
            IslandRuntimeDump.recordEvent(null, "LIFECYCLE", "NL_DISCONNECTED", reason = "onListenerDisconnected")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        HiLog.w(HiLog.TAG_NOTIF, "HyperIsle Service DESTROYED")
        
        // Notify diagnostics
        NotificationListenerDiagnostics.onServiceDestroyed()
        
        // Stop heartbeat
        stopHeartbeat()
        
        // INSTRUMENTATION: Service lifecycle
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_NOTIF, "RID=SVC_DEST STAGE=LIFECYCLE ACTION=DESTROYED")
            IslandRuntimeDump.recordEvent(null, "LIFECYCLE", "SERVICE_DESTROYED", reason = "onDestroy")
        }
        serviceScope.cancel()
        IslandKeyManager.clearAll()
    }

    // --- Heartbeat for Diagnostics (Debug Only) ---
    
    private fun startHeartbeat() {
        if (!BuildConfig.DEBUG) return
        
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                
                val activeCount = try {
                    activeNotifications?.size ?: -1
                } catch (e: Exception) {
                    -1
                }
                
                // Update diagnostics
                NotificationListenerDiagnostics.updateHeartbeat(activeCount)
                
                // Check if we're still listed in enabled_notification_listeners
                val isListed = NotificationListenerDiagnostics.isListedInEnabledListeners(applicationContext)
                if (!isListed) {
                    HiLog.e(HiLog.TAG_NOTIF, "CRITICAL: HyperIsle removed from enabled_notification_listeners!", emptyMap())
                    HiLog.d(HiLog.TAG_NOTIF, "RID=HEARTBEAT EVT=LISTENER_NOT_LISTED WARN=CRITICAL")
                }
            }
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            // Generate correlation ID for this notification flow
            val ctx = ProcCtx.from(it)
            
            // CRITICAL: Log ALL notifications to catch WhatsApp/Telegram
            HiLog.d(HiLog.TAG_NOTIF, "RID=${ctx.rid} EVT=NOTIF_RECEIVED pkg=${it.packageName} keyHash=${ctx.keyHash}")
            
            // WhatsApp-specific telemetry for diagnosis
            if (it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b") {
                val extras = it.notification.extras
                val category = it.notification.category
                val isOngoing = (it.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                val isGroupSummary = (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                val hasContentIntent = it.notification.contentIntent != null
                val channelId = it.notification.channelId
                HiLog.d(HiLog.TAG_NOTIF,
                    "EVT=WHATSAPP_RECEIVED rid=${ctx.rid} keyHash=${ctx.keyHash} category=$category " +
                    "isOngoing=$isOngoing isGroupSummary=$isGroupSummary hasContentIntent=$hasContentIntent " +
                    "channelId=$channelId isClearable=${it.isClearable} " +
                    "hasTitle=${extras.getCharSequence(Notification.EXTRA_TITLE) != null} " +
                    "hasText=${extras.getCharSequence(Notification.EXTRA_TEXT) != null}"
                )
            }
            
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
            if (isJunkNotification(it, ctx)) {
                // WhatsApp telemetry: log when filtered as junk
                if (it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b") {
                    HiLog.d(HiLog.TAG_NOTIF, "EVT=WHATSAPP_FILTERED rid=${ctx.rid} reason=JUNK_NOTIFICATION")
                }
                return
            }

            // Early type detection for critical types that bypass whitelist
            val isNavigationType = it.notification.category == Notification.CATEGORY_NAVIGATION ||
                    it.packageName.contains("maps") || it.packageName.contains("waze")
            val isCallType = it.notification.category == Notification.CATEGORY_CALL ||
                    isDialerPackage(it.packageName)
            val bypassWhitelist = isNavigationType || isCallType
            
            if (!bypassWhitelist && !isAppAllowed(it.packageName)) {
                DebugLog.event("FILTER_CHECK", ctx.rid, "FILTER", reason = "BLOCKED_NOT_SELECTED", kv = mapOf("pkg" to it.packageName, "allowedCount" to allowedPackageSet.size))
                
                if (BuildConfig.DEBUG) {
                    HiLog.d("HyperIsleAnchor", "RID=${ctx.rid} EVT=NOTIF_OVERLAY_DECISION chosen=SKIP_OVERLAY reason=USER_DISABLED pkg=${it.packageName}")
                }
                
                // WhatsApp telemetry: log when not in allowed list
                if (it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b") {
                    HiLog.d(HiLog.TAG_NOTIF, "EVT=WHATSAPP_FILTERED rid=${ctx.rid} reason=NOT_IN_ALLOWED_LIST allowedCount=${allowedPackageSet.size}")
                }
                return
            }
            
            if (bypassWhitelist && !isAppAllowed(it.packageName)) {
                DebugLog.event("FILTER_CHECK", ctx.rid, "FILTER", reason = "ALLOWED_FORCE_NAV_CALL", kv = mapOf("pkg" to it.packageName, "isNav" to isNavigationType, "isCall" to isCallType))
            }
            
            DebugLog.event("FILTER_CHECK", ctx.rid, "FILTER", reason = "ALLOWED", kv = mapOf("pkg" to it.packageName))
            
            // WhatsApp telemetry: log when passed filter
            if (it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b") {
                HiLog.d(HiLog.TAG_NOTIF, "EVT=WHATSAPP_ALLOWED rid=${ctx.rid} step=FILTER_PASSED")
            }
            
            if (shouldSkipUpdate(it)) {
                DebugLog.event("DEDUP_CHECK", ctx.rid, "DEDUP", reason = "DROP_RATE_LIMIT", kv = mapOf("pkg" to it.packageName))
                // WhatsApp telemetry: log when rate-limited
                if (it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b") {
                    HiLog.d(HiLog.TAG_NOTIF, "EVT=WHATSAPP_FILTERED rid=${ctx.rid} reason=RATE_LIMITED")
                }
                return
            }
            
            // WhatsApp telemetry: log when queued for processing
            if (it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b") {
                HiLog.d(HiLog.TAG_NOTIF, "EVT=WHATSAPP_QUEUED rid=${ctx.rid} step=PROCESS_AND_POST")
            }
            
            // BUG#2 DEBUG: Log all notification processing for WhatsApp
            if (BuildConfig.DEBUG && (it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b")) {
                HiLog.d(HiLog.TAG_NOTIF, "EVT=WHATSAPP_PROCESSING_START rid=${ctx.rid}")
            }
            
            // BUG#4 FIX: IMMEDIATE SNOOZE to beat MIUI dynamic island
            // MIUI's system island triggers immediately when notification arrives.
            // By snoozing BEFORE processing, we prevent MIUI from showing its island.
            // Only for clearable non-ongoing notifications from messaging apps.
            // FIX: Always snooze on MIUI devices regardless of useMiuiBridgeIsland setting
            // This ensures MIUI island is suppressed even when using APP_OVERLAY route
            val isClearableStandard = it.isClearable && !ctx.isOngoing && 
                it.notification.category != Notification.CATEGORY_CALL &&
                it.notification.category != Notification.CATEGORY_NAVIGATION &&
                it.notification.category != Notification.CATEGORY_TRANSPORT
            val isMiuiDevice = android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                android.os.Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
                android.os.Build.MANUFACTURER.equals("POCO", ignoreCase = true)
            
            // Check if we recently snoozed this notification to prevent loop (stash logic)
            val isReturningFromSnooze = isSelfCancel(it.key, System.currentTimeMillis(), it.packageName, it.id)
            
            // For debug/test notifications, bypass strict MIUI/Clearable checks to test Stash logic
            val isTestNotification = BuildConfig.DEBUG && it.packageName == this.packageName
            val shouldStash = (isClearableStandard && isMiuiDevice) || isTestNotification
            
            if (shouldStash && !isReturningFromSnooze) {
                try {
                    snoozeNotification(it.key, POPUP_SUPPRESS_SNOOZE_MS)
                    markSelfCancel(it.key, ctx.keyHash, it.packageName, it.id)
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_NOTIF, "RID=${ctx.rid} EVT=IMMEDIATE_SNOOZE_OK pkg=${it.packageName} reason=${if (isTestNotification) "TEST_STASH" else "BEAT_MIUI_ISLAND"}")
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_NOTIF, "RID=${ctx.rid} EVT=IMMEDIATE_SNOOZE_FAIL pkg=${it.packageName} error=${e.message}")
                    }
                }
            } else if (isReturningFromSnooze) {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=${ctx.rid} EVT=SNOOZE_SKIP_RETURNING pkg=${it.packageName} reason=ALREADY_SNOOZED")
                }
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
            val selfCancel = reason == REASON_LISTENER_CANCEL && isSelfCancel(key, now, it.packageName, it.id)
            
            // Debug-only logging (PII-free)
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "event=onRemoved pkg=${it.packageName} keyHash=${key.hashCode()} reason=$reasonName")
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
                
                // Ignore GROUP_SUMMARY_CANCELED for active islands - this is a side-effect
                // of our own group summary cancellation, not a real notification removal.
                // The individual notification we're showing is still valid.
                if (reason == REASON_GROUP_SUMMARY_CANCELED) {
                    HiLog.d(HiLog.TAG_ISLAND,
                        "RID=$keyHash EVT=ON_REMOVED_IGNORED reason=$reasonName activeIsland=true cause=OUR_GROUP_CANCEL"
                    )
                    return
                }
                
                val remainingVisibleMs = remainingMinVisibleMs(safeGroupKey, now)
                val delayMs = when {
                    selfCancel -> if (remainingVisibleMs > 0L) remainingVisibleMs else MIN_VISIBLE_MS
                    remainingVisibleMs > 0L -> remainingVisibleMs
                    else -> 0L
                }
                val action = if (delayMs > 0L) "DELAY_HIDE" else "HIDE_NOW"

                HiLog.d(HiLog.TAG_ISLAND,
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
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=NOTIF_REMOVED_$reasonName")
                }
            }
            sbnKeyToGroupKey.remove(key)
            bridgePostConfirmations.remove(key)
            selfCancelKeys.remove(key)
            callActionCache.remove(key)

            if (!isActiveIsland && activeIslands.isEmpty()) {
                IslandCooldownManager.clearLastActiveIsland()
                if (OverlayEventBus.emitDismissAll()) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=ACTIVE_ISLANDS_EMPTY_$reasonName")
                }
                HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=STATE_RESET_DONE reason=ACTIVE_ISLANDS_EMPTY_$reasonName")
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

    private fun markSelfCancel(key: String, keyHash: Int, pkg: String, sbnId: Int) {
        val now = System.currentTimeMillis()
        // Use composite key (pkg|id) for stability across snooze cycles
        val stableKey = "$pkg|$sbnId"
        selfCancelKeys[stableKey] = now
        HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SELF_CANCEL_MARK stableKey=$stableKey ttlMs=$SELF_CANCEL_WINDOW_MS")
        serviceScope.launch {
            delay(SELF_CANCEL_WINDOW_MS)
            if (selfCancelKeys[stableKey] == now) {
                selfCancelKeys.remove(stableKey)
            }
        }
    }

    private fun isSelfCancel(key: String, now: Long, pkg: String, sbnId: Int): Boolean {
        // Check stable key first
        val stableKey = "$pkg|$sbnId"
        val markTime = selfCancelKeys[stableKey]
        
        if (markTime != null) {
            val isSelfCancel = now - markTime <= SELF_CANCEL_WINDOW_MS
            if (isSelfCancel) {
                // Consume the event so we don't block future notifications
                selfCancelKeys.remove(stableKey)
                return true
            } else {
                selfCancelKeys.remove(stableKey)
            }
        }
        
        // Fallback to original key if needed (though unlikely to be used if we switched to stableKey)
        return false
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
        HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=MIN_VISIBLE_TIMER_START gk=$groupKey ms=$MIN_VISIBLE_MS")
        minVisibleJobs[groupKey] = serviceScope.launch {
            delay(MIN_VISIBLE_MS)
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=MIN_VISIBLE_TIMER_END gk=$groupKey")
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
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=MIN_VISIBLE_ELAPSED")
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
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=NOTIF_REMOVED_$reasonName")
        }

        // Debug-only: Log auto-dismiss for call islands
        if (BuildConfig.DEBUG && groupKey.endsWith(":CALL")) {
            val dismissReason = when (reason) {
                REASON_APP_CANCEL, REASON_CANCEL -> "CALL_ENDED"
                REASON_CLICK -> "DIALER_OPENED"
                else -> mapRemovalReason(reason)
            }
            HiLog.d(HiLog.TAG_NOTIF, "event=autoDismiss reason=$dismissReason pkg=$pkg keyHash=$keyHash")
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
                HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash STAGE=REMOVE ACTION=ISLAND_DISMISS reason=$reasonName pkg=$pkg type=$islandType")
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
                HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=OVERLAY_HIDE_CALLED reason=ACTIVE_ISLANDS_EMPTY_$reasonName")
            }
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=STATE_RESET_DONE reason=ACTIVE_ISLANDS_EMPTY_$reasonName")
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
            HiLog.d(HiLog.TAG_ISLAND,
                "RID=$keyHash EVT=GROUP_SUMMARY_CANCEL_SKIP reason=ISLAND_CHANNEL_DISABLED pkg=${sbn.packageName}"
            )
            return
        }
        try {
            cancelNotification(sbn.key)
            markSelfCancel(sbn.key, keyHash, sbn.packageName, sbn.id)
            HiLog.d(HiLog.TAG_ISLAND,
                "RID=$keyHash EVT=GROUP_SUMMARY_CANCEL_OK type=${type.name} pkg=${sbn.packageName}"
            )
        } catch (e: Exception) {
            HiLog.w(HiLog.TAG_NOTIF, "Failed to cancel group summary: ${e.message}")
            HiLog.d(HiLog.TAG_ISLAND,
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
        HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=GROUP_CHECK pkg=$pkg isGroupSummary=$isGroupSummary flags=${notification.flags}")
        
        if (isGroupSummary) {
            if (isAppAllowed(pkg)) {
                val type = inferNotificationType(sbn)
                val keyHash = ctx?.keyHash ?: sbn.key.hashCode()
                cancelGroupSummaryIfNeeded(sbn, type, keyHash)
            }
            DebugLog.event("JUNK_CHECK", rid, "JUNK", reason = "GROUP_SUMMARY", kv = mapOf("pkg" to pkg))
            HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=BLOCKED_GROUP_SUMMARY pkg=$pkg")
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
            
            // BUG#2 DEBUG: Track WhatsApp through entire flow
            val isWhatsApp = sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b"
            if (BuildConfig.DEBUG && isWhatsApp) {
                HiLog.d(HiLog.TAG_NOTIF, "EVT=PROCESS_START rid=$rid")
            }

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
            
            // BUG#2 DEBUG: Log type classification
            if (BuildConfig.DEBUG && isWhatsApp) {
                HiLog.d(HiLog.TAG_NOTIF, "EVT=TYPE_CLASSIFIED rid=$rid type=${type.name}")
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
                                HiLog.w(HiLog.TAG_NOTIF, "Failed to cancel media notification: ${e.message}")
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
                    HiLog.d(HiLog.TAG_NOTIF, "Context preset ${contextPreset.name}: Blocked ${type.name} for ${sbn.packageName}")
                    return
                }
            }

            // --- GOOGLE MAPS FLOATING ISLAND BLOCKER ---
            // Prevent Google Maps from showing its own floating island alongside ours
            val blockGoogleMapsFloatingIsland = preferences.blockGoogleMapsFloatingIslandFlow.first()
            if (blockGoogleMapsFloatingIsland && sbn.packageName == "com.google.android.apps.maps" && type == NotificationType.NAVIGATION) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=GOOGLE_MAPS_NAV_BLOCKED pkg=${sbn.packageName} - Preventing system floating island")
                // Cancel the original notification to prevent system floating island
                try {
                    NotificationManagerCompat.from(this).cancel(sbn.id)
                } catch (e: Exception) {
                    HiLog.w(HiLog.TAG_NOTIF, "Failed to cancel Google Maps notification: ${e.message}")
                }
                // Continue processing with our island only
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
                        HiLog.d(HiLog.TAG_NOTIF, "Context-aware: Blocked ${type.name} for ${sbn.packageName} (screen off)")
                        return
                    }
                }
            }

            // --- REPLACE POLICY: Use groupKey instead of sbn.key ---
            val groupKey = "${sbn.packageName}:${type.name}"
            
            // v0.9.8: Detect Group vs DM (to prevent group summary override)
            val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || 
                          !extras.getStringCompat(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank()

            val bridgeId = groupKey.hashCode()
            sbnKeyToGroupKey[sbn.key] = groupKey
            pendingDismissJobs.remove(groupKey)?.cancel()

            val isUpdate = activeIslands.containsKey(groupKey)
            
            // BUG FIX: Prevent Group Summary from overriding recent DM notification
            // If we are showing a DM (!isGroup) that arrived recently (< 3s), ignore incoming Group updates.
            if (isUpdate) {
                val previousIsland = activeIslands[groupKey]
                if (previousIsland != null && !previousIsland.isGroup && isGroup) {
                    val timeDiff = System.currentTimeMillis() - previousIsland.postTime
                    // If DM was shown less than 3 seconds ago, ignore this group update
                    if (timeDiff < 3000) {
                        HiLog.d(HiLog.TAG_ISLAND, "RID=$rid EVT=UPDATE_SKIP reason=GROUP_OVERRIDE_DM_BLOCK pkg=${sbn.packageName}")
                        DebugLog.event("UPDATE_SKIP", rid, "SKIP", reason = "GROUP_OVERRIDE_DM_BLOCK", kv = mapOf(
                            "pkg" to sbn.packageName,
                            "prevTitle" to previousIsland.title
                        ))
                        return
                    }
                }
            }
            
            // --- EMPTY MESSAGE UPDATE GUARD ---
            // Skip update if message content is empty (prevents narrow/collapsed island display)
            // This handles cases where Telegram/WhatsApp sends update notifications with only sender name
            if (isUpdate && type == NotificationType.STANDARD && text.isEmpty() && subText.isEmpty()) {
                HiLog.d(HiLog.TAG_ISLAND,
                    "RID=$rid EVT=UPDATE_SKIP reason=EMPTY_MESSAGE_UPDATE pkg=${sbn.packageName} titleLen=${title.length} textLen=0"
                )
                DebugLog.event("UPDATE_SKIP", rid, "SKIP", reason = "EMPTY_MESSAGE_UPDATE", kv = mapOf(
                    "pkg" to sbn.packageName,
                    "titleLen" to title.length,
                    "textLen" to 0
                ))
                return
            }
            
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

            // --- EARLY INTERCEPTION: Handle STANDARD notifications based on ShadeCancelMode ---
            // This controls whether/how we suppress system popup while showing Dynamic Island
            // 
            // Mode behavior:
            // - STASH: Never snooze/cancel → notification stays in shade + status bar (messaging apps)
            // - ISLAND_ONLY: Don't snooze/cancel → notification stays in shade (safe default)
            // - HIDE_POPUP_KEEP_SHADE: Snooze briefly → suppress popup, keep in shade
            // - FULLY_HIDE/AGGRESSIVE: Cancel → remove from shade entirely
            //
            // Scope: Only STANDARD type (messages, alerts). Does NOT affect PROGRESS, NAVIGATION, MEDIA, TIMER.
            val shadeCancelMode = preferences.getShadeCancelMode(sbn.packageName)
            val isStashMode = ShadeCancelMode.isStashMode(shadeCancelMode)
            val shouldSnooze = ShadeCancelMode.shouldSnooze(shadeCancelMode)
            val shouldCancel = ShadeCancelMode.shouldCancel(shadeCancelMode)
            
            // Log shade cancel mode decision
            HiLog.d(HiLog.TAG_NOTIF, "EVT=SHADE_MODE_CHECK pkg=${sbn.packageName} mode=${shadeCancelMode.name} " +
                "shouldSnooze=$shouldSnooze shouldCancel=$shouldCancel isStash=$isStashMode")
            
            // Store content intent for all modes
            val contentIntent = sbn.notification.contentIntent
            if (contentIntent != null) {
                val bridgeIdForIntent = groupKey.hashCode()
                IslandCooldownManager.setContentIntent(bridgeIdForIntent, contentIntent)
            }
            
            if (type == NotificationType.STANDARD && sbn.isClearable && !isOngoing) {
                when {
                    // STASH or ISLAND_ONLY: Never touch system notification
                    isStashMode || shadeCancelMode == ShadeCancelMode.ISLAND_ONLY -> {
                        HiLog.d(HiLog.TAG_NOTIF, "EVT=NOTIF_STASH_POLICY pkg=${sbn.packageName} mode=${shadeCancelMode.name} action=NONE")
                        DebugLog.event("EARLY_INTERCEPT", rid, "SKIP", reason = "STASH_OR_ISLAND_ONLY", kv = mapOf(
                            "pkg" to sbn.packageName,
                            "mode" to shadeCancelMode.name
                        ))
                    }
                    
                    // HIDE_POPUP_KEEP_SHADE: Snooze to suppress popup, keep in shade
                    shouldSnooze -> {
                        try {
                            snoozeNotification(sbn.key, POPUP_SUPPRESS_SNOOZE_MS)
                            markSelfCancel(sbn.key, sbn.key.hashCode(), sbn.packageName, sbn.id)
                            HiLog.d(HiLog.TAG_NOTIF, "EVT=NOTIF_SNOOZE_OK pkg=${sbn.packageName} mode=${shadeCancelMode.name} snoozeMs=$POPUP_SUPPRESS_SNOOZE_MS")
                            DebugLog.event("EARLY_INTERCEPT", rid, "INTERCEPT", reason = "SNOOZE_OK", kv = mapOf(
                                "pkg" to sbn.packageName,
                                "mode" to shadeCancelMode.name,
                                "snoozeMs" to POPUP_SUPPRESS_SNOOZE_MS
                            ))
                        } catch (e: Exception) {
                            HiLog.w(HiLog.TAG_NOTIF, "EVT=NOTIF_SNOOZE_FAIL pkg=${sbn.packageName} error=${e.message}")
                            DebugLog.event("EARLY_INTERCEPT", rid, "INTERCEPT", reason = "SNOOZE_FAIL", kv = mapOf(
                                "pkg" to sbn.packageName,
                                "error" to (e.message ?: "unknown")
                            ))
                        }
                    }
                    
                    // FULLY_HIDE or AGGRESSIVE: Cancel notification from shade
                    shouldCancel -> {
                        try {
                            cancelNotification(sbn.key)
                            markSelfCancel(sbn.key, sbn.key.hashCode(), sbn.packageName, sbn.id)
                            HiLog.d(HiLog.TAG_NOTIF, "EVT=NOTIF_CANCEL_OK pkg=${sbn.packageName} mode=${shadeCancelMode.name}")
                            DebugLog.event("EARLY_INTERCEPT", rid, "INTERCEPT", reason = "CANCEL_OK", kv = mapOf(
                                "pkg" to sbn.packageName,
                                "mode" to shadeCancelMode.name
                            ))
                        } catch (e: Exception) {
                            HiLog.w(HiLog.TAG_NOTIF, "EVT=NOTIF_CANCEL_FAIL pkg=${sbn.packageName} error=${e.message}")
                            DebugLog.event("EARLY_INTERCEPT", rid, "INTERCEPT", reason = "CANCEL_FAIL", kv = mapOf(
                                "pkg" to sbn.packageName,
                                "error" to (e.message ?: "unknown")
                            ))
                        }
                    }
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
                        markSelfCancel(sbn.key, sbn.key.hashCode(), sbn.packageName, sbn.id)
                        DebugLog.event("CALL_INTERCEPT", rid, "INTERCEPT", reason = "CALL_ONGOING_CANCEL_OK", kv = mapOf(
                            "pkg" to sbn.packageName,
                            "callState" to callState,
                            "method" to "cancel"
                        ))
                    } catch (e: Exception) {
                        HiLog.w(HiLog.TAG_NOTIF, "Failed to cancel ongoing call notification: ${e.message}")
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
                        markSelfCancel(sbn.key, sbn.key.hashCode(), sbn.packageName, sbn.id)
                        DebugLog.event("CALL_INTERCEPT", rid, "INTERCEPT", reason = "CALL_INCOMING_SNOOZE_OK", kv = mapOf(
                            "pkg" to sbn.packageName,
                            "callState" to callState,
                            "method" to "snooze",
                            "durationMs" to 60_000L
                        ))
                    } catch (e: Exception) {
                        // Fallback: try cancelNotification if snooze fails
                        // NOTE: This may stop ringtone on some devices - test carefully!
                        HiLog.w(HiLog.TAG_NOTIF, "Snooze failed for incoming call, trying cancel: ${e.message}")
                        try {
                            cancelNotification(sbn.key)
                            markSelfCancel(sbn.key, sbn.key.hashCode(), sbn.packageName, sbn.id)
                            DebugLog.event("CALL_INTERCEPT", rid, "INTERCEPT", reason = "CALL_INCOMING_CANCEL_FALLBACK", kv = mapOf(
                                "pkg" to sbn.packageName,
                                "callState" to callState,
                                "method" to "cancel"
                            ))
                        } catch (e2: Exception) {
                            HiLog.w(HiLog.TAG_NOTIF, "Failed to suppress incoming call notification: ${e2.message}")
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
            
            // BUG FIX: Notification types (Telegram/WhatsApp) MUST use APP_OVERLAY, never MIUI bridge
            // MIUI bridge renders a narrow island view that's not suitable for messaging notifications
            val blockMiuiBridgeForNotif = type == NotificationType.STANDARD && canUseOverlay
            
            // BUG#2 DEBUG: Log overlay routing decision
            if (BuildConfig.DEBUG && isWhatsApp) {
                HiLog.d(HiLog.TAG_NOTIF, "EVT=OVERLAY_DECISION rid=$rid overlaySupported=$overlaySupported canUseOverlay=$canUseOverlay forceOverlayRoute=$forceOverlayRoute preferOverlay=$preferOverlay blockMiuiBridge=$blockMiuiBridgeForNotif")
            }
            if (preferOverlay) {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND,
                        "RID=$rid EVT=ROUTE_SELECTED route=APP_OVERLAY reason=FORCE_OVERLAY type=${type.name}"
                    )
                }
                if (useMiuiBridgeIsland) {
                    try {
                        NotificationManagerCompat.from(this).cancel(bridgeId)
                        HiLog.d(HiLog.TAG_ISLAND,
                            "RID=$rid EVT=MIUI_BRIDGE_CANCEL_OK reason=FORCE_OVERLAY type=${type.name}"
                        )
                    } catch (e: Exception) {
                        HiLog.w(HiLog.TAG_NOTIF, "Failed to cancel MIUI bridge notification: ${e.message}")
                        HiLog.d(HiLog.TAG_ISLAND,
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
                        lastContentHash = newContentHash,
                        isGroup = isGroup
                    )
                    activeRoutes[groupKey] = IslandRoute.APP_OVERLAY
                }
                return
            }
            // BUG FIX: Block MIUI bridge for notification types - force APP_OVERLAY
            val route = when {
                blockMiuiBridgeForNotif -> IslandRoute.APP_OVERLAY  // Notifications always use APP_OVERLAY
                useMiuiBridgeIsland -> IslandRoute.MIUI_BRIDGE      // Calls can use MIUI bridge
                canUseOverlay -> IslandRoute.APP_OVERLAY
                else -> null
            }
            
            // TELEMETRY: Log notification route decision for ALL notification types
            // BUG#4 FIX: Log NOTIF_ROUTE_FINAL for every notification, not just STANDARD
            if (BuildConfig.DEBUG) {
                val suppressedRoute = if (blockMiuiBridgeForNotif && useMiuiBridgeIsland) "MIUI_ISLAND_BRIDGE" else "NONE"
                val isMiuiBridgeNotif = sbn.packageName == "com.android.systemui" && 
                    sbn.notification.extras?.getString("android.title")?.contains("MIUI") == true
                HiLog.d(HiLog.TAG_NOTIF,
                    "EVT=NOTIF_ROUTE_FINAL pkg=${sbn.packageName} type=${type.name} chosen=${route?.name ?: "NULL"} suppressed=$suppressedRoute reason=${if (isMiuiBridgeNotif) "MIUI_BRIDGE_NOTIF_NOOP" else if (blockMiuiBridgeForNotif) "NOTIF_MUST_USE_APP_OVERLAY" else "DEFAULT"}"
                )
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
                                    HiLog.w(HiLog.TAG_NOTIF, "Failed to dismiss completed island: ${e.message}")
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
                        lastContentHash = newContentHash,
                        isGroup = isGroup
                    )
                    activeRoutes[groupKey] = IslandRoute.MIUI_BRIDGE

                    // FIX: Do NOT emit overlay event when MIUI bridge is active
                    // This was causing TWO islands (MIUI mini + HyperIsle overlay) for the same notification
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_ISLAND,
                            "RID=$rid EVT=ROUTE_FINAL route=MIUI_BRIDGE reason=MIUI_BRIDGE_ACTIVE type=${type.name} pkg=${sbn.packageName}"
                        )
                    }
                }
                IslandRoute.APP_OVERLAY -> {
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_ISLAND,
                            "RID=$rid EVT=ROUTE_FINAL route=APP_OVERLAY reason=OVERLAY_SELECTED type=${type.name} pkg=${sbn.packageName}"
                        )
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
                            lastContentHash = newContentHash,
                            isGroup = isGroup
                        )
                        activeRoutes[groupKey] = IslandRoute.APP_OVERLAY
                    }
                }
                null -> {
                    // Fallback to system notification when overlay is unavailable.
                }
            }

        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_NOTIF, "Error", emptyMap(), e)
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
        HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=BRIDGE_POST_TRY pkg=${sbn.packageName} bridgeId=$bridgeId type=$notificationType")

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
                HiLog.d(HiLog.TAG_NOTIF, "event=tapOpenSetup pkg=${sbn.packageName} keyHash=${sbn.key.hashCode()} bridgeId=$bridgeId")
            }
        }

        val notification = notificationBuilder.build()
        notification.extras.putString("miui.focus.param", data.jsonParam)

        try {
            NotificationManagerCompat.from(this).notify(bridgeId, notification)
        } catch (e: Exception) {
            HiLog.d(HiLog.TAG_ISLAND,
                "RID=$keyHash EVT=BRIDGE_POST_FAIL pkg=${sbn.packageName} bridgeId=$bridgeId reason=${e.javaClass.simpleName}"
            )
            throw e
        }
        bridgePostConfirmations[sbn.key] = System.currentTimeMillis()
        HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=BRIDGE_POST_OK pkg=${sbn.packageName} bridgeId=$bridgeId")
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
            HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} STAGE=ADD ACTION=ISLAND_SHOWN pkg=${sbn.packageName} type=$notificationType bridgeId=$bridgeId")
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
        HiLog.d(HiLog.TAG_NOTIF, "ISLAND_SHOWN", mapOf(
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
        // CALL: Check calls-only-island setting
        // NAVIGATION: Always suppress to avoid system islands
        // Other types (MEDIA, PROGRESS, TIMER): Preserve existing behavior
        val shadeCancelEnabled = when (type) {
            NotificationType.STANDARD -> true  // Always intercept messages     
            NotificationType.CALL -> preferences.isCallsOnlyIslandEnabled()  // Check setting for calls
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
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash STAGE=HIDE_SYS_NOTIF ACTION=DECISION cancelShade=${decision.cancelShade} allowed=${decision.cancelShadeAllowed} eligible=${decision.cancelShadeEligible} safe=${decision.cancelShadeSafe} reason=${decision.ineligibilityReason ?: "OK"} pkg=$pkg")
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

        HiLog.d(HiLog.TAG_ISLAND,
            "RID=$keyHash EVT=CANCEL_GUARD reason=$guardReason decision={clearable=$clearable,eligible=${decision.cancelShadeEligible},safe=${decision.cancelShadeSafe},routeConfirmed=$routeConfirmed} result=$guardResult pkg=$pkg"
        )

        // v1.0.0: SHADE_CANCEL_GUARD - Log when shade cancel is enabled but cannot be performed
        // This helps identify apps where users should disable notifications in system settings
        // to avoid duplicate islands (MIUI/HyperOS often forces system notifications to show)
        if (decision.cancelShadeAllowed && !decision.cancelShade && type != NotificationType.MEDIA) {
            HiLog.d(HiLog.TAG_ISLAND,
                "RID=$keyHash EVT=SHADE_CANCEL_GUARD reason=$guardReason type=${type.name} pkg=$pkg"
            )
        }
        
        // MIUI conflict detection: Log when overlay is shown but cancel is skipped
        // This helps track potential MIUI mini-island appearances
        val isMessagingApp = ShadeCancelMode.MESSAGING_PACKAGES.contains(pkg)
        val isStashActive = ShadeCancelMode.isStashMode(shadeCancelMode)
        if (BuildConfig.DEBUG && isMessagingApp) {
            val bridgeSuppressed = route == IslandRoute.APP_OVERLAY && useMiuiBridgeIsland
            HiLog.d(HiLog.TAG_NOTIF,
                "EVT=MIUI_CONFLICT_CHECK pkg=$pkg bridgeSuppressed=$bridgeSuppressed overlayShown=${deliveryConfirmed} cancelPerformed=${decision.cancelShade} stashMode=$isStashActive"
            )
        }

        if (forceNavigationCancel) {
            if (!forceCancelReady) {
                HiLog.d(HiLog.TAG_ISLAND,
                    "RID=$keyHash EVT=SYS_NOTIF_CANCEL_SKIP reason=FORCE_NAV_GUARD pkg=$pkg"
                )
                return
            }
            val groupKey = sbnKeyToGroupKey[sbn.key]
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SYS_NOTIF_CANCEL_TRY mode=FORCE_NAV")
            try {
                delay(SYS_CANCEL_POST_DELAY_MS)
                cancelNotification(sbn.key)
                markSelfCancel(sbn.key, keyHash, sbn.packageName, sbn.id)
                if (groupKey != null) {
                    startMinVisibleTimer(groupKey, keyHash)
                }
                HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SYS_NOTIF_CANCEL_OK mode=FORCE_NAV")
            } catch (e: Exception) {
                selfCancelKeys.remove(sbn.key)
                HiLog.w(HiLog.TAG_NOTIF, "Force navigation cancel failed: ${e.message}")
                HiLog.d(HiLog.TAG_ISLAND,
                    "RID=$keyHash EVT=SYS_NOTIF_CANCEL_FAIL mode=FORCE_NAV reason=${e.javaClass.simpleName}"
                )
            }
            return
        }

        if (decision.cancelShade && !routeConfirmed) {
            val skipReason = if (route == IslandRoute.MIUI_BRIDGE) "BRIDGE_NOT_CONFIRMED" else "OVERLAY_NOT_CONFIRMED"
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=CANCEL_SKIPPED_$skipReason")
            return
        }
        
        // STASH mode: Log when cancel is skipped due to stash policy
        if (!decision.cancelShade && isStashActive && type == NotificationType.STANDARD) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "EVT=NOTIF_CANCEL_SKIPPED pkg=$pkg reason=STASH_ENABLED mode=${shadeCancelMode.name}")
            }
        }
        
        // Only cancel/snooze if all conditions are met
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
            
            // For STANDARD notifications: use snooze to keep in status bar (iOS-like stacking)
            // For other types (NAVIGATION, etc.): use cancel as before
            val useSnoozeForStacking = type == NotificationType.STANDARD && clearable
            
            try {
                delay(SYS_CANCEL_POST_DELAY_MS)
                if (useSnoozeForStacking) {
                    // Snooze briefly - notification will reappear silently in status bar
                    snoozeNotification(sbn.key, POPUP_SUPPRESS_SNOOZE_MS)
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SYS_NOTIF_SNOOZE_OK snoozeMs=$POPUP_SUPPRESS_SNOOZE_MS")
                } else {
                    cancelNotification(sbn.key)
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SYS_NOTIF_CANCEL_OK")
                }
                markSelfCancel(sbn.key, keyHash, sbn.packageName, sbn.id)
                if (groupKey != null) {
                    startMinVisibleTimer(groupKey, keyHash)
                }
                if (BuildConfig.DEBUG) {
                    val action = if (useSnoozeForStacking) "SNOOZE_OK" else "CANCEL_OK"
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash STAGE=HIDE_SYS_NOTIF ACTION=$action pkg=$pkg")
                    HiLog.d(HiLog.TAG_NOTIF, "event=shadeHandled pkg=$pkg keyHash=$keyHash method=${if (useSnoozeForStacking) "snooze" else "cancel"}")
                    IslandRuntimeDump.recordEvent(
                        ctx = ProcCtx.synthetic(pkg, "shadeCancel"),
                        stage = "HIDE_SYS_NOTIF",
                        action = action,
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
                        evt = "SYS_NOTIF_${if (useSnoozeForStacking) "SNOOZE" else "CANCEL"}_OK",
                        route = IslandUiSnapshotLogger.Route.SYSTEM_NOTIFICATION
                    )
                }
            } catch (e: Exception) {
                selfCancelKeys.remove(sbn.key)
                val method = if (useSnoozeForStacking) "snooze" else "cancel"
                HiLog.w(HiLog.TAG_NOTIF, "Failed to $method notification from shade: ${e.message}")
                HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SYS_NOTIF_${method.uppercase()}_FAIL reason=${e.javaClass.simpleName}")
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash STAGE=HIDE_SYS_NOTIF ACTION=${method.uppercase()}_FAIL reason=${e.message} pkg=$pkg")
                    IslandRuntimeDump.recordEvent(
                        ctx = ProcCtx.synthetic(pkg, "shadeCancel"),
                        stage = "HIDE_SYS_NOTIF",
                        action = "${method.uppercase()}_FAIL",
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
                        evt = "SYS_NOTIF_${method.uppercase()}_FAIL",
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
            HiLog.w(HiLog.TAG_NOTIF, "Failed to insert digest item: ${e.message}")
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
                    HiLog.d(HiLog.TAG_ISLAND,
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
            HiLog.d(HiLog.TAG_ISLAND,
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
            HiLog.w(HiLog.TAG_NOTIF, "Shade cancel hint post blocked: ${e.message}")
            HiLog.d(HiLog.TAG_ISLAND,
                "RID=$keyHash EVT=SHADE_CANCEL_HINT_SKIP reason=SECURITY_EXCEPTION pkg=$pkg"
            )
            return
        }
        HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SHADE_CANCEL_HINT_SHOWN reason=NOT_CLEARABLE pkg=$pkg")
    }

    private suspend fun attemptAggressiveShadeCancel(
        sbn: StatusBarNotification,
        type: NotificationType,
        keyHash: Int
    ) {
        val pkg = sbn.packageName
        val groupKey = sbnKeyToGroupKey[sbn.key]
        HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SYS_NOTIF_CANCEL_TRY mode=AGGRESSIVE_TRY")

        try {
            delay(SYS_CANCEL_POST_DELAY_MS)
            cancelNotification(sbn.key)
            markSelfCancel(sbn.key, keyHash, sbn.packageName, sbn.id)
            if (groupKey != null) {
                startMinVisibleTimer(groupKey, keyHash)
            }
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SYS_NOTIF_CANCEL_OK mode=AGGRESSIVE_TRY")
        } catch (e: Exception) {
            selfCancelKeys.remove(sbn.key)
            HiLog.w(HiLog.TAG_NOTIF, "Aggressive cancel failed: ${e.message}")
            HiLog.d(HiLog.TAG_NOTIF, "RID=$keyHash EVT=SYS_NOTIF_CANCEL_FAIL mode=AGGRESSIVE_TRY reason=${e.javaClass.simpleName}")
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
                        HiLog.w(HiLog.TAG_NOTIF, "SecurityException during notify: ${se.message}")
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
                    HiLog.w(HiLog.TAG_NOTIF, "Failed to update call timer: ${e.message}")
                    continue
                }
            }
        }
    }

    private fun shouldIgnore(packageName: String): Boolean {
        // Allow self-notifications in debug builds for diagnostics lab testing
        if (BuildConfig.DEBUG && packageName == this.packageName) {
            return false
        }
        
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
            if (BuildConfig.DEBUG) {
                HiLog.d("HyperIsleAnchor", "RID=$rid EVT=NOTIF_OVERLAY_DECISION chosen=SKIP_OVERLAY reason=OVERLAY_NOT_SUPPORTED type=$type pkg=${sbn.packageName}")
            }
            return false
        }

        if (!shouldRenderOverlay(type, rid)) {
            if (BuildConfig.DEBUG) {
                HiLog.d("HyperIsleAnchor", "RID=$rid EVT=NOTIF_OVERLAY_DECISION chosen=SKIP_OVERLAY reason=SHOULD_NOT_RENDER type=$type pkg=${sbn.packageName}")
            }
            return false
        }

        // Skip if overlay permission not granted
        if (!OverlayPermissionHelper.hasOverlayPermission(applicationContext)) {
            if (BuildConfig.DEBUG) {
                HiLog.d("HyperIsleAnchor", "RID=$rid EVT=NOTIF_OVERLAY_DECISION chosen=SKIP_OVERLAY reason=NO_OVERLAY_PERMISSION pkg=${sbn.packageName}")
                HiLog.d(HiLog.TAG_NOTIF, "Overlay permission not granted, skipping pill overlay")
            }
            return false
        }

        if (!OverlayPermissionHelper.startOverlayServiceIfPermitted(applicationContext)) {
            if (BuildConfig.DEBUG) {
                HiLog.d("HyperIsleAnchor", "RID=$rid EVT=NOTIF_OVERLAY_DECISION chosen=SKIP_OVERLAY reason=SERVICE_UNAVAILABLE pkg=${sbn.packageName}")
            }
            HiLog.w(HiLog.TAG_NOTIF, "Overlay service unavailable, skipping pill overlay")
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
                    
                    if (BuildConfig.DEBUG) {
                        HiLog.d("HyperIsleAnchor", "RID=$rid EVT=NOTIF_OVERLAY_DECISION chosen=SHOW_OVERLAY reason=STANDARD_NOTIF pkg=${sbn.packageName}")
                    }
                    
                    OverlayEventBus.emitNotification(notifModel)
                }
                NotificationType.NAVIGATION -> {
                    emitNavigationOverlay(sbn)
                }
                else -> false
            }
        } catch (e: Exception) {
            HiLog.w(HiLog.TAG_NOTIF, "Failed to emit overlay event: ${e.message}")
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
            HiLog.d(HiLog.TAG_ISLAND,
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
            HiLog.d(HiLog.TAG_ISLAND,
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
            HiLog.d(HiLog.TAG_ISLAND,
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
                    HiLog.d(HiLog.TAG_ISLAND,
                        "RID=${sbn.key.hashCode()} EVT=OVERLAY_FG_FALLBACK source=ACCESSIBILITY fg=$accForeground pkg=${sbn.packageName}"
                    )
                }
                if (isForeground || isDialerForeground) {
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_ISLAND,
                            "RID=${sbn.key.hashCode()} EVT=OVERLAY_SKIP reason=CALL_UI_FOREGROUND pkg=${sbn.packageName} fg=$accForeground"
                        )
                    }
                    callOverlayVisibility[groupKey] = false
                    return false
                }
            } else if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND,
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
                HiLog.d(HiLog.TAG_ISLAND,
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
            HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=OVERLAY_SKIP reason=SCREEN_OFF")
            return false
        }

        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        if (isKeyguardLocked && type != NotificationType.CALL) {
            HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=OVERLAY_SKIP reason=KEYGUARD_LOCKED")
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
        val rid = sbn.key.hashCode()
        val actions = sbn.notification.actions
        
        // Log action count for debugging
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=REPLY_ACTION_SCAN pkg=${sbn.packageName} actionCount=${actions?.size ?: 0}")
        }
        
        if (actions == null || actions.isEmpty()) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=REPLY_ACTION_DETECTED pkg=${sbn.packageName} available=false reason=NO_ACTIONS")
            }
            return null
        }
        
        val candidates = actions.filter { action ->
            val inputs = action.remoteInputs
            inputs != null && inputs.isNotEmpty() && action.actionIntent != null
        }
        
        if (candidates.isEmpty()) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=REPLY_ACTION_DETECTED pkg=${sbn.packageName} available=false reason=NO_REMOTE_INPUTS")
            }
            return null
        }

        val replyAction = candidates.firstOrNull { action ->
            action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
        } ?: candidates.first()

        val remoteInputs = replyAction.remoteInputs ?: return null
        val pendingIntent = replyAction.actionIntent ?: return null
        val label = replyAction.title?.toString()?.trim()?.ifBlank { null }
            ?: getString(R.string.overlay_reply)
        
        // Log successful extraction
        if (BuildConfig.DEBUG) {
            val isSemantic = replyAction.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
            HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=REPLY_ACTION_DETECTED pkg=${sbn.packageName} available=true inputs=${remoteInputs.size} semantic=$isSemantic label=$label")
        }
        
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

        // FIX#1: Collect all action intents first, then apply keyword matching
        // MIUI/HyperOS may send icon-only actions (title blank) - don't skip them
        val allActionIntents = mutableListOf<PendingIntent>()
        
        for (action in actions) {
            val actionTitle = action.title?.toString()?.lowercase(java.util.Locale.getDefault())
            val actionIntent = action.actionIntent
            
            // Collect all valid intents for heuristic fallback
            if (actionIntent != null) {
                allActionIntents.add(actionIntent)
            }
            
            // Skip keyword matching if title is blank, but continue to collect intents
            if (actionTitle.isNullOrBlank()) continue
            
            when {
                answerKeywords.any { actionTitle.contains(it) } -> acceptIntent = actionIntent
                hangUpKeywords.any { actionTitle.contains(it) } -> {
                    declineIntent = actionIntent
                    hangUpIntent = actionIntent
                }
                speakerKeywords.any { actionTitle.contains(it) } -> speakerIntent = actionIntent
                muteKeywords.any { actionTitle.contains(it) } -> muteIntent = actionIntent
            }
        }

        // FIX#1: MIUI/HyperOS Icon-Only Heuristic for speaker/mute
        // HARD RULES: Only apply if ALL conditions are met:
        // 1. isOngoingCall == true (OFFHOOK state)
        // 2. hangUpIntent != null (we found hangup via keyword)
        // 3. speakerIntent == null && muteIntent == null (keywords didn't find them)
        // 4. Remaining action candidates (excluding hangup) == exactly 2
        if (isOngoingCall && hangUpIntent != null && speakerIntent == null && muteIntent == null) {
            val actionCandidates = allActionIntents.filter { intent ->
                intent != hangUpIntent
            }
            if (actionCandidates.size == 2) {
                // Heuristic: first = speaker, second = mute (MIUI convention)
                speakerIntent = actionCandidates[0]
                muteIntent = actionCandidates[1]
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} EVT=CALL_ACTION_HEURISTIC applied=true reason=ICON_ONLY_EXACT2 totalActions=${allActionIntents.size}")
                }
            } else {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} EVT=CALL_ACTION_HEURISTIC applied=false reason=CANDIDATE_COUNT_MISMATCH count=${actionCandidates.size} expected=2")
                }
            }
        }

        // Cache call actions if we found any, or restore from cache if missing
        val notificationKey = sbn.key
        if (hangUpIntent != null || speakerIntent != null || muteIntent != null) {
            // We have at least one action - cache them
            callActionCache[notificationKey] = CallActionCache(
                hangUpIntent = hangUpIntent,
                speakerIntent = speakerIntent,
                muteIntent = muteIntent
            )
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} EVT=CALL_ACTION_CACHE cached=${hangUpIntent != null}|${speakerIntent != null}|${muteIntent != null}")
            }
        } else {
            // No actions in this update - try to restore from cache
            val cached = callActionCache[notificationKey]
            if (cached != null) {
                hangUpIntent = cached.hangUpIntent
                speakerIntent = cached.speakerIntent
                muteIntent = cached.muteIntent
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} EVT=CALL_ACTION_RESTORE restored=${hangUpIntent != null}|${speakerIntent != null}|${muteIntent != null}")
                }
            }
        }

        val durationText = if (isOngoingCall) {
            resolveCallDurationText(extras, durationSeconds)
        } else {
            ""
        }

        val accentColor = com.coni.hyperisle.util.AccentColorResolver.getAccentColor(this, sbn.packageName)
        
        // BUG#1 FIX: Determine capabilities based on intent availability
        // AudioManager fallback only works reliably for speaker, not mute on all devices
        val canHangup = hangUpIntent != null || isOngoingCall // TelecomManager fallback available
        val canSpeaker = speakerIntent != null || isOngoingCall // AudioManager fallback available
        val canMute = muteIntent != null // NO reliable fallback - AudioManager.setMicrophoneMute often fails
        
        // HARDENING#1: Generate callKey from CALL SESSION, not notification
        // This ensures callKey is stable throughout a single call and changes for new calls
        // Priority: callHandle (callerName) + direction + elapsedRealtime
        val direction = if (isOngoingCall) "ONGOING" else "INCOMING"
        val callKey = com.coni.hyperisle.util.CallManager.getOrCreateCallKey(
            context = this,
            callHandle = callerName,
            direction = direction
        )
        
        // HARDENING#1: If callKey is empty, session is locked (call just ended) - don't build model
        if (callKey.isEmpty()) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} EVT=CALL_MODEL_BLOCKED reason=SESSION_LOCKED")
            }
            return null
        }
        
        // BUG#3 FIX: Get current audio state for UI feedback
        val isSpeakerOn = com.coni.hyperisle.util.CallManager.isSpeakerOn(this)
        val isMuted = com.coni.hyperisle.util.CallManager.isMuted(this)
        
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} EVT=CALL_MODEL_BUILD canHangup=$canHangup canSpeaker=$canSpeaker canMute=$canMute callKey=$callKey isSpeakerOn=$isSpeakerOn isMuted=$isMuted source=CALL_SESSION")
        }
        
        return IosCallOverlayModel(
            title = if (isOngoingCall) getString(R.string.call_ongoing) else getString(R.string.call_incoming),
            callerName = callerName,
            avatarBitmap = resolveOverlayBitmap(sbn),
            contentIntent = sbn.notification.contentIntent,
            acceptIntent = acceptIntent,
            declineIntent = declineIntent,
            hangUpIntent = hangUpIntent,
            speakerIntent = speakerIntent,
            muteIntent = muteIntent,
            durationText = durationText,
            state = if (isOngoingCall) CallOverlayState.ONGOING else CallOverlayState.INCOMING,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            accentColor = accentColor,
            canHangup = canHangup,
            canSpeaker = canSpeaker,
            canMute = canMute,
            isSpeakerOn = isSpeakerOn,
            isMuted = isMuted,
            callKey = callKey
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
        
        // Detect if this is video or music content
        val mediaType = MediaOverlayModel.detectMediaType(sbn.packageName)
        val isVideo = MediaOverlayModel.isVideoPackage(sbn.packageName)
        
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} EVT=MEDIA_TYPE_DETECT pkg=${sbn.packageName} type=${mediaType.name} isVideo=$isVideo")
        }
        
        val accentColor = com.coni.hyperisle.util.AccentColorResolver.getAccentColor(this, sbn.packageName)
        
        return MediaOverlayModel(
            title = displayTitle,
            subtitle = displaySubtitle,
            albumArt = art,
            actions = actions,
            contentIntent = sbn.notification.contentIntent,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            mediaType = mediaType,
            isVideo = isVideo,
            accentColor = accentColor
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
        val accentColor = com.coni.hyperisle.util.AccentColorResolver.getAccentColor(this, sbn.packageName)
        
        return TimerOverlayModel(
            label = label,
            baseTimeMs = baseTime,
            isCountdown = isCountdown,
            contentIntent = sbn.notification.contentIntent,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            accentColor = accentColor
        )
    }

    /**
     * Build NavigationOverlayModel from StatusBarNotification.
     */
    private fun buildNavigationOverlayModel(
        sbn: StatusBarNotification
    ): NavigationOverlayModel? {
        val extras = sbn.notification.extras
        val title = extras.getStringCompat(Notification.EXTRA_TITLE)?.replace("\n", " ")?.trim() ?: ""
        val text = extras.getStringCompat(Notification.EXTRA_TEXT)?.replace("\n", " ")?.trim() ?: ""
        val subText = extras.getStringCompat(Notification.EXTRA_SUB_TEXT)?.replace("\n", " ")?.trim() ?: ""
        
        val arrivalKeywords = resources.getStringArray(R.array.nav_arrival_keywords).toList()
        val timeRegex = Regex("\\d{1,2}:\\d{2}")
        
        fun isTimeInfo(s: String): Boolean = timeRegex.containsMatchIn(s) || arrivalKeywords.any { s.contains(it, true) }
        
        var instruction = ""
        var distance = ""
        var eta = ""
        
        if (isTimeInfo(subText)) eta = subText
        if (text.isNotEmpty() && title.isNotEmpty()) {
            if (eta.isEmpty()) {
                if (isTimeInfo(text)) { eta = text; instruction = title }
                else if (isTimeInfo(title)) { eta = title; instruction = text }
            }
            if (instruction.isEmpty()) {
                if (title.length >= text.length) { instruction = title; distance = text }
                else { instruction = text; distance = title }
            }
        } else {
            instruction = title.ifEmpty { text }
        }
        
        if (instruction.isEmpty()) instruction = getString(R.string.maps_title)
        
        val appIcon = getAppIconBitmap(sbn.packageName)
        val accentColor = com.coni.hyperisle.util.AccentColorResolver.getAccentColor(this, sbn.packageName)
        
        return NavigationOverlayModel(
            instruction = instruction,
            distance = distance,
            eta = eta,
            remainingTime = "",
            totalDistance = "",
            turnDistance = "",
            directionIcon = null,
            appIcon = appIcon,
            contentIntent = sbn.notification.contentIntent,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            islandSize = NavIslandSize.COMPACT,
            accentColor = accentColor
        )
    }

    /**
     * Emit navigation overlay event.
     */
    private fun emitNavigationOverlay(sbn: StatusBarNotification): Boolean {
        val rid = sbn.key.hashCode()
        if (!shouldRenderOverlay(NotificationType.NAVIGATION, rid)) return false
        if (!OverlayPermissionHelper.hasOverlayPermission(applicationContext)) return false
        if (!OverlayPermissionHelper.startOverlayServiceIfPermitted(applicationContext)) return false
        
        val navModel = buildNavigationOverlayModel(sbn) ?: return false
        val emitted = OverlayEventBus.emitNavigation(navModel)
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND,
                "RID=$rid EVT=NAV_OVERLAY_EMIT pkg=${sbn.packageName} result=${if (emitted) "OK" else "DROP"}"
            )
        }
        return emitted
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
        val rid = sbn.key.hashCode()
        val extras = sbn.notification.extras
        var iconSource = "NONE"
        
        // BUG#3 FIX: Use safe extraction that handles both Bitmap and Icon types
        // EXTRA_LARGE_ICON can contain either Bitmap or Icon depending on Android/OEM
        
        try {
            // 1. Try EXTRA_PICTURE (already a Bitmap)
            val picture = safeGetBitmapFromExtras(extras, Notification.EXTRA_PICTURE, rid, sbn.packageName)
            if (picture != null) {
                iconSource = "EXTRA_PICTURE"
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_RESOLVED source=$iconSource pkg=${sbn.packageName}")
                }
                return picture
            }
            
            // 2. Try EXTRA_LARGE_ICON - SAFE extraction handles both Bitmap and Icon
            val largeIconBitmap = safeGetBitmapFromExtras(extras, Notification.EXTRA_LARGE_ICON, rid, sbn.packageName)
            if (largeIconBitmap != null) {
                iconSource = "EXTRA_LARGE_ICON_BITMAP"
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_RESOLVED source=$iconSource pkg=${sbn.packageName}")
                }
                return largeIconBitmap
            }
            
            // 3. Try getLargeIcon() (returns Icon, needs conversion)
            val largeIcon = sbn.notification.getLargeIcon()
            if (largeIcon != null) {
                val bitmap = loadIconBitmapSafe(largeIcon, sbn.packageName, rid, "LARGE_ICON")
                if (bitmap != null) {
                    iconSource = "LARGE_ICON"
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_RESOLVED source=$iconSource pkg=${sbn.packageName}")
                    }
                    return bitmap
                }
            }
            
            // 4. Try smallIcon (returns Icon, needs conversion)
            val smallIcon = sbn.notification.smallIcon
            if (smallIcon != null) {
                val bitmap = loadIconBitmapSafe(smallIcon, sbn.packageName, rid, "SMALL_ICON")
                if (bitmap != null) {
                    iconSource = "SMALL_ICON"
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_RESOLVED source=$iconSource pkg=${sbn.packageName}")
                    }
                    return bitmap
                }
            }
            
            // 5. Fallback to app icon
            val appIcon = getAppIconBitmap(sbn.packageName)
            if (appIcon != null) {
                iconSource = "APP_ICON_FALLBACK"
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_RESOLVED source=$iconSource pkg=${sbn.packageName}")
                }
                return appIcon
            }
            
            // 6. No icon available
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_NONE pkg=${sbn.packageName}")
            }
            return null
            
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_FAIL exception=${e.javaClass.simpleName} msg=${e.message} pkg=${sbn.packageName}")
            // Ultimate fallback - try app icon even after exception
            return try {
                getAppIconBitmap(sbn.packageName)
            } catch (fallbackEx: Exception) {
                null
            }
        }
    }

    /**
     * BUG#3 FIX: Safely extract Bitmap from notification extras.
     * Handles the case where EXTRA_LARGE_ICON may contain either a Bitmap or an Icon,
     * preventing ClassCastException: Icon cannot be cast to Bitmap.
     */
    private fun safeGetBitmapFromExtras(extras: android.os.Bundle, key: String, rid: Int, pkg: String): Bitmap? {
        return try {
            val value = extras.get(key) ?: return null
            when (value) {
                is Bitmap -> {
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_EXTRACT_OK key=$key type=BITMAP pkg=$pkg")
                    }
                    value
                }
                is Icon -> {
                    // EXTRA_LARGE_ICON contains Icon instead of Bitmap - convert it
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_EXTRACT_CONVERT key=$key type=ICON pkg=$pkg")
                    }
                    loadIconBitmapSafe(value, pkg, rid, "EXTRA_$key")
                }
                else -> {
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_EXTRACT_SKIP key=$key type=${value.javaClass.simpleName} pkg=$pkg")
                    }
                    null
                }
            }
        } catch (e: ClassCastException) {
            // This should not happen now, but guard against it
            HiLog.e(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_EXTRACT_CAST_FAIL key=$key exception=${e.message} pkg=$pkg (handled=true)")
            null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_EXTRACT_FAIL key=$key exception=${e.javaClass.simpleName} pkg=$pkg")
            }
            null
        }
    }

    /**
     * Safely load Icon to Bitmap with proper type handling.
     * NEVER casts Icon to Bitmap directly - always uses loadDrawable().
     * Handles all Icon types: TYPE_RESOURCE, TYPE_BITMAP, TYPE_URI, TYPE_DATA, TYPE_ADAPTIVE_BITMAP.
     */
    private fun loadIconBitmapSafe(icon: Icon, packageName: String, rid: Int, source: String): Bitmap? {
        return try {
            val iconType = icon.type
            val iconTypeName = when (iconType) {
                Icon.TYPE_RESOURCE -> "RESOURCE"
                Icon.TYPE_BITMAP -> "BITMAP"
                Icon.TYPE_URI -> "URI"
                Icon.TYPE_DATA -> "DATA"
                Icon.TYPE_ADAPTIVE_BITMAP -> "ADAPTIVE"
                else -> "UNKNOWN($iconType)"
            }
            
            val drawable = when (iconType) {
                Icon.TYPE_RESOURCE -> {
                    // For resource icons, try target package context first
                    try {
                        val targetContext = createPackageContext(packageName, 0)
                        icon.loadDrawable(targetContext)
                    } catch (e: Exception) {
                        // Fallback to our context
                        icon.loadDrawable(this)
                    }
                }
                else -> {
                    // For all other types (BITMAP, URI, DATA, ADAPTIVE), use our context
                    icon.loadDrawable(this)
                }
            }
            
            if (drawable == null) {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_LOAD_NULL source=$source type=$iconTypeName pkg=$packageName")
                }
                return null
            }
            
            val bitmap = drawable.toBitmap()
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_LOAD_OK source=$source type=$iconTypeName w=${bitmap.width} h=${bitmap.height} pkg=$packageName")
            }
            bitmap
            
        } catch (e: ClassCastException) {
            // This should never happen with proper Icon handling, but guard against it
            HiLog.e(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_CAST_FAIL source=$source exception=${e.message} pkg=$packageName")
            null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=ICON_LOAD_FAIL source=$source exception=${e.javaClass.simpleName} pkg=$packageName")
            }
            null
        }
    }

    private fun loadIconBitmap(icon: Icon, packageName: String): Bitmap? {
        return loadIconBitmapSafe(icon, packageName, 0, "GENERIC")
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
        val accentColor = com.coni.hyperisle.util.AccentColorResolver.getAccentColor(this, sbn.packageName)
        
        // Detect media type from message content
        val mediaType = detectNotificationMediaType(text, sbn)
        val mediaBitmap = if (mediaType != com.coni.hyperisle.overlay.NotificationMediaType.NONE) {
            extractMediaBitmap(sbn)
        } else null
        
        // Adjust message text for media types
        val displayMessage = when (mediaType) {
            com.coni.hyperisle.overlay.NotificationMediaType.PHOTO -> if (text.isBlank()) "📷 Fotoğraf" else text
            com.coni.hyperisle.overlay.NotificationMediaType.VIDEO -> if (text.isBlank()) "🎬 Video" else text
            com.coni.hyperisle.overlay.NotificationMediaType.GIF -> if (text.isBlank()) "GIF" else text
            com.coni.hyperisle.overlay.NotificationMediaType.STICKER -> if (text.isBlank()) "Çıkartma" else text
            com.coni.hyperisle.overlay.NotificationMediaType.VOICE -> if (text.isBlank()) "🎤 Sesli mesaj" else text
            com.coni.hyperisle.overlay.NotificationMediaType.DOCUMENT -> if (text.isBlank()) "📄 Dosya" else text
            com.coni.hyperisle.overlay.NotificationMediaType.LOCATION -> if (text.isBlank()) "📍 Konum" else text
            com.coni.hyperisle.overlay.NotificationMediaType.CONTACT -> if (text.isBlank()) "👤 Kişi" else text
            else -> text
        }
        
        if (BuildConfig.DEBUG && mediaType != com.coni.hyperisle.overlay.NotificationMediaType.NONE) {
            HiLog.d(HiLog.TAG_NOTIF, "RID=${sbn.key.hashCode()} EVT=MEDIA_TYPE_DETECTED type=${mediaType.name} hasPreview=${mediaBitmap != null}")
        }
        
        return IosNotificationOverlayModel(
            sender = title.ifEmpty { appName },
            timeLabel = "now",
            message = displayMessage,
            avatarBitmap = resolveOverlayBitmap(sbn),
            contentIntent = sbn.notification.contentIntent,
            packageName = sbn.packageName,
            notificationKey = sbn.key,
            collapseAfterMs = effectiveCollapse,
            replyAction = replyAction,
            accentColor = accentColor,
            mediaType = mediaType,
            mediaBitmap = mediaBitmap
        )
    }
    
    /**
     * Detect media type from notification message content.
     * Handles WhatsApp, Telegram, and other messaging apps.
     */
    private fun detectNotificationMediaType(text: String, sbn: StatusBarNotification): com.coni.hyperisle.overlay.NotificationMediaType {
        val lowerText = text.lowercase()
        val extras = sbn.notification.extras
        
        // Check for EXTRA_PICTURE which indicates media content
        val hasPicture = extras.get(Notification.EXTRA_PICTURE) != null
        
        return when {
            // Photo indicators
            lowerText.contains("📷") || lowerText.contains("photo") || 
            lowerText.contains("fotoğraf") || lowerText.contains("resim") ||
            (hasPicture && !lowerText.contains("video") && !lowerText.contains("gif")) -> 
                com.coni.hyperisle.overlay.NotificationMediaType.PHOTO
            
            // Video indicators
            lowerText.contains("🎬") || lowerText.contains("video") -> 
                com.coni.hyperisle.overlay.NotificationMediaType.VIDEO
            
            // GIF indicators
            lowerText.contains("gif") -> 
                com.coni.hyperisle.overlay.NotificationMediaType.GIF
            
            // Sticker indicators
            lowerText.contains("sticker") || lowerText.contains("çıkartma") ||
            lowerText.contains("etiket") -> 
                com.coni.hyperisle.overlay.NotificationMediaType.STICKER
            
            // Voice message indicators
            lowerText.contains("🎤") || lowerText.contains("voice") || 
            lowerText.contains("sesli") || lowerText.contains("audio") -> 
                com.coni.hyperisle.overlay.NotificationMediaType.VOICE
            
            // Document indicators
            lowerText.contains("📄") || lowerText.contains("document") || 
            lowerText.contains("dosya") || lowerText.contains("file") -> 
                com.coni.hyperisle.overlay.NotificationMediaType.DOCUMENT
            
            // Location indicators
            lowerText.contains("📍") || lowerText.contains("location") || 
            lowerText.contains("konum") -> 
                com.coni.hyperisle.overlay.NotificationMediaType.LOCATION
            
            // Contact indicators
            lowerText.contains("👤") || lowerText.contains("contact") || 
            lowerText.contains("kişi") -> 
                com.coni.hyperisle.overlay.NotificationMediaType.CONTACT
            
            else -> com.coni.hyperisle.overlay.NotificationMediaType.NONE
        }
    }
    
    /**
     * Extract media preview bitmap from notification.
     */
    private fun extractMediaBitmap(sbn: StatusBarNotification): Bitmap? {
        val extras = sbn.notification.extras
        val rid = sbn.key.hashCode()
        
        // Try EXTRA_PICTURE first (contains image preview)
        val picture = safeGetBitmapFromExtras(extras, Notification.EXTRA_PICTURE, rid, sbn.packageName)
        if (picture != null) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=MEDIA_BITMAP_FOUND source=EXTRA_PICTURE")
            }
            return picture
        }
        
        // Try EXTRA_LARGE_ICON as fallback
        val largeIcon = safeGetBitmapFromExtras(extras, Notification.EXTRA_LARGE_ICON, rid, sbn.packageName)
        if (largeIcon != null) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_NOTIF, "RID=$rid EVT=MEDIA_BITMAP_FOUND source=EXTRA_LARGE_ICON")
            }
            return largeIcon
        }
        
        return null
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
