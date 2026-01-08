package com.coni.hyperisle.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.debug.IslandRuntimeDump
import com.coni.hyperisle.debug.IslandUiSnapshotLogger
import com.coni.hyperisle.ui.components.IncomingCallPill
import com.coni.hyperisle.ui.components.NotificationPill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that manages iOS-style pill overlays.
 * Listens to OverlayEventBus and displays appropriate pill overlays.
 */
class IslandOverlayService : Service() {

    companion object {
        private const val TAG = "IslandOverlayService"
        private const val CHANNEL_ID = "ios_pill_overlay_channel"
        private const val NOTIFICATION_ID = 9999
        private const val NOTIFICATION_AUTO_DISMISS_MS = 4000L

        const val ACTION_START = "com.coni.hyperisle.overlay.START"
        const val ACTION_STOP = "com.coni.hyperisle.overlay.STOP"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var overlayController: OverlayWindowController

    // Current overlay state
    private var currentCallModel: IosCallOverlayModel? by mutableStateOf(null)
    private var currentNotificationModel: IosNotificationOverlayModel? by mutableStateOf(null)
    private var autoDismissJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        // INSTRUMENTATION: Overlay service lifecycle
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=OVL_CREATE STAGE=LIFECYCLE ACTION=OVERLAY_SVC_CREATED")
            IslandRuntimeDump.recordOverlay(null, "SERVICE_CREATED", reason = "onCreate")
        }

        overlayController = OverlayWindowController(applicationContext)
        createNotificationChannel()
        startEventCollection()
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
                startForeground(NOTIFICATION_ID, createForegroundNotification())
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        // INSTRUMENTATION: Overlay service destroy
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=OVL_DEST STAGE=LIFECYCLE ACTION=OVERLAY_SVC_DESTROYED")
            IslandRuntimeDump.recordOverlay(null, "SERVICE_DESTROYED", reason = "onDestroy")
        }
        autoDismissJob?.cancel()
        overlayController.removeOverlay()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_ios_overlay),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "iOS-style pill overlay service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_service_active))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startEventCollection() {
        serviceScope.launch {
            OverlayEventBus.events.collectLatest { event ->
                handleOverlayEvent(event)
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
            is OverlayEvent.CallEvent -> showCallOverlay(event.model)
            is OverlayEvent.NotificationEvent -> showNotificationOverlay(event.model)
            is OverlayEvent.DismissEvent -> dismissOverlay(event.notificationKey)
            is OverlayEvent.DismissAllEvent -> dismissAllOverlays()
        }
    }

    private fun showCallOverlay(model: IosCallOverlayModel) {
        Log.d(TAG, "Showing call overlay for: ${model.callerName}")
        // INSTRUMENTATION: Overlay show call
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=${model.notificationKey?.hashCode() ?: 0} STAGE=OVERLAY ACTION=OVERLAY_SHOW type=CALL pkg=${model.packageName}")
            IslandRuntimeDump.recordOverlay(null, "OVERLAY_SHOW", reason = "CALL", pkg = model.packageName, overlayType = "CALL")
            // UI Snapshot: OVERLAY_SHOW for call
            val slots = IslandUiSnapshotLogger.slotsCall(
                hasAvatar = model.avatarBitmap != null,
                hasCallerName = model.callerName.isNotEmpty(),
                hasTimer = false,
                actionLabels = listOf("decline", "accept"),
                isIncoming = true,
                isOngoing = false
            )
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = model.packageName,
                type = "CALL",
                keyHash = model.notificationKey?.hashCode()?.toString()
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "OVERLAY_SHOW",
                route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                slots = slots
            )
        }

        // Cancel any auto-dismiss job (calls don't auto-dismiss)
        autoDismissJob?.cancel()
        currentNotificationModel = null
        currentCallModel = model

        overlayController.showOverlay(OverlayEvent.CallEvent(model)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                IncomingCallPill(
                    title = model.title,
                    name = model.callerName,
                    avatarBitmap = model.avatarBitmap,
                    onDecline = {
                        handleCallAction(model.declineIntent, "decline")
                        dismissAllOverlays()
                    },
                    onAccept = {
                        handleCallAction(model.acceptIntent, "accept")
                        dismissAllOverlays()
                    }
                )
            }
        }
    }

    private fun showNotificationOverlay(model: IosNotificationOverlayModel) {
        Log.d(TAG, "Showing notification overlay from: ${model.sender}")
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

        // Cancel previous auto-dismiss job
        autoDismissJob?.cancel()
        currentCallModel = null
        currentNotificationModel = model

        overlayController.showOverlay(OverlayEvent.NotificationEvent(model)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                NotificationPill(
                    sender = model.sender,
                    timeLabel = model.timeLabel,
                    message = model.message,
                    avatarBitmap = model.avatarBitmap,
                    onClick = {
                        handleNotificationTap(model.contentIntent)
                        dismissAllOverlays()
                    }
                )

                // Auto-dismiss after 4 seconds
                LaunchedEffect(model.notificationKey) {
                    delay(NOTIFICATION_AUTO_DISMISS_MS)
                    if (currentNotificationModel?.notificationKey == model.notificationKey) {
                        dismissAllOverlays()
                    }
                }
            }
        }

        // Backup auto-dismiss using coroutine (in case LaunchedEffect doesn't trigger)
        autoDismissJob = serviceScope.launch {
            delay(NOTIFICATION_AUTO_DISMISS_MS)
            if (currentNotificationModel?.notificationKey == model.notificationKey) {
                dismissAllOverlays()
            }
        }
    }

    private fun handleCallAction(pendingIntent: PendingIntent?, actionType: String) {
        if (pendingIntent == null) {
            Log.w(TAG, "No PendingIntent for call action: $actionType")
            return
        }

        try {
            pendingIntent.send()
            Log.d(TAG, "Call action sent successfully: $actionType")
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "PendingIntent was cancelled for action: $actionType", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send call action: $actionType", e)
        }
    }

    private fun handleNotificationTap(contentIntent: PendingIntent?) {
        if (contentIntent == null) {
            Log.w(TAG, "No contentIntent for notification tap")
            return
        }

        try {
            contentIntent.send()
            Log.d(TAG, "Notification tap intent sent successfully")
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Notification contentIntent was cancelled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification tap intent", e)
        }
    }

    private fun dismissOverlay(notificationKey: String?) {
        if (notificationKey == null) {
            dismissAllOverlays()
            return
        }

        // Only dismiss if the key matches current overlay
        val shouldDismiss = when {
            currentCallModel?.notificationKey == notificationKey -> true
            currentNotificationModel?.notificationKey == notificationKey -> true
            else -> false
        }

        if (shouldDismiss) {
            dismissAllOverlays()
        }
    }

    private fun dismissAllOverlays() {
        Log.d(TAG, "Dismissing all overlays")
        // INSTRUMENTATION: Overlay dismiss
        if (BuildConfig.DEBUG) {
            val overlayType = when {
                currentCallModel != null -> "CALL"
                currentNotificationModel != null -> "NOTIFICATION"
                else -> "NONE"
            }
            val pkg = currentCallModel?.packageName ?: currentNotificationModel?.packageName
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
                reason = "AUTO_TIMEOUT"
            )
        }
        autoDismissJob?.cancel()
        currentCallModel = null
        currentNotificationModel = null
        overlayController.removeOverlay()
    }
}
