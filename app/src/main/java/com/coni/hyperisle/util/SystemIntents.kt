package com.coni.hyperisle.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import com.coni.hyperisle.service.ContextSignalsAccessibilityService



/**
 * Opens the hidden Xiaomi Autostart management screen.
 */
fun openAutostartSettings(context: Context) {
    try {
        val intent = Intent()
        intent.component = ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:${context.packageName}".toUri()
            context.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(context, "Settings not found", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Checks if Autostart is likely enabled (Best effort check for Xiaomi)
 * Note: There is no public API to check this reliably on MIUI.
 * We return false by default to encourage user to check.
 */
fun isAutostartEnabled(context: Context): Boolean {
    // Cannot reliably check on MIUI without root/system privs.
    // We assume false so the user is prompted to check it.
    // Alternatively, we could save a preference when user clicks "Enable"
    return false 
}

/**
 * Opens the Battery Optimization screen for this app.
 */
@SuppressLint("BatteryLife")
fun openBatterySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = "package:${context.packageName}".toUri()
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:${context.packageName}".toUri()
            context.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(context, "Settings not found", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Checks if Notification Listener permission is granted.
 */
fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(pkgName)
}

/**
 * Checks if Post Notification permission (Android 13+) is granted.
 */
fun isPostNotificationsEnabled(context: Context): Boolean {
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/**
 * Checks if Overlay (Display over other apps) permission is granted.
 */
fun isOverlayPermissionGranted(context: Context): Boolean {
    return Settings.canDrawOverlays(context)
}

/**
 * Opens the Overlay permission settings for this app.
 */
fun openOverlaySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        intent.data = "package:${context.packageName}".toUri()
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:${context.packageName}".toUri()
            context.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(context, "Settings not found", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Opens the Notification Listener settings.
 */
fun openNotificationListenerSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:${context.packageName}".toUri()
            context.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(context, "Settings not found", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Opens the App Notification settings (for POST_NOTIFICATIONS on Android 13+).
 */
fun openAppNotificationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:${context.packageName}".toUri()
            context.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(context, "Settings not found", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Checks if HyperIsle Accessibility context service is enabled.
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, ContextSignalsAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
}

/**
 * Opens the Accessibility settings screen.
 */
fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:${context.packageName}".toUri()
            context.startActivity(intent)
        } catch (e2: Exception) {
            Toast.makeText(context, "Settings not found", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Checks if Phone permission is granted.
 */
fun isPhonePermissionGranted(context: Context): Boolean {
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.READ_PHONE_STATE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/**
 * Opens App Info settings to grant Phone permission.
 */
fun openPhonePermissionSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = "package:${context.packageName}".toUri()
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Settings not found", Toast.LENGTH_SHORT).show()
    }
}

