package com.coni.hyperisle.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * Helper utility for managing overlay (SYSTEM_ALERT_WINDOW) permission.
 */
object OverlayPermissionHelper {

    private const val TAG = "OverlayPermissionHelper"

    /**
     * Check if the app has overlay permission.
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Open system settings to request overlay permission.
     * Returns true if the intent was launched successfully.
     */
    fun requestOverlayPermission(context: Context): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Launched overlay permission settings")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open overlay permission settings: ${e.message}", e)
            // Fallback to general app settings
            openAppSettings(context)
        }
    }

    /**
     * Open general app settings as fallback.
     */
    private fun openAppSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${context.packageName}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Launched app settings as fallback")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings: ${e.message}", e)
            false
        }
    }

    /**
     * Start the overlay service if permission is granted.
     * Returns true if the service was started, false if permission is missing.
     */
    fun startOverlayServiceIfPermitted(context: Context): Boolean {
        if (!hasOverlayPermission(context)) {
            Log.w(TAG, "Cannot start overlay service: permission not granted")
            return false
        }

        return try {
            val intent = Intent(context, com.coni.hyperisle.overlay.IslandOverlayService::class.java).apply {
                action = com.coni.hyperisle.overlay.IslandOverlayService.ACTION_START
            }
            context.startForegroundService(intent)
            Log.d(TAG, "Overlay service started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start overlay service: ${e.message}", e)
            false
        }
    }

    /**
     * Stop the overlay service.
     */
    fun stopOverlayService(context: Context) {
        try {
            val intent = Intent(context, com.coni.hyperisle.overlay.IslandOverlayService::class.java).apply {
                action = com.coni.hyperisle.overlay.IslandOverlayService.ACTION_STOP
            }
            context.startService(intent)
            Log.d(TAG, "Overlay service stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop overlay service: ${e.message}", e)
        }
    }
}
