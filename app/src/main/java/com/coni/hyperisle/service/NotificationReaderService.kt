package com.coni.hyperisle.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import android.app.PendingIntent
import android.content.Intent
import com.coni.hyperisle.BuildConfig

class NotificationReaderService : NotificationListenerService() {

    private val TAG = "HyperIsleService"
    private val ISLAND_CHANNEL_ID = "hyper_isle_island_channel"

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // --- STATE ---
    private var allowedPackageSet: Set<String> = emptySet()
    private var currentMode = IslandLimitMode.MOST_RECENT
    private var appPriorityList = emptyList<String>()

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

    // --- CACHES ---
    // Replace Policy: groupKey -> ActiveIsland (instead of sbn.key)
    private val activeIslands = ConcurrentHashMap<String, ActiveIsland>()
    private val activeTranslations = ConcurrentHashMap<String, Int>()
    private val lastUpdateMap = ConcurrentHashMap<String, Long>()
    // Smart Silence: groupKey -> (timestamp, contentHash)
    private val lastShownMap = ConcurrentHashMap<String, Pair<Long, Int>>()
    // Map original sbn.key to groupKey for cleanup
    private val sbnKeyToGroupKey = ConcurrentHashMap<String, String>()

    // Call timer tracking: groupKey -> (startTime, timerJob)
    private val activeCallTimers = ConcurrentHashMap<String, Pair<Long, Job>>()

    private lateinit var database: AppDatabase

    private val UPDATE_INTERVAL_MS = 200L
    private val MAX_ISLANDS = 9

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
        serviceScope.launch { preferences.appPriorityListFlow.collectLatest { appPriorityList = it } }

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

        // Initialize ContextStateManager
        ContextStateManager.initialize(applicationContext)

        // Database for digest
        database = AppDatabase.getDatabase(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "HyperIsle Connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            if (shouldIgnore(it.packageName)) return

            // Check Global Junk + Blocked Terms
            if (isJunkNotification(it)) return

            if (isAppAllowed(it.packageName)) {
                if (shouldSkipUpdate(it)) return
                serviceScope.launch { processAndPost(it) }
            }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        sbn?.let {
            val key = it.key
            val groupKey = sbnKeyToGroupKey[key]
            
            // Debug-only logging (PII-free)
            if (BuildConfig.DEBUG) {
                val reasonName = mapRemovalReason(reason)
                Log.d(TAG, "event=onRemoved pkg=${it.packageName} keyHash=${key.hashCode()} reason=$reasonName")
            }
            
            if (groupKey != null && activeTranslations.containsKey(groupKey)) {
                val hyperId = activeTranslations[groupKey] ?: return
                
                // Debug-only: Log auto-dismiss for call islands
                if (BuildConfig.DEBUG && groupKey.endsWith(":CALL")) {
                    val dismissReason = when (reason) {
                        REASON_APP_CANCEL, REASON_CANCEL -> "CALL_ENDED"
                        REASON_CLICK -> "DIALER_OPENED"
                        else -> mapRemovalReason(reason)
                    }
                    Log.d(TAG, "event=autoDismiss reason=$dismissReason pkg=${it.packageName} keyHash=${key.hashCode()}")
                }
                
                try { NotificationManagerCompat.from(this).cancel(hyperId) } catch (e: Exception) {}

                activeIslands.remove(groupKey)
                activeTranslations.remove(groupKey)
                lastUpdateMap.remove(groupKey)
                lastShownMap.remove(groupKey)
                
                // Stop call timer if exists
                activeCallTimers[groupKey]?.second?.cancel()
                activeCallTimers.remove(groupKey)
            }
            sbnKeyToGroupKey.remove(key)
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
        val currTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val currText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val currSub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        if (currTitle != previousIsland.title || currText != previousIsland.text || currSub != previousIsland.subText) {
            lastUpdateMap[key] = now
            return false
        }

        if (now - lastTime < UPDATE_INTERVAL_MS) return true

        lastUpdateMap[key] = now
        return false
    }

    private fun isJunkNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras
        val pkg = sbn.packageName

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        // --- 1. CONTENT CHECKS ---
        if (title.isEmpty() && text.isEmpty() && subText.isEmpty()) return true

        // Package Name Leaks
        if (title.equals(pkg, ignoreCase = true) || text.equals(pkg, ignoreCase = true) || subText.equals(pkg, ignoreCase = true)) return true
        if (title.contains("com.google.android", ignoreCase = true)) return true

        // *** NEW: GLOBAL BLOCKLIST CHECK ***
        // If title or text contains any blocked word, ignore it.
        if (globalBlockedTerms.isNotEmpty()) {
            val content = "$title $text"
            if (globalBlockedTerms.any { term -> content.contains(term, ignoreCase = true) }) {
                return true
            }
        }

        // Placeholder Titles
        val appName = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { "" }
        if (title == appName && text.isEmpty() && subText.isEmpty()) return true

        // System Noise
        if (title.contains("running in background", true)) return true
        if (text.contains("tap for more info", true)) return true
        if (text.contains("displaying over other apps", true)) return true

        // --- 2. GROUP SUMMARIES ---
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return true

        // --- 3. PRIORITY PASS ---
        val hasProgress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val isSpecial = notification.category == Notification.CATEGORY_TRANSPORT ||
                notification.category == Notification.CATEGORY_CALL ||
                notification.category == Notification.CATEGORY_NAVIGATION ||
                extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true

        if (hasProgress || isSpecial) return false

        return false
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun processAndPost(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification.extras

            val title = extras.getString(Notification.EXTRA_TITLE) ?: sbn.packageName
            val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
            val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""

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

            val isCall = sbn.notification.category == Notification.CATEGORY_CALL
            val isNavigation = sbn.notification.category == Notification.CATEGORY_NAVIGATION ||
                    sbn.packageName.contains("maps") || sbn.packageName.contains("waze")
            val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val hasProgress = progressMax > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
            val chronometerBase = sbn.notification.`when`
            val isTimer = (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ||
                    sbn.notification.category == Notification.CATEGORY_ALARM ||
                    sbn.notification.category == Notification.CATEGORY_STOPWATCH) && chronometerBase > 0
            val isMedia = extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true

            // --- MUSIC ISLAND MODE HANDLING (unchanged) ---
            if (isMedia) {
                when (musicIslandMode) {
                    MusicIslandMode.SYSTEM_ONLY -> return
                    MusicIslandMode.BLOCK_SYSTEM -> {
                        if (musicBlockApps.contains(sbn.packageName)) {
                            try { cancelNotification(sbn.key) } catch (e: Exception) {
                                Log.w(TAG, "Failed to cancel media notification: ${e.message}")
                            }
                        }
                        return
                    }
                }
            }

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

            val isUpdate = activeIslands.containsKey(groupKey)

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

            val data: HyperIslandData = when (type) {
                NotificationType.CALL -> {
                    // Detect if call is ongoing (has chronometer shown and no answer action)
                    val isChronometerShown = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
                    val actions = sbn.notification.actions ?: emptyArray()
                    val answerKeywords = resources.getStringArray(R.array.call_keywords_answer).toList()
                    val hasAnswerAction = actions.any { action ->
                        val txt = action.title.toString().lowercase(java.util.Locale.getDefault())
                        answerKeywords.any { k -> txt.contains(k) }
                    }
                    val isOngoingCall = isChronometerShown && !hasAnswerAction
                    
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
                    
                    callTranslator.translate(sbn, picKey, finalConfig, durationSeconds)
                }
                NotificationType.NAVIGATION -> {
                    val navLayout = preferences.getEffectiveNavLayout(sbn.packageName).first()
                    navTranslator.translate(sbn, picKey, finalConfig, navLayout.first, navLayout.second)
                }
                NotificationType.TIMER -> timerTranslator.translate(sbn, picKey, finalConfig)
                NotificationType.PROGRESS -> progressTranslator.translate(sbn, title, picKey, finalConfig)
                else -> standardTranslator.translate(sbn, picKey, finalConfig, bridgeId)
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

            when (activityResult) {
                is IslandActivityStateMachine.ActivityResult.Completed -> {
                    postNotification(sbn, bridgeId, groupKey, type.name, data)
                    Haptics.hapticOnIslandSuccess(applicationContext)
                    serviceScope.launch {
                        delay(activityResult.timeoutMs)
                        try {
                            NotificationManagerCompat.from(this@NotificationReaderService).cancel(bridgeId)
                            activeIslands.remove(groupKey)
                            activeTranslations.remove(groupKey)
                            IslandActivityStateMachine.remove(groupKey)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to dismiss completed island: ${e.message}")
                        }
                    }
                }
                else -> {
                    postNotification(sbn, bridgeId, groupKey, type.name, data)
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

        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postNotification(sbn: StatusBarNotification, bridgeId: Int, groupKey: String, notificationType: String, data: HyperIslandData) {
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
            val tapOpenActionString = FocusActionHelper.buildActionString(FocusActionHelper.TYPE_TAP_OPEN, bridgeId)
            val tapOpenIntent = Intent(tapOpenActionString).apply {
                setPackage(packageName)
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

        NotificationManagerCompat.from(this).notify(bridgeId, notification)
        activeTranslations[groupKey] = bridgeId

        // Record shown for PriorityEngine burst tracking
        PriorityEngine.recordShown(sbn.packageName, notificationType)
        
        // v0.9.2: Record island shown timestamp for fast dismiss detection
        PriorityEngine.recordIslandShown(sbn.packageName)

        // Store island meta for per-island action handling
        IslandCooldownManager.setIslandMeta(bridgeId, sbn.packageName, notificationType)

        // Store last active island info for receiver usage (fallback)
        IslandCooldownManager.setLastActiveIsland(bridgeId, sbn.packageName, notificationType)

        // Haptic feedback when island is shown
        Haptics.hapticOnIslandShown(applicationContext)
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
                    NotificationManagerCompat.from(this).cancel(it.value.id)
                    activeIslands.remove(it.key)
                }
            }
            IslandLimitMode.PRIORITY -> {
                val newPriority = appPriorityList.indexOf(newPkg).takeIf { it != -1 } ?: 9999
                val lowestActiveEntry = activeIslands.maxByOrNull { entry ->
                    appPriorityList.indexOf(entry.value.packageName).takeIf { it != -1 } ?: 9999
                }
                if (lowestActiveEntry != null) {
                    val lowestPriority = appPriorityList.indexOf(lowestActiveEntry.value.packageName).takeIf { it != -1 } ?: 9999
                    if (newPriority < lowestPriority) {
                        NotificationManagerCompat.from(this).cancel(lowestActiveEntry.value.id)
                        activeIslands.remove(lowestActiveEntry.key)
                    }
                }
            }
        }
    }

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
                data = android.net.Uri.parse("package:$packageName")
            }
            android.app.PendingIntent.getActivity(
                this,
                packageName.hashCode(),
                settingsIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun startCallTimer(sbn: StatusBarNotification, groupKey: String, picKey: String, config: IslandConfig, startTime: Long): Job {
        return serviceScope.launch {
            while (true) {
                delay(1000) // Update every second
                
                // Check if call still exists
                if (!activeCallTimers.containsKey(groupKey)) break
                
                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
                val data = callTranslator.translate(sbn, picKey, config, durationSeconds)
                val bridgeId = activeTranslations[groupKey] ?: break
                
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
                    
                    NotificationManagerCompat.from(this@NotificationReaderService).notify(bridgeId, notification)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update call timer: ${e.message}")
                    break
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
        val isMedia = extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true

        return when {
            isCall -> NotificationType.CALL
            isNavigation -> NotificationType.NAVIGATION
            isTimer -> NotificationType.TIMER
            isMedia -> NotificationType.MEDIA
            hasProgress -> NotificationType.PROGRESS
            else -> NotificationType.STANDARD
        }
    }
}