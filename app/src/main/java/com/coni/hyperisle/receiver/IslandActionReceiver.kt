package com.coni.hyperisle.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.MainActivity
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.util.ActionDiagnostics
import com.coni.hyperisle.util.DebugTimeline
import com.coni.hyperisle.util.FocusActionHelper
import com.coni.hyperisle.util.Haptics
import com.coni.hyperisle.util.IslandCooldownManager
import com.coni.hyperisle.util.PriorityEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling island action button clicks.
 * Listens for:
 * - miui.focus.action_options -> Opens Quick Actions UI
 * - miui.focus.action_dismiss -> Cancels island + records cooldown + haptic
 * - miui.focus.action_tapopen -> Dismisses island + opens source app
 */
class IslandActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IslandActionReceiver"
        // Delegate to FocusActionHelper for centralized action string constants
        const val ACTION_OPTIONS = FocusActionHelper.ACTION_OPTIONS
        const val ACTION_DISMISS = FocusActionHelper.ACTION_DISMISS
        const val ACTION_TAP_OPEN = FocusActionHelper.ACTION_TAP_OPEN
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // Security: Validate intent before processing
        if (intent == null) return
        val action = intent.action ?: return
        
        // Security: Whitelist - only accept known focus actions
        if (!FocusActionHelper.isOptionsAction(action) && 
            !FocusActionHelper.isDismissAction(action) &&
            !FocusActionHelper.isTapOpenAction(action)) {
            // Unknown action - silently ignore (no logging to avoid PII leaks)
            return
        }
        
        Log.d(TAG, "Received focus action")

        // Parse notification ID using centralized helper
        val notificationId = FocusActionHelper.parseNotificationId(action)

        // Safety: if focus action detected but ID parsing failed, log and early return
        if (notificationId == null) {
            Log.w(TAG, "Focus action detected but notification ID parsing failed")
            if (ActionDiagnostics.isEnabled()) {
                ActionDiagnostics.record("focus_action_parse_failed")
            }
            // Fall through to allow fallback behavior using last active notification
        }

        when {
            FocusActionHelper.isOptionsAction(action) -> handleOptions(context, notificationId)
            FocusActionHelper.isDismissAction(action) -> handleDismiss(context, notificationId)
            FocusActionHelper.isTapOpenAction(action) -> handleTapOpen(context, notificationId)
        }
    }

    private fun handleOptions(context: Context, notificationId: Int?) {
        Log.d(TAG, "Opening Quick Actions screen for notificationId: $notificationId")
        
        // Show debug route toast if enabled
        showDebugRouteToast(context, "Broadcast")
        
        // Get package from meta map, fallback to last active
        val targetPackage = if (notificationId != null) {
            IslandCooldownManager.getIslandMeta(notificationId)?.first
        } else null ?: IslandCooldownManager.getLastActivePackage()
        
        val targetId = notificationId ?: IslandCooldownManager.getLastActiveNotificationId()
        
        // Timeline: optionsPressed event
        DebugTimeline.log(
            "optionsPressed",
            targetPackage,
            targetId,
            mapOf("action" to "openQuickActions")
        )
        
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openQuickActions", true)
            targetPackage?.let { putExtra("quickActionsPackage", it) }
        }
        
        context.startActivity(launchIntent)
    }

    private fun handleDismiss(context: Context, notificationId: Int?) {
        Log.d(TAG, "Dismissing island for notificationId: $notificationId")
        
        // Show debug route toast if enabled
        showDebugRouteToast(context, "Broadcast")
        
        // Get meta from map for this specific notification ID
        val meta = if (notificationId != null) {
            IslandCooldownManager.getIslandMeta(notificationId)
        } else null
        
        val targetId = notificationId ?: IslandCooldownManager.getLastActiveNotificationId()
        val targetPackage = meta?.first ?: IslandCooldownManager.getLastActivePackage()
        val targetType = meta?.second ?: IslandCooldownManager.getLastActiveType()
        
        // Debug log (PII-safe): record close button press event
        if (ActionDiagnostics.isEnabled()) {
            val keyHash = targetId?.hashCode() ?: "N/A"
            ActionDiagnostics.record("event=closePressed pkg=${targetPackage ?: "unknown"} keyHash=$keyHash")
        }
        
        // Timeline: closePressed event
        DebugTimeline.log(
            "closePressed",
            targetPackage,
            targetId,
            mapOf("type" to targetType)
        )
        
        // Cancel the specific notification
        if (targetId != null) {
            try {
                NotificationManagerCompat.from(context).cancel(targetId)
                Log.d(TAG, "Cancelled notification id: $targetId")
                
                // Timeline: autoDismissTriggered event for user dismiss
                DebugTimeline.log(
                    "autoDismissTriggered",
                    targetPackage,
                    targetId,
                    mapOf("reason" to "USER_DISMISS")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel notification: ${e.message}")
            }
        }
        
        // Record cooldown for the correct pkg:type
        if (targetPackage != null && targetType != null) {
            IslandCooldownManager.recordDismissal(targetPackage, targetType)
            Log.d(TAG, "Recorded cooldown for $targetPackage:$targetType")
            
            // Increment dismiss counter for PriorityEngine (auto-throttle)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val preferences = AppPreferences(context)
                    val aggressiveness = preferences.smartPriorityAggressivenessFlow.first()
                    PriorityEngine.recordDismiss(preferences, targetPackage, targetType, aggressiveness)
                    Log.d(TAG, "Recorded PriorityEngine dismiss for $targetPackage:$targetType")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record PriorityEngine dismiss: ${e.message}")
                }
            }
        }
        
        // Clear meta for this notification ID
        if (notificationId != null) {
            IslandCooldownManager.clearIslandMeta(notificationId)
        }
        
        // Trigger success haptic
        Haptics.hapticOnIslandSuccess(context)
    }

    /**
     * Handles tap-open action: dismisses island UI and opens the source app.
     * This is triggered when user taps the island body to open the app.
     * Does NOT record cooldown (user explicitly wanted to see the notification).
     */
    private fun handleTapOpen(context: Context, notificationId: Int?) {
        // Get meta from map for this specific notification ID
        val meta = if (notificationId != null) {
            IslandCooldownManager.getIslandMeta(notificationId)
        } else null
        
        val targetId = notificationId ?: IslandCooldownManager.getLastActiveNotificationId()
        val targetPackage = meta?.first ?: IslandCooldownManager.getLastActivePackage()
        
        // Debug log: tapOpen event
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "event=tapOpen pkg=${targetPackage ?: "unknown"} keyHash=${targetId?.hashCode() ?: "N/A"}")
        }
        
        // Timeline: tapOpenSourceApp event (PII-safe)
        DebugTimeline.log(
            "tapOpenSourceApp",
            targetPackage,
            targetId,
            mapOf("bridgeId" to targetId)
        )
        
        // Get the original content intent before clearing
        val originalIntent = if (targetId != null) {
            IslandCooldownManager.getContentIntent(targetId)
        } else null
        
        // Cancel the island notification (dismiss UI only, not the source notification)
        if (targetId != null) {
            try {
                NotificationManagerCompat.from(context).cancel(targetId)
                
                // Debug log: autoDismissTriggered event
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "event=autoDismissTriggered reason=OPENED_APP pkg=${targetPackage ?: "unknown"} keyHash=${targetId.hashCode()}")
                }
                
                // Timeline: autoDismissTriggered event
                DebugTimeline.log(
                    "autoDismissTriggered",
                    targetPackage,
                    targetId,
                    mapOf("reason" to "OPENED_APP")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel island notification: ${e.message}")
            }
        }
        
        // Fire the original content intent to open the source app
        if (originalIntent != null) {
            try {
                originalIntent.send()
                Log.d(TAG, "Fired original content intent for $targetPackage")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fire original content intent: ${e.message}")
            }
        } else {
            Log.w(TAG, "No original content intent found for notificationId: $notificationId")
        }
        
        // Record tap-open for PriorityEngine (positive signal)
        if (targetPackage != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val preferences = AppPreferences(context)
                    PriorityEngine.recordTapOpen(preferences, targetPackage)
                    Log.d(TAG, "Recorded PriorityEngine tap-open for $targetPackage")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record PriorityEngine tap-open: ${e.message}")
                }
            }
        }
        
        // Clear meta and content intent for this notification ID
        if (notificationId != null) {
            IslandCooldownManager.clearIslandMeta(notificationId)
            IslandCooldownManager.clearContentIntent(notificationId)
        }
    }

    /**
     * Shows a debug toast with the action route type (Activity/Broadcast/Service).
     * Only shown in debug builds when the setting is enabled.
     * No runtime cost when disabled.
     */
    private fun showDebugRouteToast(context: Context, routeType: String) {
        if (!BuildConfig.DEBUG) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = AppPreferences(context)
                val enabled = preferences.isActionLongPressInfoEnabled()
                if (enabled) {
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(
                            context,
                            context.getString(R.string.debug_route_toast, routeType),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show debug route toast: ${e.message}")
            }
        }
    }
}
