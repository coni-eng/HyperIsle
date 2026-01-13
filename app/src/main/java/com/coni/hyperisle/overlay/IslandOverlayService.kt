package com.coni.hyperisle.overlay

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.graphics.Bitmap
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.debug.IslandRuntimeDump
import com.coni.hyperisle.debug.IslandUiSnapshotLogger
import com.coni.hyperisle.ui.components.ActiveCallCompactPill
import com.coni.hyperisle.ui.components.ActiveCallExpandedPill
import com.coni.hyperisle.ui.components.IncomingCallPill
import com.coni.hyperisle.ui.components.MediaDot
import com.coni.hyperisle.ui.components.MediaExpandedPill
import com.coni.hyperisle.ui.components.MediaPill
import com.coni.hyperisle.ui.components.MiniNotificationPill
import com.coni.hyperisle.ui.components.NotificationPill
import com.coni.hyperisle.ui.components.NotificationReplyPill
import com.coni.hyperisle.ui.components.TimerDot
import com.coni.hyperisle.ui.components.TimerPill
import com.coni.hyperisle.util.AccessibilityContextSignals
import androidx.compose.ui.graphics.asImageBitmap
import com.coni.hyperisle.util.AccessibilityContextState
import com.coni.hyperisle.util.CallManager
import com.coni.hyperisle.util.ContextStateManager
import com.coni.hyperisle.util.Haptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt
import android.telephony.TelephonyManager

/**
 * Foreground service that manages iOS-style pill overlays.
 * Listens to OverlayEventBus and displays appropriate pill overlays.
 */
class IslandOverlayService : Service() {

    companion object {
        private const val TAG = "IslandOverlayService"
        private const val CHANNEL_ID = "ios_pill_overlay_channel"
        private const val NOTIFICATION_ID = 9999

        const val ACTION_START = "com.coni.hyperisle.overlay.START"
        const val ACTION_STOP = "com.coni.hyperisle.overlay.STOP"

        private val CALL_FOREGROUND_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.incallui"
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var overlayController: OverlayWindowController

    // Current overlay state
    private var currentCallModel: IosCallOverlayModel? by mutableStateOf(null)
    private var currentNotificationModel: IosNotificationOverlayModel? by mutableStateOf(null)
    private var currentMediaModel: MediaOverlayModel? by mutableStateOf(null)
    private var currentTimerModel: TimerOverlayModel? by mutableStateOf(null)
    private var currentNavigationModel: NavigationOverlayModel? by mutableStateOf(null)
    private var isNotificationCollapsed: Boolean by mutableStateOf(false)
    private var autoCollapseJob: Job? = null
    private var stopForegroundJob: Job? = null
    private var isForegroundActive = false
    private var deferCallOverlay: Boolean = false
    
    // Track user-dismissed calls to prevent re-showing after swipe
    // Uses TTL-based dedupe: key -> dismissTime
    private val userDismissedCallKeys = mutableMapOf<String, Long>()
    private val CALL_DEDUPE_TTL_MS = 60_000L // 60 seconds - prevent re-showing after user swipe dismiss // 10 seconds TTL
    
    // Track removed call notifications to prevent late CALL_STATE events from re-showing
    // MIUI bridge may send CALL_STATE ONGOING even after notification is removed
    private val removedCallNotificationKeys = mutableMapOf<String, Long>()
    private val REMOVED_CALL_TTL_MS = 30_000L // 30 seconds TTL - prevent late CALL_STATE events after call ends
    
    // Call state tracking for lifecycle management
    private var lastCallState: CallManager.CallState = CallManager.CallState.IDLE
    private var callStateGuardJob: Job? = null
    private var isCallOverlayActive = false
    
    // BUG#2 FIX: Track when call ended to implement cooldown for stale MIUI bridge events
    // MIUI bridge may send ONGOING events even after CallManager reports IDLE
    private var lastCallEndTs: Long = 0L
    private val CALL_END_COOLDOWN_MS = 3000L // 3 seconds cooldown after call ends
    
    // BUG#2 FIX: Service-level call UI state - persists across model updates
    // Keyed by notificationKey, prevents flickering when MIUI bridge sends periodic updates
    private val callExpandedState = mutableMapOf<String, Boolean>()
    private val callMediaExpandedState = mutableMapOf<String, Boolean>()
    
    // HARDENING#4: Debounce for overlay show - callKey + overlayType based
    // Prevents multiple overlays for same call during CALL_UI_FOREGROUND → HOME transition
    private data class DebounceKey(val callKey: String, val overlayType: String)
    private val lastOverlayShowTs = mutableMapOf<DebounceKey, Long>()
    private val CALL_OVERLAY_DEBOUNCE_MS = 250L // Debounce window for same callKey+type
    
    // HARDENING#2: IDLE state lock - prevents stale events from recreating ONGOING state
    // Once CallManager reports IDLE, model stays null until NEW call event
    @Volatile
    private var idleStateLocked = false
    private var idleLockCallKey: String? = null
    
    // BUG#2 FIX: Cache lastStableCallKey for CALL_RESET - prevents "unknown_..." keys
    // Updated on every CALLKEY_USED event, used when CALL_RESET needs a valid key
    private var lastStableCallKey: String? = null
    private var pendingCallReset = false // Set when IDLE transition happens without valid key
    
    // HARDENING#3: Optimistic speaker/mute state tracking
    // UI shows optimistic state immediately, reverts if real state doesn't match within timeout
    private data class OptimisticAudioState(
        val isSpeakerOn: Boolean?,
        val isMuted: Boolean?,
        val appliedAtMs: Long
    )
    private var optimisticAudioState: OptimisticAudioState? = null
    private val OPTIMISTIC_REVERT_MS = 800L // Revert optimistic state after 800ms if no confirmation
    private var audioStateRevertJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=OVL_CREATE STAGE=LIFECYCLE ACTION=OVERLAY_SVC_CREATED")
            IslandRuntimeDump.recordOverlay(null, "SERVICE_CREATED", reason = "onCreate")
        }

        overlayController = OverlayWindowController(applicationContext)
        createNotificationChannel()
        startEventCollection()
        startCallStateGuard()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping service")
                // INSTRUMENTATION: Overlay service stop
                if (BuildConfig.DEBUG) {
                    Log.d("HyperIsleIsland", "RID=OVL_STOP STAGE=LIFECYCLE ACTION=OVERLAY_SVC_STOP")
                    IslandRuntimeDump.recordOverlay(null, "SERVICE_STOP", reason = "ACTION_STOP")
                }
                stopForeground(true)
                isForegroundActive = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                Log.d(TAG, "Starting foreground service")
                // INSTRUMENTATION: Overlay service start
                if (BuildConfig.DEBUG) {
                    Log.d("HyperIsleIsland", "RID=OVL_START STAGE=LIFECYCLE ACTION=OVERLAY_SVC_START")
                    IslandRuntimeDump.recordOverlay(null, "SERVICE_START", reason = "ACTION_START")
                }
                ensureForeground()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=OVL_DEST STAGE=LIFECYCLE ACTION=OVERLAY_SVC_DESTROYED")
            IslandRuntimeDump.recordOverlay(null, "SERVICE_DESTROYED", reason = "onDestroy")
        }
        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        callStateGuardJob?.cancel()
        overlayController.forceDismissOverlay("SERVICE_DESTROYED")
        serviceScope.cancel()
        isForegroundActive = false
        isCallOverlayActive = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_ios_overlay),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "iOS-style pill overlay service"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureForeground() {
        if (isForegroundActive) return
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        isForegroundActive = true
    }

    private fun scheduleStopIfIdle(reason: String) {
        stopForegroundJob?.cancel()
        stopForegroundJob = serviceScope.launch {
            delay(3000L)
            if (!overlayController.isShowing() &&
                currentCallModel == null &&
                currentNotificationModel == null &&
                currentMediaModel == null &&
                currentTimerModel == null
            ) {
                Log.d("HyperIsleIsland", "RID=OVL_STOP EVT=OVERLAY_SVC_STOP reason=$reason")
                stopForeground(true)
                isForegroundActive = false
                stopSelf()
            }
        }
    }

    private fun startEventCollection() {
        serviceScope.launch {
            OverlayEventBus.events.collectLatest { event ->
                handleOverlayEvent(event)
            }
        }
    }

    /**
     * Start call state guard to detect stuck overlays.
     * Runs every 2 seconds and force-dismisses overlay if call ended but overlay remains.
     */
    private fun startCallStateGuard() {
        callStateGuardJob = serviceScope.launch {
            while (isActive) {
                delay(2000L)
                val currentState = CallManager.getCallState(applicationContext)
                
                if (BuildConfig.DEBUG && currentState != lastCallState) {
                    Log.d("HI_CALL", "EVT=CALL_STATE_CHANGED old=${lastCallState.name} new=${currentState.name}")
                }
                
                // Detect stuck overlay: call ended but overlay still showing
                if (currentState == CallManager.CallState.IDLE && 
                    isCallOverlayActive && 
                    overlayController.isShowing()) {
                    val rid = currentCallModel?.notificationKey?.hashCode() ?: 0
                    val notifKey = currentCallModel?.notificationKey
                    if (BuildConfig.DEBUG) {
                        Log.d("HI_GUARD", "RID=$rid EVT=GUARD_CLEANUP reason=CALL_ENDED_OVERLAY_STUCK")
                    }
                    // BUG#2 FIX: Set call end timestamp for cooldown
                    lastCallEndTs = System.currentTimeMillis()
                    if (BuildConfig.DEBUG) {
                        Log.d("HI_CALL", "RID=$rid EVT=CALL_END_TS_SET ts=$lastCallEndTs cooldown=${CALL_END_COOLDOWN_MS}ms reason=GUARD_CLEANUP")
                    }
                    
                    // FIX#4: Clear all call-related state when call ends
                    notifKey?.let { key ->
                        callExpandedState.remove(key)
                        callMediaExpandedState.keys.filter { it.startsWith("${key}_") }
                            .forEach { callMediaExpandedState.remove(it) }
                        userDismissedCallKeys.remove(key)
                    }
                    
                    overlayController.forceDismissOverlay("GUARD_CLEANUP_CALL_ENDED")
                    currentCallModel = null
                    isCallOverlayActive = false
                    
                    // Restore other overlays if needed
                    if (currentMediaModel != null || currentTimerModel != null) {
                        showActivityOverlayFromState()
                    } else {
                        scheduleStopIfIdle("GUARD_CLEANUP")
                    }
                }
                
                // BUG#2 FIX: Detect transition from active call to IDLE (even if overlay is already hidden)
                // This ensures stale MIUI bridge events are ignored AND model is fully reset
                if (lastCallState != CallManager.CallState.IDLE && 
                    currentState == CallManager.CallState.IDLE) {
                    val rid = currentCallModel?.notificationKey?.hashCode() ?: 0
                    // FIX: Get stableCallKey from model first, fallback to cached lastStableCallKey
                    // NEVER use "unknown_..." - if no key available, set pendingCallReset
                    val stableCallKey = currentCallModel?.callKey 
                        ?: CallManager.getActiveSession()?.callKey 
                        ?: lastStableCallKey
                    val legacySbnKey = currentCallModel?.notificationKey
                    
                    // Set cooldown timestamp to ignore stale MIUI bridge events
                    lastCallEndTs = System.currentTimeMillis()
                    
                    // HARDENING#2: FULL MODEL RESET on IDLE transition
                    // This is the truth source - CallManager reports call ended
                    // LOCK the state so stale events cannot recreate ONGOING
                    idleStateLocked = true
                    
                    if (stableCallKey != null) {
                        idleLockCallKey = stableCallKey
                        if (BuildConfig.DEBUG) {
                            Log.d("HI_CALL", "RID=$rid EVT=CALL_RESET reason=CALL_MANAGER_IDLE stableCallKey=$stableCallKey legacySbnKey=$legacySbnKey idleStateLocked=true")
                        }
                        // Clear lastStableCallKey after successful reset
                        lastStableCallKey = null
                        pendingCallReset = false
                    } else {
                        // No valid key available - set pending reset flag
                        pendingCallReset = true
                        if (BuildConfig.DEBUG) {
                            Log.d("HI_CALL", "RID=$rid EVT=CALL_RESET_PENDING reason=NO_STABLE_KEY legacySbnKey=$legacySbnKey idleStateLocked=true")
                        }
                    }
                    
                    // Clear all call-related state
                    currentCallModel?.notificationKey?.let { key ->
                        callExpandedState.remove(key)
                        callMediaExpandedState.keys.filter { it.startsWith("${key}_") }
                            .forEach { callMediaExpandedState.remove(it) }
                        // Add to removed keys to prevent late MIUI bridge events
                        removedCallNotificationKeys[key] = lastCallEndTs
                    }
                    
                    // Reset model
                    currentCallModel = null
                    isCallOverlayActive = false
                }
                
                lastCallState = currentState
            }
        }
    }

    private fun handleOverlayEvent(event: OverlayEvent) {
        if (!overlayController.hasOverlayPermission()) {
            Log.w(TAG, "Overlay permission not granted, ignoring event: $event")
            // INSTRUMENTATION: Overlay permission denied
            if (BuildConfig.DEBUG) {
                Log.d("HyperIsleIsland", "RID=OVL_PERM STAGE=OVERLAY ACTION=PERM_DENIED reason=canDrawOverlays_false")
                IslandRuntimeDump.recordOverlay(null, "PERM_DENIED", reason = "canDrawOverlays_false")
                // UI Snapshot: OVERLAY_PERMISSION denied
                val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                    rid = IslandUiSnapshotLogger.rid(),
                    pkg = null,
                    type = "OVERLAY"
                )
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "OVERLAY_PERMISSION",
                    route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                    reason = "DENIED"
                )
            }
            return
        }

        when (event) {
            is OverlayEvent.CallEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = true)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            is OverlayEvent.NotificationEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = false)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            is OverlayEvent.MediaEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = false)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            is OverlayEvent.TimerEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = false)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            is OverlayEvent.NavigationEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = false)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            else -> Unit
        }

        when (event) {
            is OverlayEvent.CallEvent -> showCallOverlay(event.model)
            is OverlayEvent.MediaEvent -> showMediaOverlay(event.model)
            is OverlayEvent.TimerEvent -> showTimerOverlay(event.model)
            is OverlayEvent.NavigationEvent -> showNavigationOverlay(event.model)
            is OverlayEvent.NotificationEvent -> showNotificationOverlay(event.model)
            is OverlayEvent.DismissEvent -> dismissOverlay(event.notificationKey, "NOTIF_REMOVED")
            is OverlayEvent.DismissAllEvent -> {
                // BUG FIX: Guard dismissAllOverlays when call is ONGOING
                // Prevents flicker when dialer→home transition triggers DISMISS_ALL
                // FIX: Block if callState is OFFHOOK/RINGING regardless of hasActiveCallModel
                val callState = CallManager.getCallState(applicationContext)
                val isCallOngoing = callState == CallManager.CallState.OFFHOOK || 
                                    callState == CallManager.CallState.RINGING
                val hasActiveCallModel = currentCallModel != null && isCallOverlayActive
                
                if (isCallOngoing) {
                    // Block dismiss - call state is ongoing, regardless of model state
                    // activeCallModel may be null due to timing but call is still active
                    if (BuildConfig.DEBUG) {
                        val stableCallKey = currentCallModel?.callKey ?: lastStableCallKey ?: "unknown"
                        Log.d(
                            "HI_CALL",
                            "EVT=DISMISS_ALL_BLOCKED reason=CALL_STATE_ONGOING callState=${callState.name} stableCallKey=$stableCallKey hasActiveCallModel=$hasActiveCallModel"
                        )
                    }
                    // Don't dismiss - call is still active, this is a layout change not call end
                } else {
                    // Allow dismiss - call state is IDLE
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "HI_CALL",
                            "EVT=DISMISS_ALL_ALLOWED callState=${callState.name} hasActiveCallModel=$hasActiveCallModel"
                        )
                    }
                    dismissAllOverlays("DISMISS_ALL_EVENT")
                }
            }
        }
    }

    private fun canRenderOverlay(rid: Int, allowOnKeyguard: Boolean): Boolean {
        val screenOn = ContextStateManager.getEffectiveScreenOn(applicationContext)
        if (!screenOn) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_SKIP reason=SCREEN_OFF")
            return false
        }

        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        if (isKeyguardLocked && !allowOnKeyguard) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_SKIP reason=KEYGUARD_LOCKED")
            return false
        }

        return true
    }

    private fun showCallOverlay(model: IosCallOverlayModel) {
        val isIncoming = model.state == CallOverlayState.INCOMING
        val isOngoing = model.state == CallOverlayState.ONGOING
        val hasTimer = model.durationText.isNotEmpty()
        val wasCallOverlay =
            overlayController.currentEvent is OverlayEvent.CallEvent && overlayController.isShowing()
        val rid = model.notificationKey.hashCode()
        
        // HARDENING#4: Debounce overlay show - callKey + overlayType based
        // Prevents multiple overlays for same call during CALL_UI_FOREGROUND → HOME transition
        val now = System.currentTimeMillis()
        val overlayType = if (isOngoing) "ONGOING" else "INCOMING"
        val debounceKey = DebounceKey(model.callKey, overlayType)
        val lastShowTs = lastOverlayShowTs[debounceKey]
        
        // TELEMETRY: Log stableCallKey vs legacy sbn key for call tracking
        if (BuildConfig.DEBUG) {
            Log.d(
                "HI_CALL",
                "RID=$rid EVT=CALLKEY_USED stable=${model.callKey} legacySbnKey=${model.notificationKey} state=$overlayType"
            )
        }
        
        // BUG#2 FIX: Cache stableCallKey for CALL_RESET - prevents "unknown_..." keys
        lastStableCallKey = model.callKey
        
        // BUG#2 FIX: If pendingCallReset was set, finalize it now that we have a valid key
        if (pendingCallReset) {
            pendingCallReset = false
            if (BuildConfig.DEBUG) {
                Log.d("HI_CALL", "RID=$rid EVT=CALL_RESET_FINALIZED stableCallKey=${model.callKey}")
            }
        }
        
        if (lastShowTs != null) {
            val elapsed = now - lastShowTs
            if (elapsed < CALL_OVERLAY_DEBOUNCE_MS) {
                if (BuildConfig.DEBUG) {
                    Log.d("HI_CALL", "RID=$rid EVT=OVERLAY_DEBOUNCE callKey=${model.callKey} type=$overlayType elapsed=${elapsed}ms debounce=${CALL_OVERLAY_DEBOUNCE_MS}ms")
                }
                // Don't re-show - debounce in progress
                return
            }
        }
        // Update debounce tracking
        lastOverlayShowTs[debounceKey] = now
        // Cleanup old debounce entries (older than 5 seconds)
        lastOverlayShowTs.entries.removeIf { now - it.value > 5000L }

        // HARDENING#2: Check IDLE state lock - prevents stale events from recreating ONGOING state
        // If we locked the IDLE state for this callKey, reject the event
        if (idleStateLocked && isOngoing && model.callKey == idleLockCallKey) {
            if (BuildConfig.DEBUG) {
                Log.d("HI_CALL", "RID=$rid EVT=CALL_STALE_BLOCKED reason=IDLE_STATE_LOCKED callKey=${model.callKey}")
            }
            return
        }
        
        // HARDENING#2: Check if CallManager session is locked
        if (CallManager.isSessionLocked() && isOngoing) {
            if (BuildConfig.DEBUG) {
                Log.d("HI_CALL", "RID=$rid EVT=CALL_STALE_BLOCKED reason=SESSION_LOCKED callKey=${model.callKey}")
            }
            return
        }

        // BUG#2 FIX: Truth source check - CallManager is the authoritative source for call state
        // If CallManager reports IDLE, do NOT show call overlay regardless of MIUI bridge state
        val callManagerState = CallManager.getCallState(applicationContext)
        if (callManagerState == CallManager.CallState.IDLE && isOngoing) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HI_CALL",
                    "RID=$rid EVT=CALL_ENDED_TRUTH_SOURCE modelState=${model.state.name} callManagerState=IDLE action=IGNORE"
                )
            }
            // Don't show overlay - call has ended per truth source
            // Also check cooldown to prevent rapid re-showing
            val elapsed = System.currentTimeMillis() - lastCallEndTs
            if (lastCallEndTs > 0 && elapsed < CALL_END_COOLDOWN_MS) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HI_CALL",
                        "RID=$rid EVT=CALL_BRIDGE_STALE_IGNORED cooldownRemaining=${CALL_END_COOLDOWN_MS - elapsed}ms"
                    )
                }
            }
            return
        }
        
        // HARDENING#2: Clear IDLE lock if we're receiving a NEW call (different callKey or INCOMING)
        if (isIncoming || (isOngoing && model.callKey != idleLockCallKey)) {
            if (idleStateLocked && BuildConfig.DEBUG) {
                Log.d("HI_CALL", "RID=$rid EVT=IDLE_LOCK_CLEARED reason=NEW_CALL newKey=${model.callKey} oldKey=$idleLockCallKey")
            }
            idleStateLocked = false
            idleLockCallKey = null
        }

        // Check if notification was removed (call ended) - ignore late CALL_STATE events from MIUI bridge
        val removedTime = removedCallNotificationKeys[model.notificationKey]
        if (removedTime != null) {
            val elapsed = System.currentTimeMillis() - removedTime
            if (elapsed < REMOVED_CALL_TTL_MS) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=${model.notificationKey.hashCode()} EVT=CALL_SKIP_REMOVED ttl=${REMOVED_CALL_TTL_MS - elapsed}ms reason=NOTIF_ALREADY_REMOVED state=${model.state.name}"
                )
                return // Don't show - call notification was already removed
            } else {
                // TTL expired, remove tracking
                removedCallNotificationKeys.remove(model.notificationKey)
            }
        }
        
        // BUG#4 FIX: Check if user dismissed this call - don't re-show (with TTL dedupe)
        // IMPORTANT: Do NOT set currentCallModel when suppressing - this prevents Compose from re-rendering
        val dismissTime = userDismissedCallKeys[model.notificationKey]
        if (isOngoing && dismissTime != null) {
            val elapsed = System.currentTimeMillis() - dismissTime
            if (elapsed < CALL_DEDUPE_TTL_MS) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=${model.notificationKey.hashCode()} EVT=DEDUP_SUPPRESS_UPDATE ttl=${CALL_DEDUPE_TTL_MS - elapsed}ms reason=USER_DISMISSED state=${model.state.name}"
                )
                // BUG#4 FIX: Do NOT set currentCallModel here - keep it null so overlay doesn't render
                return
            } else {
                // TTL expired, remove from dismissed set
                userDismissedCallKeys.remove(model.notificationKey)
                Log.d(
                    "HyperIsleIsland",
                    "RID=${model.notificationKey.hashCode()} EVT=DEDUP_TTL_EXPIRED elapsed=${elapsed}ms"
                )
            }
        }

        if (deferCallOverlay && currentNotificationModel != null && isOngoing) {
            currentCallModel = model
            Log.d(
                "HyperIsleIsland",
                "RID=${model.notificationKey.hashCode()} EVT=CALL_DEFERRED reason=NOTIF_PEEK state=${model.state.name}"
            )
            return
        }
        deferCallOverlay = false

        // Cancel any auto-dismiss job (calls don't auto-dismiss)
        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        isNotificationCollapsed = false
        currentNotificationModel = null
        currentCallModel = model

        if (!wasCallOverlay) {
            Log.d(TAG, "Showing call overlay for: ${model.callerName}")
            // Haptic feedback when overlay is shown
            Haptics.hapticOnIslandShown(applicationContext)
            // INSTRUMENTATION: Overlay show call
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=${model.notificationKey.hashCode()} STAGE=OVERLAY ACTION=OVERLAY_SHOW type=CALL pkg=${model.packageName}"
                )
                IslandRuntimeDump.recordOverlay(
                    null,
                    "OVERLAY_SHOW",
                    reason = "CALL",
                    pkg = model.packageName,
                    overlayType = "CALL"
                )
                // UI Snapshot: OVERLAY_SHOW for call
                val slots = IslandUiSnapshotLogger.slotsCall(
                    hasAvatar = model.avatarBitmap != null,
                    hasCallerName = model.callerName.isNotEmpty(),
                    hasTimer = hasTimer,
                    actionLabels = if (isIncoming) listOf("decline", "accept") else listOf(
                        "hangup",
                        "speaker",
                        "mute"
                    ),
                    isIncoming = isIncoming,
                    isOngoing = isOngoing
                )
                val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                    rid = IslandUiSnapshotLogger.rid(),
                    pkg = model.packageName,
                    type = "CALL",
                    keyHash = model.notificationKey.hashCode().toString()
                )
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "OVERLAY_SHOW",
                    route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                    slots = slots
                )
            }

            Log.d(
                "HyperIsleIsland",
                "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_META type=CALL pkg=${model.packageName} state=${model.state.name} titleLen=${model.title.length} nameLen=${model.callerName.length} hasAvatar=${model.avatarBitmap != null} hasTimer=$hasTimer"
            )
        }

        if (wasCallOverlay) {
            return
        }

        // BUG#1 FIX: Determine interactive mode based on call state
        // IMPORTANT: ONGOING calls MUST be interactive so tap/long-press works on collapsed pill
        // Only RINGING state should be passive (non-interactive) because system may intercept
        // For OFFHOOK (active call), we NEED touch input to open call UI or expand pill
        val currentCallState = CallManager.getCallState(applicationContext)
        val isInteractive = when {
            isIncoming -> true  // INCOMING calls: interactive for accept/decline buttons
            isOngoing -> true   // BUG#1 FIX: ONGOING calls MUST be interactive for tap/long-press
            currentCallState == CallManager.CallState.RINGING -> false  // RINGING only: passive
            else -> true  // Default to interactive
        }
        
        if (BuildConfig.DEBUG) {
            Log.d(
                "HI_CALL",
                "RID=$rid EVT=CALL_OVERLAY_MODE interactive=$isInteractive callState=${currentCallState.name} modelState=${model.state.name}"
            )
            // Log touchability state for debugging
            Log.d(
                "HI_CALL",
                "RID=$rid EVT=CALL_OVERLAY_TOUCHABLE touchable=$isInteractive reason=${if (isOngoing) "ONGOING_MUST_BE_TOUCHABLE" else if (isIncoming) "INCOMING_BUTTONS" else "DEFAULT"}"
            )
        }
        
        isCallOverlayActive = true

        overlayController.showOverlay(OverlayEvent.CallEvent(model), interactive = isInteractive) {
            val activeModel = currentCallModel
            if (activeModel == null) {
                // Call ended - dismiss overlay
                LaunchedEffect(Unit) {
                    isCallOverlayActive = false
                    dismissAllOverlays("CALL_MODEL_NULL")
                }
                return@showOverlay
            }
            
            // BUG#3 FIX: Check if call notification was removed - don't render if in removed set
            val isRemoved = removedCallNotificationKeys[activeModel.notificationKey]?.let { removedTime ->
                (System.currentTimeMillis() - removedTime) < REMOVED_CALL_TTL_MS
            } ?: false
            if (isRemoved) {
                LaunchedEffect(Unit) {
                    if (BuildConfig.DEBUG) {
                        Log.d("HyperIsleIsland", "RID=${activeModel.notificationKey.hashCode()} EVT=RENDER_SKIP_REMOVED reason=CALL_NOTIF_REMOVED")
                    }
                    isCallOverlayActive = false
                    dismissAllOverlays("CALL_NOTIF_REMOVED")
                }
                return@showOverlay
            }
            
            // BUG#4 FIX: Check if user dismissed this call - don't render if in dismissed set
            val activeIsOngoing = activeModel.state == CallOverlayState.ONGOING
            val isUserDismissed = if (activeIsOngoing) {
                userDismissedCallKeys[activeModel.notificationKey]?.let { dismissTime ->
                    (System.currentTimeMillis() - dismissTime) < CALL_DEDUPE_TTL_MS
                } ?: false
            } else false
            if (isUserDismissed) {
                LaunchedEffect(Unit) {
                    if (BuildConfig.DEBUG) {
                        Log.d("HyperIsleIsland", "RID=${activeModel.notificationKey.hashCode()} EVT=RENDER_SKIP_DISMISSED reason=USER_DISMISSED")
                    }
                    isCallOverlayActive = false
                    dismissAllOverlays("USER_DISMISSED_RENDER")
                }
                return@showOverlay
            }
            // BUG#2 FIX: Use service-level state instead of Compose remember
            // This prevents flickering when MIUI bridge sends periodic CALL_STATE updates
            val notifKey = activeModel.notificationKey
            var isExpanded by remember(notifKey) {
                mutableStateOf(callExpandedState[notifKey] ?: false)
            }
            // Sync changes back to service-level state
            LaunchedEffect(isExpanded) {
                callExpandedState[notifKey] = isExpanded
            }
            val contextSignals by AccessibilityContextState.signals.collectAsState()
            val suppressOverlay = shouldSuppressOverlay(
                contextSignals = contextSignals,
                overlayPackage = activeModel.packageName,
                isCall = true
            )
            LaunchedEffect(suppressOverlay, contextSignals.foregroundPackage) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=${activeModel.notificationKey.hashCode()} EVT=OVERLAY_SUPPRESS state=${if (suppressOverlay) "ON" else "OFF"} type=CALL fg=${contextSignals.foregroundPackage ?: "unknown"} pkg=${activeModel.packageName}"
                    )
                }
                // FIX#3: When dialer is foreground, hard-hide overlay instead of rendering empty Spacer
                // Empty Spacer causes half-rendered/shifted overlay issue
                if (suppressOverlay) {
                    if (BuildConfig.DEBUG) {
                        Log.d("HI_CALL", "RID=${activeModel.notificationKey.hashCode()} EVT=OVERLAY_FORCE_HIDE reason=CALL_UI_FOREGROUND")
                    }
                    overlayController.forceDismissOverlay("CALL_UI_FOREGROUND")
                }
            }
            if (suppressOverlay) {
                return@showOverlay
            }

            val rid = activeModel.notificationKey.hashCode()
            val mediaModel = currentMediaModel
            // BUG#2 FIX: Use service-level state for media expanded too
            val mediaKey = "${notifKey}_${mediaModel?.notificationKey ?: "none"}"
            var isMediaExpanded by remember(mediaKey) {
                mutableStateOf(callMediaExpandedState[mediaKey] ?: false)
            }
            LaunchedEffect(isMediaExpanded) {
                callMediaExpandedState[mediaKey] = isMediaExpanded
            }
            LaunchedEffect(mediaModel?.notificationKey) {
                if (mediaModel == null) {
                    isMediaExpanded = false
                    // Clean up orphaned media states
                    callMediaExpandedState.keys.filter { it.startsWith("${notifKey}_") && it != mediaKey }
                        .forEach { callMediaExpandedState.remove(it) }
                }
            }
            val showSplit = activeIsOngoing && mediaModel != null && !isExpanded && !isMediaExpanded
            val layoutState = when {
                !activeIsOngoing -> "call_incoming"
                isExpanded -> "call_expanded"
                isMediaExpanded -> "media_expanded"
                showSplit -> "call_media_split"
                else -> "call_compact"
            }
            LaunchedEffect(layoutState) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=$rid EVT=OVERLAY_LAYOUT type=CALL state=$layoutState"
                    )
                }
            }
            val containerTap: (() -> Unit)? = when {
                activeIsOngoing && isExpanded -> {
                    { isExpanded = false }
                }
                isMediaExpanded -> {
                    { isMediaExpanded = false }
                }
                else -> null
            }
            val containerLongPress: (() -> Unit)? =
                if (activeIsOngoing && !showSplit && !isMediaExpanded) {
                    { isExpanded = true }
                } else {
                    null
                }

            SwipeDismissContainer(
                rid = rid,
                stateLabel = layoutState,
                onDismiss = { dismissFromUser("SWIPE_DISMISSED") },
                onTap = containerTap,
                onLongPress = containerLongPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                when {
                    !activeIsOngoing -> {
                        IncomingCallPill(
                            title = activeModel.title,
                            name = activeModel.callerName,
                            avatarBitmap = activeModel.avatarBitmap,
                            accentColor = activeModel.accentColor,
                            onDecline = if (isInteractive) {
                                {
                                    if (BuildConfig.DEBUG) {
                                        Log.d("HI_INPUT", "RID=$rid EVT=INPUT_DECLINE_CLICK")
                                    }
                                    Log.d(
                                        "HyperIsleIsland",
                                        "RID=$rid EVT=BTN_CALL_DECLINE_CLICK pkg=${activeModel.packageName}"
                                    )
                                    val result = handleCallAction(
                                        pendingIntent = activeModel.declineIntent,
                                        actionType = "decline",
                                        rid = rid,
                                        pkg = activeModel.packageName
                                    )
                                    val resultLabel = if (result) "OK" else "FAIL"
                                    Log.d(
                                        "HyperIsleIsland",
                                        "RID=$rid EVT=BTN_CALL_DECLINE_RESULT result=$resultLabel pkg=${activeModel.packageName}"
                                    )
                                    isCallOverlayActive = false
                                    dismissAllOverlays("CALL_DECLINE")
                                }
                            } else null,
                            onAccept = if (isInteractive) {
                                {
                                    if (BuildConfig.DEBUG) {
                                        Log.d("HI_INPUT", "RID=$rid EVT=INPUT_ACCEPT_CLICK")
                                    }
                                    Log.d(
                                        "HyperIsleIsland",
                                        "RID=$rid EVT=BTN_CALL_ACCEPT_CLICK pkg=${activeModel.packageName}"
                                    )
                                    val result = handleCallAction(
                                        pendingIntent = activeModel.acceptIntent,
                                        actionType = "accept",
                                        rid = rid,
                                        pkg = activeModel.packageName
                                    )
                                    val resultLabel = if (result) "OK" else "FAIL"
                                    Log.d(
                                        "HyperIsleIsland",
                                        "RID=$rid EVT=BTN_CALL_ACCEPT_RESULT result=$resultLabel pkg=${activeModel.packageName}"
                                    )
                                    // Don't dismiss - wait for system to send ongoing call notification
                                    // The overlay will transition to ongoing state when new notification arrives
                                }
                            } else null,
                            debugRid = rid
                        )
                    }
                    isExpanded -> {
                        // BUG#1 FIX: Pass capability flags - button disabled when false
                        // BUG#3 FIX: Pass audio state for UI feedback
                        ActiveCallExpandedPill(
                            callerLabel = activeModel.callerName,
                            durationText = activeModel.durationText,
                            onHangUp = {
                                handleCallAction(
                                    pendingIntent = activeModel.hangUpIntent,
                                    actionType = "hangup",
                                    rid = rid,
                                    pkg = activeModel.packageName
                                )
                            },
                            onSpeaker = {
                                handleCallAction(
                                    pendingIntent = activeModel.speakerIntent,
                                    actionType = "speaker",
                                    rid = rid,
                                    pkg = activeModel.packageName
                                )
                            },
                            onMute = {
                                handleCallAction(
                                    pendingIntent = activeModel.muteIntent,
                                    actionType = "mute",
                                    rid = rid,
                                    pkg = activeModel.packageName
                                )
                            },
                            canSpeaker = activeModel.canSpeaker,
                            canMute = activeModel.canMute,
                            isSpeakerOn = activeModel.isSpeakerOn,
                            isMuted = activeModel.isMuted,
                            debugRid = rid
                        )
                    }
                    isMediaExpanded && mediaModel != null -> {
                        MediaExpandedPill(
                            title = mediaModel.title,
                            subtitle = mediaModel.subtitle,
                            albumArt = mediaModel.albumArt,
                            actions = mediaModel.actions,
                            modifier = Modifier.fillMaxWidth(),
                            debugRid = rid
                        )
                    }
                    showSplit -> {
                        val media = mediaModel ?: return@SwipeDismissContainer
                        val gapWidth by animateDpAsState(
                            targetValue = 10.dp,
                            label = "call_media_gap"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            ActiveCallCompactPill(
                                callerLabel = activeModel.callerName,
                                durationText = activeModel.durationText,
                                modifier = Modifier
                                    .widthIn(min = 180.dp, max = 240.dp)
                                    .combinedClickable(
                                        onClick = {
                                            Log.d(
                                                "HyperIsleIsland",
                                                "RID=$rid EVT=SPLIT_TAP target=CALL pkg=${activeModel.packageName}"
                                            )
                                            handleNotificationTap(
                                                contentIntent = activeModel.contentIntent,
                                                rid = rid,
                                                pkg = activeModel.packageName
                                            )
                                        },
                                        onLongClick = {
                                            isMediaExpanded = false
                                            isExpanded = true
                                        }
                                    ),
                                fillMaxWidth = false,
                                debugRid = rid
                            )
                            Spacer(modifier = Modifier.width(gapWidth))
                            MediaDot(
                                albumArt = media.albumArt,
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        val mediaRid = media.notificationKey.hashCode()
                                        Log.d(
                                            "HyperIsleIsland",
                                            "RID=$mediaRid EVT=SPLIT_TAP target=MEDIA pkg=${media.packageName}"
                                        )
                                        handleNotificationTap(
                                            contentIntent = media.contentIntent,
                                            rid = mediaRid,
                                            pkg = media.packageName
                                        )
                                    },
                                    onLongClick = {
                                        isExpanded = false
                                        isMediaExpanded = true
                                    }
                                ),
                                debugRid = rid
                            )
                        }
                    }
                    else -> {
                        ActiveCallCompactPill(
                            callerLabel = activeModel.callerName,
                            durationText = activeModel.durationText,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    Log.d(
                                        "HyperIsleIsland",
                                        "RID=$rid EVT=CALL_COMPACT_TAP pkg=${activeModel.packageName}"
                                    )
                                    // BUG#1 FIX: Use TelecomManager to show in-call UI, do NOT dismiss
                                    // This ensures tap opens call UI without killing the overlay
                                    val opened = handleCallTap(rid, activeModel.packageName, activeModel.contentIntent)
                                    if (BuildConfig.DEBUG) {
                                        Log.d("HI_INPUT", "RID=$rid EVT=CALL_COMPACT_TAP_RESULT opened=$opened")
                                    }
                                    // DO NOT dismiss - call overlay should stay visible
                                    // User-dismissed calls are tracked separately via swipe
                                },
                                onLongClick = { isExpanded = true }
                            ),
                            debugRid = rid
                        )
                    }
                }
            }
        }
    }

    private fun showMediaOverlay(model: MediaOverlayModel) {
        currentMediaModel = model
        if (currentCallModel != null || currentNotificationModel != null) {
            return
        }
        showActivityOverlay(OverlayEvent.MediaEvent(model))
    }

    private fun showTimerOverlay(model: TimerOverlayModel) {
        currentTimerModel = model
        if (currentCallModel != null || currentNotificationModel != null) {
            return
        }
        showActivityOverlay(OverlayEvent.TimerEvent(model))
    }

    private fun showNavigationOverlay(model: NavigationOverlayModel) {
        Log.d(TAG, "Showing navigation overlay: ${model.instruction}")
        Haptics.hapticOnIslandShown(applicationContext)
        currentNavigationModel = model
        
        // Navigation has lower priority than calls and notifications
        if (currentCallModel != null || currentNotificationModel != null) {
            return
        }
        
        val rid = model.notificationKey.hashCode()
        if (BuildConfig.DEBUG) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid STAGE=OVERLAY ACTION=OVERLAY_SHOW type=NAVIGATION pkg=${model.packageName}"
            )
        }
        
        overlayController.showOverlay(OverlayEvent.NavigationEvent(model)) {
            NavigationOverlayContent()
        }
    }

    private fun showActivityOverlay(event: OverlayEvent) {
        if (currentMediaModel == null && currentTimerModel == null) {
            scheduleStopIfIdle("ACTIVITY_EMPTY")
            return
        }
        val isActivityOverlay =
            overlayController.currentEvent is OverlayEvent.MediaEvent ||
                overlayController.currentEvent is OverlayEvent.TimerEvent
        if (isActivityOverlay && overlayController.isShowing()) {
            return
        }

        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        isNotificationCollapsed = false
        currentNotificationModel = null
        deferCallOverlay = false

        val rid = when (event) {
            is OverlayEvent.MediaEvent -> event.model.notificationKey.hashCode()
            is OverlayEvent.TimerEvent -> event.model.notificationKey.hashCode()
            else -> 0
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_ACTIVITY_SHOW media=${currentMediaModel != null} timer=${currentTimerModel != null}"
            )
        }

        overlayController.showOverlay(event) {
            ActivityOverlayContent()
        }
    }

    private fun showActivityOverlayFromState() {
        val media = currentMediaModel
        val timer = currentTimerModel
        val event = when {
            media != null -> OverlayEvent.MediaEvent(media)
            timer != null -> OverlayEvent.TimerEvent(timer)
            else -> null
        } ?: return
        showActivityOverlay(event)
    }

    private fun showNotificationOverlay(model: IosNotificationOverlayModel) {
        Log.d(TAG, "Showing notification overlay from: ${model.sender}")
        // Haptic feedback when overlay is shown
        Haptics.hapticOnIslandShown(applicationContext)
        val rid = model.notificationKey.hashCode()
        val contextSignals = AccessibilityContextState.snapshot()
        val contextRestricted = contextSignals.isImeVisible
        val callState = currentCallModel?.state
        if (callState == CallOverlayState.INCOMING) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_SKIP reason=CALL_INCOMING pkg=${model.packageName}"
            )
            return
        }
        val restoreCallAfterDismiss = callState == CallOverlayState.ONGOING
        // BUG#5 FIX: Do NOT set deferCallOverlay - this was preventing call overlay from showing
        // Instead, we just show a quick notification peek and auto-restore call UI
        // The call overlay state machine should NOT be affected by notification peek
        if (contextRestricted) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_CONTEXT fullscreen=${contextSignals.isFullscreen} ime=${contextSignals.isImeVisible} fg=${contextSignals.foregroundPackage ?: "unknown"}"
            )
        }
        if (restoreCallAfterDismiss) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_PEEK reason=CALL_ONGOING pkg=${model.packageName}"
            )
        }
        Log.d(
            "HyperIsleIsland",
            "RID=$rid EVT=OVERLAY_META type=NOTIFICATION pkg=${model.packageName} senderLen=${model.sender.length} messageLen=${model.message.length} timeLen=${model.timeLabel.length} hasAvatar=${model.avatarBitmap != null} hasReplyAction=${model.replyAction != null}"
        )
        // INSTRUMENTATION: Overlay show notification
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=${model.notificationKey?.hashCode() ?: 0} STAGE=OVERLAY ACTION=OVERLAY_SHOW type=NOTIFICATION pkg=${model.packageName}")
            IslandRuntimeDump.recordOverlay(null, "OVERLAY_SHOW", reason = "NOTIFICATION", pkg = model.packageName, overlayType = "NOTIFICATION")
            // UI Snapshot: OVERLAY_SHOW for notification
            val slots = IslandUiSnapshotLogger.slotsOverlay(
                hasAvatar = model.avatarBitmap != null,
                hasSender = model.sender.isNotEmpty(),
                hasMessage = model.message.isNotEmpty(),
                hasTime = model.timeLabel.isNotEmpty(),
                overlayType = "notification"
            )
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = model.packageName,
                type = "NOTIFICATION",
                keyHash = model.notificationKey?.hashCode()?.toString()
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "OVERLAY_SHOW",
                route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                slots = slots
            )
        }

        // Cancel previous auto-collapse job
        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        if (!restoreCallAfterDismiss) {
            currentCallModel = null
        }
        currentNotificationModel = model
        isNotificationCollapsed = contextRestricted
        scheduleAutoCollapse(model, restoreCallAfterDismiss)

        overlayController.showOverlay(OverlayEvent.NotificationEvent(model)) {
            var isReplying by remember(model.notificationKey) { mutableStateOf(false) }
            var replyText by remember(model.notificationKey) { mutableStateOf("") }
            val replyAction = model.replyAction
            val allowExpand = !contextRestricted
            val allowReply = replyAction != null && !contextRestricted
            val contextState by AccessibilityContextState.signals.collectAsState()
            val suppressOverlay = shouldSuppressOverlay(
                contextSignals = contextState,
                overlayPackage = model.packageName,
                isCall = false
            )
            LaunchedEffect(suppressOverlay, contextState.foregroundPackage) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=$rid EVT=OVERLAY_SUPPRESS state=${if (suppressOverlay) "ON" else "OFF"} type=NOTIFICATION fg=${contextState.foregroundPackage ?: "unknown"} pkg=${model.packageName}"
                    )
                }
            }
            if (suppressOverlay) {
                Spacer(modifier = Modifier.height(0.dp))
                return@showOverlay
            }

            // FIX: Use wrapContentSize for normal mode to allow touch pass-through outside pill
            // Only use fillMaxSize when replying to capture background taps and dim screen
            if (isReplying) {
                // Reply mode: full screen dim background to capture outside taps
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NotificationOverlayContent(
                        model = model,
                        rid = rid,
                        isReplying = isReplying,
                        onReplyingChange = { isReplying = it },
                        replyText = replyText,
                        onReplyTextChange = { replyText = it },
                        allowExpand = allowExpand,
                        allowReply = allowReply,
                        restoreCallAfterDismiss = restoreCallAfterDismiss
                    )
                }
            } else {
                // Normal mode: wrapContentSize allows touch pass-through outside pill area
                Box(
                    modifier = Modifier
                        .wrapContentSize(unbounded = true)
                        .background(Color.Transparent),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NotificationOverlayContent(
                        model = model,
                        rid = rid,
                        isReplying = isReplying,
                        onReplyingChange = { isReplying = it },
                        replyText = replyText,
                        onReplyTextChange = { replyText = it },
                        allowExpand = allowExpand,
                        allowReply = allowReply,
                        restoreCallAfterDismiss = restoreCallAfterDismiss
                    )
                }
            }
        }
    }

    /**
     * Extracted notification overlay content to avoid code duplication between reply/normal modes.
     */
    @Composable
    private fun NotificationOverlayContent(
        model: IosNotificationOverlayModel,
        rid: Int,
        isReplying: Boolean,
        onReplyingChange: (Boolean) -> Unit,
        replyText: String,
        onReplyTextChange: (String) -> Unit,
        allowExpand: Boolean,
        allowReply: Boolean,
        restoreCallAfterDismiss: Boolean
    ) {
        val replyAction = model.replyAction
        
        SwipeDismissContainer(
            rid = rid,
            stateLabel = if (isNotificationCollapsed) "collapsed" else "expanded",
            onDismiss = { dismissFromUser("SWIPE_DISMISSED") },
            onTap = {
                if (isReplying) {
                    Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_IGNORED reason=REPLY_OPEN")
                    return@SwipeDismissContainer
                }
                if (isNotificationCollapsed) {
                    if (allowExpand) {
                        isNotificationCollapsed = false
                        scheduleAutoCollapse(model, restoreCallAfterDismiss)
                        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_EXPAND reason=TAP")
                    } else {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=$rid EVT=BTN_TAP_OPEN_CLICK reason=OVERLAY_CONTEXT pkg=${model.packageName}"
                        )
                        Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_TRIGGERED")
                        handleNotificationTap(model.contentIntent, rid, model.packageName)
                        Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_DISMISS_CALLED")
                        dismissFromUser("TAP_OPEN")
                    }
                } else {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=$rid EVT=BTN_TAP_OPEN_CLICK reason=OVERLAY pkg=${model.packageName}"
                    )
                    Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_TRIGGERED")
                    handleNotificationTap(model.contentIntent, rid, model.packageName)
                    Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_DISMISS_CALLED")
                    dismissFromUser("TAP_OPEN")
                }
            },
            onLongPress = if (allowReply) {
                {
                    if (isNotificationCollapsed) {
                        isNotificationCollapsed = false
                        scheduleAutoCollapse(model, restoreCallAfterDismiss)
                    }
                    onReplyingChange(true)
                    Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_LONG_PRESS")
                    Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_OPEN reason=LONG_PRESS")
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            LaunchedEffect(isNotificationCollapsed) {
                if (isNotificationCollapsed) {
                    if (isReplying) {
                        Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_CLOSE reason=COLLAPSE")
                    }
                    onReplyingChange(false)
                    onReplyTextChange("")
                    // Request layout update to ensure touch regions match mini pill size
                    overlayController.requestLayoutUpdate("MINI", rid)
                } else {
                    // Request layout update when expanding back
                    overlayController.requestLayoutUpdate("EXPANDED", rid)
                }
            }
            LaunchedEffect(isReplying) {
                overlayController.setFocusable(
                    isFocusable = isReplying,
                    reason = if (isReplying) "REPLY_OPEN" else "REPLY_CLOSE",
                    rid = rid
                )
                Log.d(
                    "HyperIsleIsland",
                    "RID=$rid EVT=REPLY_STATE state=${if (isReplying) "OPEN" else "CLOSED"}"
                )
            }

            // ANIMATION: Popup/dismiss animation from camera cutout area
            // Scale and alpha animation for smooth entry/exit
            var isVisible by remember { mutableStateOf(false) }
            val animatedScale by animateFloatAsState(
                targetValue = if (isVisible) 1f else 0.3f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "island_scale"
            )
            val animatedAlpha by animateFloatAsState(
                targetValue = if (isVisible) 1f else 0f,
                animationSpec = tween(durationMillis = 200),
                label = "island_alpha"
            )
            
            LaunchedEffect(Unit) {
                isVisible = true
            }
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        alpha = animatedAlpha
                        // Transform origin at top-center (camera cutout area)
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
            ) {
                if (isNotificationCollapsed) {
                    MiniNotificationPill(
                        sender = model.sender,
                        avatarBitmap = model.avatarBitmap,
                        onDismiss = {
                            Log.d("HyperIsleIsland", "RID=$rid EVT=BTN_RED_X_CLICK reason=OVERLAY")
                            dismissFromUser("BTN_RED_X")
                        },
                        debugRid = rid
                    )
                } else if (replyAction != null && isReplying) {
                    NotificationReplyPill(
                        sender = model.sender,
                        message = model.message,
                        avatarBitmap = model.avatarBitmap,
                        replyText = replyText,
                        onReplyChange = { onReplyTextChange(it) },
                        onSend = send@{
                            val trimmed = replyText.trim()
                            if (trimmed.isEmpty()) {
                                Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_FAIL reason=EMPTY_INPUT")
                                return@send
                            }
                            Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_TRY")
                            val result = sendInlineReply(replyAction, trimmed, rid)
                            if (result) {
                                Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_OK")
                                onReplyingChange(false)
                                onReplyTextChange("")
                                dismissFromUser("REPLY_SENT")
                            }
                        },
                        sendLabel = getString(R.string.overlay_send),
                        debugRid = rid
                    )
                } else {
                    NotificationPill(
                        sender = model.sender,
                        timeLabel = model.timeLabel,
                        message = model.message,
                        avatarBitmap = model.avatarBitmap,
                        accentColor = model.accentColor,
                        onDismiss = {
                            Log.d("HyperIsleIsland", "RID=$rid EVT=BTN_RED_X_CLICK reason=OVERLAY")
                            dismissFromUser("BTN_RED_X")
                        },
                        debugRid = rid
                    )
                }
            }
        }
    }

    @Composable
    private fun ActivityOverlayContent() {
        val mediaModel = currentMediaModel
        val timerModel = currentTimerModel
        val layoutState = when {
            mediaModel != null && timerModel != null -> "media_timer_split"
            mediaModel != null -> "media_only"
            timerModel != null -> "timer_only"
            else -> "idle"
        }
        val contextSignals by AccessibilityContextState.signals.collectAsState()
        val suppressOverlay = contextSignals.foregroundPackage?.let { fg ->
            (mediaModel?.packageName == fg) || (timerModel?.packageName == fg)
        } ?: false
        val rid = mediaModel?.notificationKey?.hashCode()
            ?: timerModel?.notificationKey?.hashCode()
            ?: 0
        LaunchedEffect(layoutState) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=$rid EVT=OVERLAY_LAYOUT type=ACTIVITY state=$layoutState"
                )
            }
        }
        LaunchedEffect(suppressOverlay, contextSignals.foregroundPackage) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=$rid EVT=OVERLAY_SUPPRESS state=${if (suppressOverlay) "ON" else "OFF"} type=ACTIVITY fg=${contextSignals.foregroundPackage ?: "unknown"}"
                )
            }
        }
        if (suppressOverlay || layoutState == "idle") {
            Spacer(modifier = Modifier.height(0.dp))
            return
        }
        var isMediaExpanded by remember(mediaModel?.notificationKey) { mutableStateOf(false) }
        LaunchedEffect(mediaModel?.notificationKey) {
            if (mediaModel == null) {
                isMediaExpanded = false
            }
        }
        val containerTap: (() -> Unit)? = if (isMediaExpanded) {
            { isMediaExpanded = false }
        } else {
            null
        }

        SwipeDismissContainer(
            rid = rid,
            stateLabel = layoutState,
            onDismiss = { dismissFromUser("SWIPE_DISMISSED") },
            onTap = containerTap,
            onLongPress = null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when {
                isMediaExpanded && mediaModel != null -> {
                    MediaExpandedPill(
                        title = mediaModel.title,
                        subtitle = mediaModel.subtitle,
                        albumArt = mediaModel.albumArt,
                        actions = mediaModel.actions,
                        modifier = Modifier.fillMaxWidth(),
                        debugRid = rid
                    )
                }
                mediaModel != null && timerModel != null -> {
                    val gapWidth by animateDpAsState(
                        targetValue = 10.dp,
                        label = "media_timer_gap"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        MediaPill(
                            title = mediaModel.title,
                            subtitle = mediaModel.subtitle,
                            albumArt = mediaModel.albumArt,
                            accentColor = mediaModel.accentColor,
                            modifier = Modifier
                                .widthIn(min = 200.dp, max = 260.dp)
                                .combinedClickable(
                                    onClick = {
                                        Log.d(
                                            "HyperIsleIsland",
                                            "RID=$rid EVT=SPLIT_TAP target=MEDIA pkg=${mediaModel.packageName}"
                                        )
                                        handleNotificationTap(
                                            contentIntent = mediaModel.contentIntent,
                                            rid = rid,
                                            pkg = mediaModel.packageName
                                        )
                                    },
                                    onLongClick = { isMediaExpanded = true }
                                ),
                            debugRid = rid
                        )
                        Spacer(modifier = Modifier.width(gapWidth))
                        TimerDot(
                            baseTimeMs = timerModel.baseTimeMs,
                            isCountdown = timerModel.isCountdown,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    val timerRid = timerModel.notificationKey.hashCode()
                                    Log.d(
                                        "HyperIsleIsland",
                                        "RID=$timerRid EVT=SPLIT_TAP target=TIMER pkg=${timerModel.packageName}"
                                    )
                                    handleNotificationTap(
                                        contentIntent = timerModel.contentIntent,
                                        rid = timerRid,
                                        pkg = timerModel.packageName
                                    )
                                }
                            ),
                            debugRid = rid
                        )
                    }
                }
                mediaModel != null -> {
                    MediaPill(
                        title = mediaModel.title,
                        subtitle = mediaModel.subtitle,
                        albumArt = mediaModel.albumArt,
                        accentColor = mediaModel.accentColor,
                        modifier = Modifier
                            .widthIn(min = 220.dp, max = 280.dp)
                            .combinedClickable(
                                onClick = {
                                    handleNotificationTap(
                                        contentIntent = mediaModel.contentIntent,
                                        rid = rid,
                                        pkg = mediaModel.packageName
                                    )
                                },
                                onLongClick = { isMediaExpanded = true }
                            ),
                        debugRid = rid
                    )
                }
                timerModel != null -> {
                    TimerPill(
                        label = timerModel.label,
                        baseTimeMs = timerModel.baseTimeMs,
                        isCountdown = timerModel.isCountdown,
                        accentColor = timerModel.accentColor,
                        modifier = Modifier
                            .widthIn(min = 160.dp, max = 220.dp)
                            .combinedClickable(
                                onClick = {
                                    val timerRid = timerModel.notificationKey.hashCode()
                                    handleNotificationTap(
                                        contentIntent = timerModel.contentIntent,
                                        rid = timerRid,
                                        pkg = timerModel.packageName
                                    )
                                }
                            ),
                        debugRid = rid
                    )
                }
            }
        }
    }

    @Composable
    private fun NavigationOverlayContent() {
        val navModel = currentNavigationModel
        if (navModel == null) {
            LaunchedEffect(Unit) {
                dismissAllOverlays("NAV_MODEL_NULL")
            }
            return
        }
        
        val rid = navModel.notificationKey.hashCode()
        val contextSignals by AccessibilityContextState.signals.collectAsState()
        val suppressOverlay = contextSignals.foregroundPackage == navModel.packageName
        
        LaunchedEffect(suppressOverlay) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=$rid EVT=OVERLAY_SUPPRESS state=${if (suppressOverlay) "ON" else "OFF"} type=NAVIGATION fg=${contextSignals.foregroundPackage ?: "unknown"}"
                )
            }
        }
        
        if (suppressOverlay) {
            Spacer(modifier = Modifier.height(0.dp))
            return
        }
        
        val isCompact = navModel.islandSize == NavIslandSize.COMPACT
        
        SwipeDismissContainer(
            rid = rid,
            stateLabel = if (isCompact) "nav_compact" else "nav_expanded",
            onDismiss = { dismissFromUser("SWIPE_DISMISSED") },
            onTap = {
                handleNotificationTap(
                    contentIntent = navModel.contentIntent,
                    rid = rid,
                    pkg = navModel.packageName
                )
            },
            onLongPress = null,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            NavigationPill(
                instruction = navModel.instruction,
                distance = navModel.distance,
                eta = navModel.eta,
                remainingTime = navModel.remainingTime,
                appIcon = navModel.appIcon,
                isCompact = isCompact,
                modifier = Modifier.widthIn(min = 200.dp, max = 340.dp),
                debugRid = rid
            )
        }
    }

    @Composable
    private fun NavigationPill(
        instruction: String,
        distance: String,
        eta: String,
        remainingTime: String,
        appIcon: Bitmap?,
        isCompact: Boolean,
        modifier: Modifier = Modifier,
        debugRid: Int = 0
    ) {
        androidx.compose.material3.Surface(
            color = Color.Black,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            modifier = modifier
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Direction icon or app icon
                if (appIcon != null) {
                    androidx.compose.foundation.Image(
                        bitmap = appIcon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(0xFF34C759), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text(
                            text = "→",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // Navigation info
                Column(modifier = Modifier.weight(1f)) {
                    androidx.compose.material3.Text(
                        text = instruction,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (!isCompact && remainingTime.isNotEmpty()) {
                        androidx.compose.material3.Text(
                            text = remainingTime,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
                
                // Distance and ETA
                Column(horizontalAlignment = Alignment.End) {
                    if (distance.isNotEmpty()) {
                        androidx.compose.material3.Text(
                            text = distance,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (eta.isNotEmpty()) {
                        androidx.compose.material3.Text(
                            text = eta,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    private fun scheduleAutoCollapse(
        model: IosNotificationOverlayModel,
        restoreCallAfterDismiss: Boolean
    ) {
        autoCollapseJob?.cancel()
        val collapseAfterMs = model.collapseAfterMs
        if (collapseAfterMs == 0L) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_AUTOCOLLAPSE_SKIP reason=STICKY"
                )
            }
            return
        }
        if (collapseAfterMs == null || collapseAfterMs < 0L) {
            // Default auto-dismiss after 5 seconds if no collapse time specified
            autoCollapseJob = serviceScope.launch {
                delay(5000L)
                if (currentNotificationModel?.notificationKey == model.notificationKey) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_DISMISS reason=AUTO_TIMEOUT"
                    )
                    dismissNotificationOverlay("AUTO_TIMEOUT", restoreCall = restoreCallAfterDismiss)
                }
            }
            return
        }

        autoCollapseJob = serviceScope.launch {
            // First: collapse after collapseAfterMs
            delay(collapseAfterMs)
            if (currentNotificationModel?.notificationKey == model.notificationKey) {
                if (restoreCallAfterDismiss) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_DISMISS reason=TIMEOUT"
                    )
                    dismissNotificationOverlay("TIMEOUT", restoreCall = true)
                } else if (!isNotificationCollapsed) {
                    isNotificationCollapsed = true
                    Log.d("HyperIsleIsland", "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_COLLAPSE reason=TIMEOUT")
                    
                    // Second: auto-dismiss 3 seconds after collapse
                    delay(3000L)
                    if (currentNotificationModel?.notificationKey == model.notificationKey && isNotificationCollapsed) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_DISMISS reason=COLLAPSE_TIMEOUT"
                        )
                        dismissNotificationOverlay("COLLAPSE_TIMEOUT", restoreCall = false)
                    }
                }
            }
        }
    }

    private fun sendInlineReply(action: IosNotificationReplyAction, message: String, rid: Int): Boolean {
        return try {
            val intent = Intent()
            val results = Bundle()
            action.remoteInputs.forEach { input ->
                results.putCharSequence(input.resultKey, message)
            }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            action.pendingIntent.send(this, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_FAIL reason=CANCELED")
            false
        } catch (e: Exception) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_FAIL reason=${e.javaClass.simpleName}")
            false
        }
    }

    private fun handleCallAction(
        pendingIntent: PendingIntent?,
        actionType: String,
        rid: Int,
        pkg: String?
    ): Boolean {
        Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_START action=$actionType hasIntent=${pendingIntent != null} pkg=$pkg")
        
        // Use enhanced CallManager for all call actions with AudioManager fallbacks
        val result = when (actionType) {
            "answer", "accept" -> {
                // PRIMARY: TelecomManager.acceptRingingCall()
                // FALLBACK: PendingIntent from notification
                val acceptResult = CallManager.acceptCall(applicationContext, pendingIntent)
                Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_RESULT action=$actionType success=${acceptResult.success} method=${acceptResult.method} pkg=$pkg")
                
                // Schedule verification if TelecomManager was used
                if (acceptResult.success && acceptResult.method == "TELECOM") {
                    serviceScope.launch {
                        val verified = CallManager.verifyCallAccepted(applicationContext)
                        Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACCEPT_VERIFIED verified=$verified pkg=$pkg")
                        if (!verified && pendingIntent != null) {
                            // Retry with PendingIntent if verification failed
                            Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACCEPT_RETRY method=PENDING_INTENT pkg=$pkg")
                            try {
                                pendingIntent.send()
                            } catch (e: Exception) {
                                Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACCEPT_RETRY_FAIL reason=${e.javaClass.simpleName}")
                            }
                        }
                    }
                }
                acceptResult.success
            }
            "hangup", "decline", "reject" -> {
                // PRIMARY: TelecomManager.endCall()
                // FALLBACK: PendingIntent from notification
                val endResult = CallManager.endCall(applicationContext, pendingIntent)
                Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_RESULT action=$actionType success=${endResult.success} method=${endResult.method} pkg=$pkg")
                endResult.success
            }
            "speaker" -> {
                // BUG#3 FIX: Get current state and compute desired toggle state
                val currentSpeaker = CallManager.isSpeakerOn(applicationContext)
                val desiredSpeaker = !currentSpeaker
                
                // Log click with desired state for debugging
                if (BuildConfig.DEBUG) {
                    Log.d("HI_CALL", "RID=$rid EVT=CALL_TOGGLE_CLICK action=speaker current=$currentSpeaker desired=$desiredSpeaker")
                }
                
                // HARDENING#3: Apply optimistic state immediately
                applyOptimisticAudioState(isSpeakerOn = desiredSpeaker, isMuted = null, rid = rid)
                
                // BUG#1 FIX: AudioManager fallback for speaker toggle when MIUI sends actions=0
                val speakerResult = CallManager.toggleSpeaker(applicationContext, pendingIntent)
                if (BuildConfig.DEBUG) {
                    Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_RESULT action=speaker success=${speakerResult.success} method=${speakerResult.method}")
                }
                
                // HARDENING#3: Schedule verification and potential revert
                // NOTE: This only reverts audio state, does NOT affect overlay expand/collapse
                scheduleAudioStateVerification(
                    expectedSpeaker = desiredSpeaker,
                    expectedMute = null,
                    rid = rid,
                    actionType = "speaker"
                )
                
                speakerResult.success
            }
            "mute" -> {
                // BUG#3 FIX: Get current state and compute desired toggle state
                val currentMute = CallManager.isMuted(applicationContext)
                val desiredMute = !currentMute
                
                // Log click with desired state for debugging
                if (BuildConfig.DEBUG) {
                    Log.d("HI_CALL", "RID=$rid EVT=CALL_TOGGLE_CLICK action=mute current=$currentMute desired=$desiredMute")
                }
                
                // HARDENING#3: Apply optimistic state immediately
                applyOptimisticAudioState(isSpeakerOn = null, isMuted = desiredMute, rid = rid)
                
                // BUG#1 FIX: AudioManager fallback for mute toggle when MIUI sends actions=0
                val muteResult = CallManager.toggleMute(applicationContext, pendingIntent)
                if (BuildConfig.DEBUG) {
                    Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_RESULT action=mute success=${muteResult.success} method=${muteResult.method}")
                }
                
                // HARDENING#3: Schedule verification and potential revert
                // NOTE: This only reverts audio state, does NOT affect overlay expand/collapse
                scheduleAudioStateVerification(
                    expectedSpeaker = null,
                    expectedMute = desiredMute,
                    rid = rid,
                    actionType = "mute"
                )
                
                muteResult.success
            }
            else -> {
                // Unknown action type - try PendingIntent directly
                if (pendingIntent == null) {
                    Log.w(TAG, "No PendingIntent for unknown call action: $actionType")
                    Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_FAIL action=$actionType reason=NO_INTENT pkg=$pkg")
                    false
                } else {
                    try {
                        pendingIntent.send()
                        Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_OK action=$actionType method=PENDING_INTENT pkg=$pkg")
                        true
                    } catch (e: Exception) {
                        Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_FAIL action=$actionType reason=${e.javaClass.simpleName} pkg=$pkg")
                        false
                    }
                }
            }
        }
        
        // BUG#1 FIX: Do NOT open call screen when mute/speaker fails
        // Opening call screen is bad UX - user clicked mute button, not "open call app"
        // Just log the failure - the button is already disabled via canMute/canSpeaker flags
        if (!result && (actionType == "speaker" || actionType == "mute")) {
            if (BuildConfig.DEBUG) {
                Log.d("HI_CALL", "RID=$rid EVT=CALL_ACTION_FAILED action=$actionType canRetry=false")
            }
            // BUG#1 FIX: REMOVED call screen fallback - this was causing bad UX
            // The capability flags (canMute=false) already prevent future clicks
        }
        
        return result
    }
    
    /**
     * HARDENING#3: Apply optimistic audio state immediately for responsive UI.
     * The UI will show this state until verification confirms or reverts it.
     */
    private fun applyOptimisticAudioState(isSpeakerOn: Boolean?, isMuted: Boolean?, rid: Int) {
        val now = System.currentTimeMillis()
        optimisticAudioState = OptimisticAudioState(
            isSpeakerOn = isSpeakerOn,
            isMuted = isMuted,
            appliedAtMs = now
        )
        
        if (BuildConfig.DEBUG) {
            Log.d("HI_CALL", "RID=$rid EVT=CALL_AUDIO_STATE_SYNC source=OPTIMISTIC speaker=$isSpeakerOn mute=$isMuted")
        }
    }
    
    /**
     * HARDENING#3: Schedule verification of audio state after action.
     * If real state doesn't match expected state within timeout, revert optimistic state.
     */
    private fun scheduleAudioStateVerification(
        expectedSpeaker: Boolean?,
        expectedMute: Boolean?,
        rid: Int,
        actionType: String
    ) {
        // Cancel any previous verification job
        audioStateRevertJob?.cancel()
        
        audioStateRevertJob = serviceScope.launch {
            delay(OPTIMISTIC_REVERT_MS)
            
            // Check real state from AudioManager
            val realSpeaker = CallManager.isSpeakerOn(applicationContext)
            val realMute = CallManager.isMuted(applicationContext)
            
            val speakerMatches = expectedSpeaker == null || realSpeaker == expectedSpeaker
            val muteMatches = expectedMute == null || realMute == expectedMute
            
            if (speakerMatches && muteMatches) {
                // State confirmed - clear optimistic state
                optimisticAudioState = null
                if (BuildConfig.DEBUG) {
                    Log.d("HI_CALL", "RID=$rid EVT=CALL_AUDIO_STATE_SYNC source=TELECOM action=$actionType confirmed=true realSpeaker=$realSpeaker realMute=$realMute")
                }
            } else {
                // State mismatch - revert optimistic state
                optimisticAudioState = null
                if (BuildConfig.DEBUG) {
                    Log.d("HI_CALL", "RID=$rid EVT=CALL_AUDIO_STATE_SYNC source=AUDIO_MANAGER action=$actionType confirmed=false expectedSpeaker=$expectedSpeaker expectedMute=$expectedMute realSpeaker=$realSpeaker realMute=$realMute REVERTED=true")
                }
                // Note: UI will pick up real state on next model update
            }
        }
    }
    
    /**
     * HARDENING#3: Get effective audio state (optimistic if recent, otherwise real).
     */
    private fun getEffectiveAudioState(): Pair<Boolean, Boolean> {
        val optimistic = optimisticAudioState
        val now = System.currentTimeMillis()
        
        // Use optimistic state if within timeout window
        if (optimistic != null && (now - optimistic.appliedAtMs) < OPTIMISTIC_REVERT_MS) {
            val realSpeaker = CallManager.isSpeakerOn(applicationContext)
            val realMute = CallManager.isMuted(applicationContext)
            return Pair(
                optimistic.isSpeakerOn ?: realSpeaker,
                optimistic.isMuted ?: realMute
            )
        }
        
        // Use real state from AudioManager
        return Pair(
            CallManager.isSpeakerOn(applicationContext),
            CallManager.isMuted(applicationContext)
        )
    }

    /**
     * BUG#1 & BUG#2 FIX: Handle call island tap using TelecomManager.showInCallScreen().
     * This is more reliable on MIUI/HyperOS than contentIntent, and ensures the in-call UI opens.
     * 
     * @return true if in-call UI was shown, false otherwise
     */
    private fun handleCallTap(rid: Int, pkg: String, fallbackIntent: PendingIntent?): Boolean {
        Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_START pkg=$pkg")
        
        // BUG#2 FIX: Check READ_PHONE_STATE permission before trying showInCallScreen
        val hasPhoneStatePermission = androidx.core.content.ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        // Method 1: TelecomManager.showInCallScreen() - only if we have permission
        if (hasPhoneStatePermission) {
            try {
                val telecomManager = getSystemService(android.content.Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                if (telecomManager != null) {
                    telecomManager.showInCallScreen(true)
                    Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_OK method=TELECOM_SHOW_INCALL pkg=$pkg")
                    Haptics.hapticOnIslandSuccess(applicationContext)
                    return true
                } else {
                    Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_WARN reason=NO_TELECOM_SERVICE pkg=$pkg")
                }
            } catch (e: SecurityException) {
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_FAIL reason=SECURITY_EXCEPTION msg=${e.message} pkg=$pkg")
            } catch (e: Exception) {
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_FAIL reason=${e.javaClass.simpleName} msg=${e.message} pkg=$pkg")
            }
        } else {
            if (BuildConfig.DEBUG) {
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_SKIP reason=NO_READ_PHONE_STATE method=TELECOM_SHOW_INCALL pkg=$pkg")
            }
        }
        
        // BUG#2 FIX: Method 2 - Try dialer-specific InCallUI activities
        val inCallActivities = when (pkg) {
            "com.google.android.dialer" -> listOf(
                "com.android.incallui.InCallActivity",
                "com.google.android.dialer.extensions.call.ui.InCallActivity"
            )
            "com.samsung.android.incallui" -> listOf(
                "com.samsung.android.incallui.InCallActivity"
            )
            "com.android.dialer" -> listOf(
                "com.android.incallui.InCallActivity"
            )
            else -> emptyList()
        }
        
        for (activity in inCallActivities) {
            try {
                val inCallIntent = Intent().apply {
                    setClassName(pkg, activity)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(inCallIntent)
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_OK method=INCALL_ACTIVITY activity=$activity pkg=$pkg")
                Haptics.hapticOnIslandSuccess(applicationContext)
                return true
            } catch (e: Exception) {
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_TRY reason=INCALL_${e.javaClass.simpleName} activity=$activity pkg=$pkg")
            }
        }
        
        // Method 3: Try ACTION_MAIN with DIAL category for phone apps
        try {
            val dialIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            val resolveInfo = packageManager.resolveActivity(dialIntent, 0)
            if (resolveInfo != null) {
                startActivity(dialIntent)
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_OK method=DIAL_MAIN pkg=$pkg")
                Haptics.hapticOnIslandSuccess(applicationContext)
                return true
            }
        } catch (e: Exception) {
            Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_TRY reason=DIAL_MAIN_${e.javaClass.simpleName} pkg=$pkg")
        }
        
        // Method 4: Try contentIntent (notification's original intent)
        if (fallbackIntent != null) {
            try {
                fallbackIntent.send()
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_OK method=CONTENT_INTENT pkg=$pkg")
                Haptics.hapticOnIslandSuccess(applicationContext)
                return true
            } catch (e: Exception) {
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_TRY reason=CONTENT_INTENT_${e.javaClass.simpleName} pkg=$pkg")
            }
        }
        
        // Method 5: Launch dialer package directly
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(launchIntent)
                Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_OK method=LAUNCH_INTENT pkg=$pkg")
                Haptics.hapticOnIslandSuccess(applicationContext)
                return true
            }
        } catch (e: Exception) {
            Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_FAIL reason=LAUNCH_INTENT_${e.javaClass.simpleName} pkg=$pkg")
        }
        
        Log.d("HI_INPUT", "RID=$rid EVT=CALL_TAP_FAIL reason=ALL_METHODS_FAILED pkg=$pkg")
        return false
    }

    private fun handleNotificationTap(contentIntent: PendingIntent?, rid: Int, pkg: String) {
        // Try contentIntent first
        if (contentIntent != null) {
            try {
                // Send with context to ensure Activity can be launched
                contentIntent.send(
                    this,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null
                )
                Log.d(TAG, "Notification tap intent sent successfully")
                Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_OK method=CONTENT_INTENT pkg=$pkg")
                return
            } catch (e: PendingIntent.CanceledException) {
                Log.e(TAG, "Notification contentIntent was cancelled", e)
                Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_FAIL reason=CANCELED pkg=$pkg")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send notification tap intent", e)
                Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_FAIL reason=${e.javaClass.simpleName} pkg=$pkg")
            }
        }

        // Fallback: Open app using launch intent
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "App launched via launch intent")
                Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_OK method=LAUNCH_INTENT pkg=$pkg")
            } else {
                Log.w(TAG, "No launch intent found for package: $pkg")
                Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_FAIL reason=NO_LAUNCH_INTENT pkg=$pkg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app", e)
            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_FAIL reason=${e.javaClass.simpleName} pkg=$pkg")
        }
    }

    private fun dismissOverlay(notificationKey: String?, reason: String) {      
        if (notificationKey == null) {
            dismissAllOverlays(reason)
            return
        }

        // Only dismiss if the key matches current overlay
        val matchesCall = currentCallModel?.notificationKey == notificationKey
        val matchesNotification = currentNotificationModel?.notificationKey == notificationKey
        val matchesMedia = currentMediaModel?.notificationKey == notificationKey
        val matchesTimer = currentTimerModel?.notificationKey == notificationKey
        val matchesNavigation = currentNavigationModel?.notificationKey == notificationKey

        if (matchesNavigation) {
            currentNavigationModel = null
            if (currentCallModel == null && currentNotificationModel == null) {
                dismissAllOverlays(reason)
            }
            return
        }

        if (matchesCall && currentNotificationModel != null) {
            Log.d(
                "HyperIsleIsland",
                "RID=${notificationKey.hashCode()} EVT=CALL_CLEARED reason=$reason"
            )
            currentCallModel = null
            deferCallOverlay = false
            return
        }

        if (matchesMedia) {
            currentMediaModel = null
            if (currentCallModel == null && currentNotificationModel == null) {
                if (currentTimerModel != null) {
                    showActivityOverlayFromState()
                } else {
                    dismissAllOverlays(reason)
                }
            }
            return
        }

        if (matchesTimer) {
            currentTimerModel = null
            if (currentCallModel == null && currentNotificationModel == null) {
                if (currentMediaModel != null) {
                    showActivityOverlayFromState()
                } else {
                    dismissAllOverlays(reason)
                }
            }
            return
        }

        if (matchesNotification) {
            val restoreCall = currentCallModel?.state == CallOverlayState.ONGOING
            dismissNotificationOverlay(reason, restoreCall)
            return
        }

        if (matchesCall) {
            // Track removed call notification to prevent late CALL_STATE events from re-showing
            removedCallNotificationKeys[notificationKey] = System.currentTimeMillis()
            // BUG#2 FIX: Set call end timestamp for cooldown
            lastCallEndTs = System.currentTimeMillis()
            Log.d(
                "HyperIsleIsland",
                "RID=${notificationKey.hashCode()} EVT=CALL_NOTIF_REMOVED_TRACKED ttl=${REMOVED_CALL_TTL_MS}ms"
            )
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HI_CALL",
                    "RID=${notificationKey.hashCode()} EVT=CALL_END_TS_SET ts=$lastCallEndTs cooldown=${CALL_END_COOLDOWN_MS}ms"
                )
            }
            // Clear dismissed tracking when call ends
            userDismissedCallKeys.remove(notificationKey)
            currentCallModel = null
            isCallOverlayActive = false
            if (currentMediaModel != null || currentTimerModel != null) {
                showActivityOverlayFromState()
            } else {
                dismissAllOverlays(reason)
            }
        }
    }

    private fun dismissNotificationOverlay(reason: String, restoreCall: Boolean) {
        val rid = currentNotificationModel?.notificationKey?.hashCode() ?: 0
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDE_CALLED reason=$reason")
        if (BuildConfig.DEBUG) {
            val pkg = currentNotificationModel?.packageName
            Log.d("HyperIsleIsland", "RID=OVL_DISMISS STAGE=OVERLAY ACTION=OVERLAY_DISMISS type=NOTIFICATION pkg=$pkg")
            IslandRuntimeDump.recordOverlay(
                null,
                "OVERLAY_DISMISS",
                reason = "dismissNotification",
                pkg = pkg,
                overlayType = "NOTIFICATION"
            )
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = pkg,
                type = "NOTIFICATION"
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "OVERLAY_DISMISS",
                route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                reason = reason
            )
        }
        autoCollapseJob?.cancel()
        currentNotificationModel = null
        isNotificationCollapsed = false
        deferCallOverlay = false
        overlayController.removeOverlay()
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDDEN_OK reason=$reason")
        if (restoreCall && currentCallModel != null) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_RESTORE reason=$reason type=CALL"
            )
            showCallOverlay(currentCallModel ?: return)
        } else if (currentMediaModel != null || currentTimerModel != null) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_RESTORE reason=$reason type=ACTIVITY"
            )
            showActivityOverlayFromState()
        } else {
            scheduleStopIfIdle(reason)
        }
    }

    private fun dismissAllOverlays(reason: String) {
        Log.d(TAG, "Dismissing all overlays")
        val rid = (currentCallModel?.notificationKey ?: currentNotificationModel?.notificationKey)?.hashCode() ?: 0
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDE_CALLED reason=$reason")
        // INSTRUMENTATION: Overlay dismiss
        if (BuildConfig.DEBUG) {
            val overlayType = when {
                currentCallModel != null -> "CALL"
                currentNotificationModel != null -> "NOTIFICATION"
                currentMediaModel != null || currentTimerModel != null -> "ACTIVITY"
                else -> "NONE"
            }
            val pkg = currentCallModel?.packageName
                ?: currentNotificationModel?.packageName
                ?: currentMediaModel?.packageName
                ?: currentTimerModel?.packageName
            Log.d("HyperIsleIsland", "RID=OVL_DISMISS STAGE=OVERLAY ACTION=OVERLAY_DISMISS type=$overlayType pkg=$pkg")
            IslandRuntimeDump.recordOverlay(null, "OVERLAY_DISMISS", reason = "dismissAllOverlays", pkg = pkg, overlayType = overlayType)
            // UI Snapshot: OVERLAY_DISMISS
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = pkg,
                type = overlayType
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "OVERLAY_DISMISS",
                route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                reason = reason
            )
        }
        autoCollapseJob?.cancel()
        // BUG#2 FIX: Clean up service-level call UI state maps and set call end timestamp
        currentCallModel?.notificationKey?.let { key ->
            callExpandedState.remove(key)
            callMediaExpandedState.keys.filter { it.startsWith("${key}_") }
                .forEach { callMediaExpandedState.remove(it) }
            // Set call end timestamp for cooldown
            lastCallEndTs = System.currentTimeMillis()
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HI_CALL",
                    "RID=${key.hashCode()} EVT=CALL_END_TS_SET ts=$lastCallEndTs cooldown=${CALL_END_COOLDOWN_MS}ms reason=dismissAllOverlays"
                )
            }
        }
        currentCallModel = null
        currentNotificationModel = null
        currentMediaModel = null
        currentTimerModel = null
        currentNavigationModel = null
        isNotificationCollapsed = false
        deferCallOverlay = false
        isCallOverlayActive = false
        overlayController.removeOverlay()
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDDEN_OK reason=$reason")
        Log.d("HyperIsleIsland", "RID=$rid EVT=STATE_RESET_DONE reason=$reason")
        scheduleStopIfIdle(reason)
    }

    private fun shouldSuppressOverlay(
        contextSignals: AccessibilityContextSignals,
        overlayPackage: String,
        isCall: Boolean
    ): Boolean {
        val foregroundPackage = contextSignals.foregroundPackage ?: return false
        val isForegroundTarget = if (isCall) {
            foregroundPackage == overlayPackage || CALL_FOREGROUND_PACKAGES.contains(foregroundPackage)
        } else {
            foregroundPackage == overlayPackage
        }
        return isForegroundTarget
    }

    private fun dismissFromUser(reason: String) {
        val restoreCall = currentNotificationModel != null &&
            currentCallModel?.state == CallOverlayState.ONGOING
        
        // Haptic feedback on user dismiss
        Haptics.hapticOnIslandSuccess(applicationContext)
        
        // Track user-dismissed calls to prevent re-showing (with TTL timestamp)
        if (currentCallModel != null && currentNotificationModel == null) {
            currentCallModel?.notificationKey?.let { key ->
                userDismissedCallKeys[key] = System.currentTimeMillis()
                Log.d("HyperIsleIsland", "RID=${key.hashCode()} EVT=CALL_USER_DISMISSED reason=$reason ttl=${CALL_DEDUPE_TTL_MS}ms")
            }
        }
        
        if (currentNotificationModel != null) {
            dismissNotificationOverlay(reason, restoreCall)
        } else {
            dismissAllOverlays(reason)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Composable
    private fun SwipeDismissContainer(
        rid: Int,
        stateLabel: String,
        modifier: Modifier = Modifier,
        onDismiss: () -> Unit,
        onTap: (() -> Unit)? = null,
        onLongPress: (() -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var offsetX by remember { mutableStateOf(0f) }
        var containerWidth by remember { mutableStateOf(0) }
        var isSwiping by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        val dismissThresholdPx = with(density) { 48.dp.toPx() }
        val state = stateLabel.lowercase()

        Box(
            modifier = modifier
                .onSizeChanged { containerWidth = it.width }
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInteropFilter { event ->
                    val actionName = when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> "DOWN"
                        MotionEvent.ACTION_MOVE -> "MOVE"
                        MotionEvent.ACTION_UP -> "UP"
                        MotionEvent.ACTION_CANCEL -> "CANCEL"
                        else -> event.actionMasked.toString()
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=$rid EVT=RAW_TOUCH action=$actionName x=${event.x} y=${event.y} consumed=$isSwiping"
                        )
                    }
                    false
                }
                .pointerInput(rid, onTap, onLongPress) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val pointerId = down.id
                            var longPressTriggered = false
                            var dragTotal = 0f
                            var hasStarted = false
                            var lastPosition = down.position
                            var totalDx = 0f
                            var totalDy = 0f
                            var endedByUp = false
                            var endedByCancel = false
                            val touchSlop = viewConfiguration.touchSlop
                            val longPressJob = if (onLongPress != null) {
                                scope.launch {
                                    // Use longer timeout (600ms) to prevent accidental long press on quick taps
                                    delay(600L)
                                    if (!hasStarted && totalDx <= touchSlop && totalDy <= touchSlop) {
                                        longPressTriggered = true
                                        onLongPress.invoke()
                                    }
                                }
                            } else {
                                null
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                val deltaX = change.position.x - lastPosition.x
                                val deltaY = change.position.y - lastPosition.y
                                totalDx = abs(change.position.x - down.position.x)
                                totalDy = abs(change.position.y - down.position.y)

                                if (!hasStarted) {
                                    if (totalDx > touchSlop && totalDx > totalDy) {
                                        hasStarted = true
                                        isSwiping = true
                                        longPressJob?.cancel()
                                        change.consume()
                                        Log.d(
                                            "HyperIsleIsland",
                                            "RID=$rid EVT=SWIPE_START x=${down.position.x} y=${down.position.y} state=$state"
                                        )
                                    } else if (totalDx <= touchSlop && totalDy <= touchSlop) {
                                        // Consume small movements to prevent them from being handled elsewhere
                                        change.consume()
                                    }
                                }

                                if (hasStarted && deltaX != 0f) {
                                    dragTotal += deltaX
                                    offsetX += deltaX
                                    change.consume()
                                    Log.d("HyperIsleIsland", "RID=$rid EVT=SWIPE_MOVE dx=$dragTotal")
                                }

                                val isUp = change.previousPressed && !change.pressed
                                val isCancel = !change.pressed && !change.previousPressed
                                if (isUp || isCancel) {
                                    endedByUp = isUp
                                    endedByCancel = isCancel
                                    break
                                }
                                lastPosition = change.position
                            }

                            longPressJob?.cancel()
                            if (!hasStarted) {
                                isSwiping = false
                                val isTap = endedByUp && !endedByCancel && !longPressTriggered &&
                                    totalDx <= touchSlop && totalDy <= touchSlop
                                if (BuildConfig.DEBUG) {
                                    val decision = when {
                                        isTap && onTap != null -> "TAP_FIRE"
                                        isTap && onTap == null -> "TAP_NO_HANDLER"
                                        longPressTriggered -> "LONG_PRESS"
                                        endedByCancel -> "CANCEL"
                                        else -> "IGNORED"
                                    }
                                    Log.d(
                                        "HyperIsleIsland",
                                        "RID=$rid EVT=GESTURE_DECISION decision=$decision dx=$totalDx dy=$totalDy slop=$touchSlop state=$state"
                                    )
                                }
                                if (isTap) {
                                    onTap?.invoke()
                                }
                                continue
                            }

                            val shouldDismiss = abs(offsetX) >= dismissThresholdPx
                            if (shouldDismiss) {
                                val targetDistance = if (containerWidth > 0) {
                                    containerWidth.toFloat()
                                } else {
                                    dismissThresholdPx * 4
                                }
                                val target = if (offsetX < 0f) -targetDistance else targetDistance
                                scope.launch {
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = target,
                                        animationSpec = tween(180)
                                    ) { value, _ ->
                                        offsetX = value
                                    }
                                    onDismiss()
                                    offsetX = 0f
                                }
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=SWIPE_END result=DISMISSED dx=$dragTotal threshold=${dismissThresholdPx.roundToInt()}"
                                )
                            } else {
                                scope.launch {
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = 0f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                                    ) { value, _ ->
                                        offsetX = value
                                    }
                                }
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=SWIPE_END result=CANCELLED dx=$dragTotal threshold=${dismissThresholdPx.roundToInt()}"
                                )
                            }
                            isSwiping = false
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            content()
        }
    }
}
