package com.coni.hyperisle.models

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.ui.graphics.vector.ImageVector
import com.coni.hyperisle.R
import com.coni.hyperisle.ui.screens.settings.isIgnoringBatteryOptimizations
import com.coni.hyperisle.util.isAccessibilityServiceEnabled
import com.coni.hyperisle.util.isAutostartEnabled
import com.coni.hyperisle.util.isNotificationServiceEnabled
import com.coni.hyperisle.util.isOverlayPermissionGranted
import com.coni.hyperisle.util.isPhonePermissionGranted
import com.coni.hyperisle.util.isPostNotificationsEnabled
import com.coni.hyperisle.util.openAccessibilitySettings
import com.coni.hyperisle.util.openAppNotificationSettings
import com.coni.hyperisle.util.openAutostartSettings
import com.coni.hyperisle.util.openBatterySettings
import com.coni.hyperisle.util.openNotificationListenerSettings
import com.coni.hyperisle.util.openOverlaySettings
import com.coni.hyperisle.util.openPhonePermissionSettings



/**
 * Represents a permission item with its metadata and status.
 * Single source of truth for permission definitions.
 */
data class PermissionItem(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val icon: ImageVector,
    val isRequired: Boolean,
    val isGranted: (Context) -> Boolean,
    val openSettings: (Context) -> Unit
)

/**
 * Central registry of all permissions used by HyperIsle.
 */
object PermissionRegistry {
    
    /**
     * Notification Listener - REQUIRED
     * Core functionality: reading notifications to create islands
     */
    val NOTIFICATION_LISTENER = PermissionItem(
        id = "notification_listener",
        titleRes = R.string.notif_access,
        descriptionRes = R.string.notif_access_desc,
        icon = Icons.Default.NotificationsActive,
        isRequired = true,
        isGranted = { context -> isNotificationServiceEnabled(context) },
        openSettings = { context -> openNotificationListenerSettings(context) }
    )
    
    /**
     * Overlay Permission - REQUIRED
     * Core UI requirement: islands cannot be displayed without overlay
     * No degraded mode; treat as strictly required
     */
    val OVERLAY = PermissionItem(
        id = "overlay",
        titleRes = R.string.perm_overlay_title,
        descriptionRes = R.string.perm_overlay_desc,
        icon = Icons.Default.Layers,
        isRequired = true,
        isGranted = { context -> isOverlayPermissionGranted(context) },
        openSettings = { context -> openOverlaySettings(context) }
    )

    /**
     * Accessibility Service - RECOMMENDED
     * Used for advanced gestures and interaction control
     */
    val ACCESSIBILITY = PermissionItem(
        id = "accessibility",
        titleRes = R.string.perm_accessibility_title,
        descriptionRes = R.string.perm_accessibility_desc,
        icon = Icons.Default.Accessibility,
        isRequired = false,
        isGranted = { context -> isAccessibilityServiceEnabled(context) },
        openSettings = { context -> openAccessibilitySettings(context) }
    )

    /**
     * Phone Permission - RECOMMENDED
     * Used for call status detection
     */
    val PHONE_CALLS = PermissionItem(
        id = "phone_calls",
        titleRes = R.string.perm_phone_title,
        descriptionRes = R.string.perm_phone_desc,
        icon = Icons.Default.Call,
        isRequired = false,
        isGranted = { context -> isPhonePermissionGranted(context) },
        openSettings = { context -> openPhonePermissionSettings(context) }
    )

    /**
     * Autostart - RECOMMENDED
     * Ensures service restarts after reboot (OEM specific)
     */
    val AUTOSTART = PermissionItem(
        id = "autostart",
        titleRes = R.string.perm_autostart_title,
        descriptionRes = R.string.perm_autostart_desc,
        icon = Icons.Default.Autorenew,
        isRequired = false,
        isGranted = { context -> isAutostartEnabled(context) },
        openSettings = { context -> openAutostartSettings(context) }
    )
    
    /**
     * POST_NOTIFICATIONS (Android 13+) - RECOMMENDED
     * Used for service/bridge notifications
     * App can function without it
     */
    val POST_NOTIFICATIONS = PermissionItem(
        id = "post_notifications",
        titleRes = R.string.show_island,
        descriptionRes = R.string.perm_display_desc,
        icon = Icons.Default.Notifications,
        isRequired = false,
        isGranted = { context -> isPostNotificationsEnabled(context) },
        openSettings = { context -> openAppNotificationSettings(context) }
    )
    
    /**
     * Battery Optimization Ignore - RECOMMENDED
     * Improves stability on aggressive OEMs
     */
    val BATTERY_OPTIMIZATION = PermissionItem(
        id = "battery_optimization",
        titleRes = R.string.battery_unrestricted,
        descriptionRes = R.string.battery_desc,
        icon = Icons.Default.BatteryAlert,
        isRequired = false,
        isGranted = { context -> isIgnoringBatteryOptimizations(context) },
        openSettings = { context -> openBatterySettings(context) }
    )
    
    /**
     * All required permissions.
     */
    val requiredPermissions: List<PermissionItem> = listOf(
        NOTIFICATION_LISTENER,
        OVERLAY
    )
    
    /**
     * All recommended permissions.
     */
    val recommendedPermissions: List<PermissionItem> = listOf(
        POST_NOTIFICATIONS,
        ACCESSIBILITY,
        PHONE_CALLS,
        AUTOSTART,
        BATTERY_OPTIMIZATION
    )
    
    /**
     * All permissions.
     */
    val allPermissions: List<PermissionItem> = requiredPermissions + recommendedPermissions
    
    /**
     * Returns the count of missing required permissions.
     */
    fun getMissingRequiredCount(context: Context): Int {
        return requiredPermissions.count { !it.isGranted(context) }
    }
    
    /**
     * Returns true if any required permission is missing.
     */
    fun hasAnyMissingRequired(context: Context): Boolean {
        return requiredPermissions.any { !it.isGranted(context) }
    }
    
    /**
     * Returns the list of missing required permissions.
     */
    fun getMissingRequired(context: Context): List<PermissionItem> {
        return requiredPermissions.filter { !it.isGranted(context) }
    }
}
