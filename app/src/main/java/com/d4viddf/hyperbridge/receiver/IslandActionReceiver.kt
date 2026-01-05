package com.d4viddf.hyperbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.d4viddf.hyperbridge.MainActivity
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.util.Haptics
import com.d4viddf.hyperbridge.util.IslandCooldownManager
import com.d4viddf.hyperbridge.util.PriorityEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling island action button clicks.
 * Listens for:
 * - miui.focus.action_options -> Opens Quick Actions UI
 * - miui.focus.action_dismiss -> Cancels island + records cooldown + haptic
 */
class IslandActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IslandActionReceiver"
        const val ACTION_OPTIONS = "miui.focus.action_options"
        const val ACTION_DISMISS = "miui.focus.action_dismiss"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received action: $action")

        // Parse notification ID from action string (e.g., "miui.focus.action_options_12345")
        val notificationId = parseNotificationId(action)

        when {
            action.startsWith(ACTION_OPTIONS) -> handleOptions(context, notificationId)
            action.startsWith(ACTION_DISMISS) -> handleDismiss(context, notificationId)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    /**
     * Parses the notification ID from the action string.
     * Action format: "miui.focus.action_options_12345" or "miui.focus.action_dismiss_-987654"
     * @return The parsed notification ID, or null if not found
     */
    private fun parseNotificationId(action: String): Int? {
        // Find the last underscore and parse the number after it
        val lastUnderscoreIndex = action.lastIndexOf('_')
        if (lastUnderscoreIndex == -1) return null
        
        val idString = action.substring(lastUnderscoreIndex + 1)
        return idString.toIntOrNull()
    }

    private fun handleOptions(context: Context, notificationId: Int?) {
        Log.d(TAG, "Opening Quick Actions screen for notificationId: $notificationId")
        
        // Get package from meta map, fallback to last active
        val targetPackage = if (notificationId != null) {
            IslandCooldownManager.getIslandMeta(notificationId)?.first
        } else null ?: IslandCooldownManager.getLastActivePackage()
        
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openQuickActions", true)
            targetPackage?.let { putExtra("quickActionsPackage", it) }
        }
        
        context.startActivity(launchIntent)
    }

    private fun handleDismiss(context: Context, notificationId: Int?) {
        Log.d(TAG, "Dismissing island for notificationId: $notificationId")
        
        // Get meta from map for this specific notification ID
        val meta = if (notificationId != null) {
            IslandCooldownManager.getIslandMeta(notificationId)
        } else null
        
        val targetId = notificationId ?: IslandCooldownManager.getLastActiveNotificationId()
        val targetPackage = meta?.first ?: IslandCooldownManager.getLastActivePackage()
        val targetType = meta?.second ?: IslandCooldownManager.getLastActiveType()
        
        // Cancel the specific notification
        if (targetId != null) {
            try {
                NotificationManagerCompat.from(context).cancel(targetId)
                Log.d(TAG, "Cancelled notification id: $targetId")
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
}
