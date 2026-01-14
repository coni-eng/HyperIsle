package com.coni.hyperisle.debug

import android.content.Context
import android.content.Intent
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.IosNotificationOverlayModel
import com.coni.hyperisle.overlay.NotificationMediaType
import com.coni.hyperisle.overlay.OverlayEventBus
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.util.OverlayPermissionHelper
import java.util.UUID



/**
 * Origin of the notification event - used for telemetry and routing decisions.
 */
enum class NotifOrigin {
    LISTENER,    // From NotificationListenerService (real notification)
    DEBUG_LAB    // From Notification Lab (synthetic test event)
}

/**
 * Route hint for debug lab - allows forcing specific routing behavior.
 */
enum class RouteHint {
    AUTO,                       // Normal routing decision
    FORCE_APP_OVERLAY,          // Force APP_OVERLAY route
    FORCE_SUPPRESS_MIUI_BRIDGE, // Suppress MIUI bridge, use APP_OVERLAY
    FORCE_NONE                  // Don't show, only log
}

/**
 * Core notification event model for both real and debug notifications.
 * This DTO carries all the information needed to route and render a notification island.
 */
data class HiNotifEvent(
    val sourcePackage: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val bigText: String? = null,
    val whenMs: Long = System.currentTimeMillis(),
    val conversationId: String? = null,
    val messageId: String = UUID.randomUUID().toString(),
    val canReply: Boolean = false,
    val hasActions: Boolean = false,
    val importance: Int = 4, // IMPORTANCE_HIGH
    val category: String? = "msg",
    val isGroup: Boolean = false,
    val groupKey: String? = null,
    val routeHint: RouteHint = RouteHint.AUTO,
    val origin: NotifOrigin = NotifOrigin.LISTENER,
    // Debug lab specific
    val overrideSelectedAppsFilter: Boolean = false // Bypass "selected apps" check
) {
    /**
     * Generate a unique key for this event (used for overlay tracking).
     */
    val notificationKey: String
        get() = "$sourcePackage:$conversationId:$messageId"
    
    /**
     * Generate a group key for island replacement policy.
     */
    val islandGroupKey: String
        get() = "$sourcePackage:STANDARD"
}

/**
 * Result of the notification routing decision.
 */
data class NotifRouteResult(
    val chosen: String, // APP_OVERLAY, MIUI_ISLAND_BRIDGE, NONE
    val suppressedReason: String? = null,
    val shouldRender: Boolean = true
)

/**
 * Core notification processing pipeline.
 * Both NotificationReaderService and Debug Lab use this for consistent behavior.
 */
object NotificationCore {
    private const val TAG = "HI_NOTIF"
    
    // Known messaging app packages
    private val KNOWN_MESSAGING_APPS = mapOf(
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "org.telegram.plus" to "Telegram",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business"
    )
    
    /**
     * Ingest a notification event and route it to the appropriate display.
     * 
     * @param context Application context
     * @param event The notification event to process
     * @return Route result indicating what action was taken
     */
    fun ingest(context: Context, event: HiNotifEvent): NotifRouteResult {
        val rid = DebugLog.rid()
        
        // EVT=NOTIF_INGEST - Log event intake
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=NOTIF_INGEST origin=${event.origin.name} pkg=${event.sourcePackage} " +
                "msgId=${event.messageId.take(8)} convId=${event.conversationId ?: "null"} " +
                "title=${event.title.take(20)} canReply=${event.canReply}")
        }
        
        // Step 1: Selection filter check
        val filterResult = checkSelectionFilter(context, event)
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=NOTIF_FILTER selectedAppsHit=${filterResult.first} override=${event.overrideSelectedAppsFilter}")
        }
        
        if (!filterResult.first && !event.overrideSelectedAppsFilter) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=NOTIF_ROUTE_FINAL chosen=NONE suppressedReason=NOT_IN_SELECTED_APPS")
            }
            return NotifRouteResult(
                chosen = "NONE",
                suppressedReason = "NOT_IN_SELECTED_APPS",
                shouldRender = false
            )
        }
        
        // Step 2: Determine route based on hint and capabilities
        val routeResult = determineRoute(context, event)
        
        // EVT=NOTIF_ROUTE_FINAL - Log final routing decision
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=NOTIF_ROUTE_FINAL chosen=${routeResult.chosen} " +
                "suppressedReason=${routeResult.suppressedReason ?: "NONE"} " +
                "pkg=${event.sourcePackage}")
        }
        
        // Step 3: Render if needed
        if (routeResult.shouldRender && routeResult.chosen == "APP_OVERLAY") {
            val renderSuccess = emitOverlayEvent(context, event)
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=ISLAND_RENDER type=NOTIF state=EXPANDED pkg=${event.sourcePackage} " +
                    "success=$renderSuccess")
            }
        }
        
        return routeResult
    }
    
    /**
     * Check if the app is in the user's selected apps list.
     * For debug lab events with override, this always returns true.
     */
    private fun checkSelectionFilter(context: Context, event: HiNotifEvent): Pair<Boolean, String> {
        // For debug lab, we can check if the app is actually selected
        // But with override flag, we bypass this
        if (event.overrideSelectedAppsFilter) {
            return true to "OVERRIDE_ENABLED"
        }
        
        // Check if it's a known messaging app - for debug purposes assume selected
        if (KNOWN_MESSAGING_APPS.containsKey(event.sourcePackage)) {
            return true to "KNOWN_MESSAGING_APP"
        }
        
        // For now, assume selected for debug events
        return (event.origin == NotifOrigin.DEBUG_LAB) to "DEBUG_LAB_PASS"
    }
    
    /**
     * Determine the routing decision based on event properties and hints.
     */
    private fun determineRoute(context: Context, event: HiNotifEvent): NotifRouteResult {
        // Check overlay permission
        val hasOverlayPermission = OverlayPermissionHelper.hasOverlayPermission(context)
        
        return when (event.routeHint) {
            RouteHint.FORCE_NONE -> NotifRouteResult(
                chosen = "NONE",
                suppressedReason = "FORCE_NONE_HINT",
                shouldRender = false
            )
            
            RouteHint.FORCE_APP_OVERLAY -> {
                if (!hasOverlayPermission) {
                    NotifRouteResult(
                        chosen = "NONE",
                        suppressedReason = "NO_OVERLAY_PERMISSION",
                        shouldRender = false
                    )
                } else {
                    NotifRouteResult(
                        chosen = "APP_OVERLAY",
                        suppressedReason = "MIUI_ISLAND_BRIDGE", // We're suppressing MIUI
                        shouldRender = true
                    )
                }
            }
            
            RouteHint.FORCE_SUPPRESS_MIUI_BRIDGE -> {
                if (!hasOverlayPermission) {
                    NotifRouteResult(
                        chosen = "NONE",
                        suppressedReason = "NO_OVERLAY_PERMISSION",
                        shouldRender = false
                    )
                } else {
                    NotifRouteResult(
                        chosen = "APP_OVERLAY",
                        suppressedReason = "MIUI_ISLAND_BRIDGE",
                        shouldRender = true
                    )
                }
            }
            
            RouteHint.AUTO -> {
                // Default: prefer APP_OVERLAY for notifications
                if (!hasOverlayPermission) {
                    NotifRouteResult(
                        chosen = "NONE",
                        suppressedReason = "NO_OVERLAY_PERMISSION",
                        shouldRender = false
                    )
                } else {
                    NotifRouteResult(
                        chosen = "APP_OVERLAY",
                        suppressedReason = "MIUI_ISLAND_BRIDGE", // Standard notifications suppress MIUI
                        shouldRender = true
                    )
                }
            }
        }
    }
    
    /**
     * Emit an overlay event to show the notification island.
     */
    private fun emitOverlayEvent(context: Context, event: HiNotifEvent): Boolean {
        // Ensure overlay service is started
        if (!OverlayPermissionHelper.startOverlayServiceIfPermitted(context)) {
            if (BuildConfig.DEBUG) {
                HiLog.w(HiLog.TAG_ISLAND, "EVT=OVERLAY_SERVICE_FAIL reason=SERVICE_NOT_STARTED")
            }
            return false
        }
        
        // Build the overlay model
        val model = IosNotificationOverlayModel(
            sender = event.title,
            timeLabel = formatTimeLabel(event.whenMs),
            message = event.bigText ?: event.text,
            avatarBitmap = null, // Debug lab doesn't provide avatar
            contentIntent = null, // Will be handled via openApp fallback
            packageName = event.sourcePackage,
            notificationKey = event.notificationKey,
            collapseAfterMs = 5000L, // Default collapse time
            replyAction = null, // Debug lab doesn't provide reply action
            accentColor = null,
            mediaType = NotificationMediaType.NONE,
            mediaBitmap = null
        )
        
        return OverlayEventBus.emitNotification(model)
    }
    
    /**
     * Format timestamp for display.
     */
    private fun formatTimeLabel(whenMs: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - whenMs
        return when {
            diff < 60_000 -> "now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            else -> "${diff / 3600_000}h ago"
        }
    }
    
    /**
     * Dismiss an active island (for debug lab dismiss button).
     */
    fun dismissIsland(notificationKey: String? = null) {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=DEBUG_LAB_DISMISS key=${notificationKey ?: "ALL"}")
        }
        if (notificationKey != null) {
            OverlayEventBus.emitDismiss(notificationKey)
        } else {
            OverlayEventBus.emitDismissAll()
        }
    }
    
    /**
     * Try to open the source app.
     */
    fun openSourceApp(context: Context, packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=ISLAND_GESTURE action=OPEN target=$packageName result=OK")
                }
                true
            } else {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=ISLAND_GESTURE action=OPEN target=$packageName result=NO_LAUNCH_INTENT")
                }
                false
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=ISLAND_GESTURE action=OPEN target=$packageName result=ERROR error=${e.message}")
            }
            false
        }
    }
    
    /**
     * Get default app label for known packages.
     */
    fun getAppLabel(packageName: String): String {
        return KNOWN_MESSAGING_APPS[packageName] ?: packageName.substringAfterLast(".")
    }
}
