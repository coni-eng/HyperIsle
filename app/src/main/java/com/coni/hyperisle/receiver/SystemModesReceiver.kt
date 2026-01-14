package com.coni.hyperisle.receiver

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.media.AudioManager
import com.coni.hyperisle.util.HiLog
import androidx.annotation.RequiresPermission
import androidx.core.content.edit
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.util.SystemHyperIslandPoster
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Broadcast receiver for system mode changes (ringer mode, DND).
 * Posts Hyper Island notifications for state changes with debouncing.
 */
class SystemModesReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SystemModesReceiver"
        
        // Debounce interval in milliseconds
        private const val DEBOUNCE_INTERVAL_MS = 2000L
        
        // SharedPreferences for state caching
        private const val PREFS_NAME = "system_modes_state"
        private const val KEY_LAST_RINGER_MODE = "last_ringer_mode"
        private const val KEY_LAST_DND_FILTER = "last_dnd_filter"
        private const val KEY_LAST_RINGER_TIME = "last_ringer_time"
        private const val KEY_LAST_DND_TIME = "last_dnd_time"

        /**
         * Check DND state from MainActivity (fallback for devices with flaky broadcasts).
         * Should be called from onCreate/onResume with minimal frequency.
         */
        @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
        fun checkDndStateOnAppLaunch(context: Context) {
            // System state islands are disabled by default - HyperOS handles these natively
            val appPreferences = AppPreferences(context)
            val isEnabled = runBlocking { appPreferences.systemStateIslandsEnabledFlow.first() }
            if (!isEnabled) return

            val poster = SystemHyperIslandPoster(context)
            if (!poster.hasNotificationPermission()) return

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val currentFilter = notificationManager.currentInterruptionFilter
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastFilter = prefs.getInt(KEY_LAST_DND_FILTER, -1)
            val lastTime = prefs.getLong(KEY_LAST_DND_TIME, 0L)
            val now = System.currentTimeMillis()

            // Only check if state changed and debounce passed
            if (currentFilter != lastFilter && lastFilter != -1 && (now - lastTime) >= DEBOUNCE_INTERVAL_MS) {
                prefs.edit {
                    putInt(KEY_LAST_DND_FILTER, currentFilter)
                    putLong(KEY_LAST_DND_TIME, now)
                }

                val message = when {
                    currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL -> 
                        context.getString(R.string.dnd_disabled)
                    notificationManager.isNotificationPolicyAccessGranted -> when (currentFilter) {
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> 
                            context.getString(R.string.dnd_priority)
                        NotificationManager.INTERRUPTION_FILTER_ALARMS -> 
                            context.getString(R.string.dnd_alarms)
                        NotificationManager.INTERRUPTION_FILTER_NONE -> 
                            context.getString(R.string.dnd_enabled)
                        else -> context.getString(R.string.dnd_changed_generic)
                    }
                    else -> context.getString(R.string.dnd_changed_generic)
                }

                val title = context.getString(R.string.app_name)
                poster.postSystemNotification(
                    SystemHyperIslandPoster.DND_NOTIFICATION_ID,
                    title,
                    message
                )
            } else if (lastFilter == -1) {
                // First run - just save state
                prefs.edit {
                    putInt(KEY_LAST_DND_FILTER, currentFilter)
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onReceive(context: Context, intent: Intent?) {
        // Security: Validate intent before processing
        val action = intent?.action ?: return
        
        // Security: Whitelist - only accept known system mode actions
        if (action != AudioManager.RINGER_MODE_CHANGED_ACTION &&
            action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
            return
        }
        
        Log.d(TAG, "Received system mode broadcast")

        // System state islands are disabled by default - HyperOS handles these natively
        val appPreferences = AppPreferences(context)
        val isEnabled = runBlocking { appPreferences.systemStateIslandsEnabledFlow.first() }
        if (!isEnabled) {
            Log.d(TAG, "System state islands disabled, ignoring broadcast")
            return
        }

        val poster = SystemHyperIslandPoster(context)
        
        // Check notification permission first
        if (!poster.hasNotificationPermission()) {
            Log.d(TAG, "No notification permission, ignoring broadcast")
            return
        }

        when (action) {
            AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                handleRingerModeChange(context, poster)
                // Also check DND as fallback for devices that don't send DND broadcasts
                checkDndStateFallback(context, poster)
            }
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                handleDndChange(context, poster)
            }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun handleRingerModeChange(context: Context, poster: SystemHyperIslandPoster) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentMode = audioManager.ringerMode
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastMode = prefs.getInt(KEY_LAST_RINGER_MODE, -1)
        val lastTime = prefs.getLong(KEY_LAST_RINGER_TIME, 0L)
        val now = System.currentTimeMillis()

        // Debounce: ignore if same mode within interval
        if (currentMode == lastMode && (now - lastTime) < DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "Ringer mode debounced: $currentMode")
            return
        }

        // Save new state
        prefs.edit {
            putInt(KEY_LAST_RINGER_MODE, currentMode)
            putLong(KEY_LAST_RINGER_TIME, now)
        }

        val message = when (currentMode) {
            AudioManager.RINGER_MODE_SILENT -> context.getString(R.string.mode_silent_enabled)
            AudioManager.RINGER_MODE_VIBRATE -> context.getString(R.string.mode_vibrate_enabled)
            AudioManager.RINGER_MODE_NORMAL -> context.getString(R.string.mode_sound_enabled)
            else -> return
        }

        val title = context.getString(R.string.app_name)
        poster.postSystemNotification(
            SystemHyperIslandPoster.RINGER_MODE_NOTIFICATION_ID,
            title,
            message
        )
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun handleDndChange(context: Context, poster: SystemHyperIslandPoster) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentFilter = notificationManager.currentInterruptionFilter
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFilter = prefs.getInt(KEY_LAST_DND_FILTER, -1)
        val lastTime = prefs.getLong(KEY_LAST_DND_TIME, 0L)
        val now = System.currentTimeMillis()

        // Debounce: ignore if same filter within interval
        if (currentFilter == lastFilter && (now - lastTime) < DEBOUNCE_INTERVAL_MS) {
            HiLog.d(HiLog.TAG_ISLAND, "DND filter debounced: $currentFilter")
            return
        }

        // Save new state
        prefs.edit {
            putInt(KEY_LAST_DND_FILTER, currentFilter)
            putLong(KEY_LAST_DND_TIME, now)
        }

        val message = getDndMessage(context, notificationManager, currentFilter)
        val title = context.getString(R.string.app_name)
        
        poster.postSystemNotification(
            SystemHyperIslandPoster.DND_NOTIFICATION_ID,
            title,
            message
        )
    }

    private fun getDndMessage(
        context: Context,
        notificationManager: NotificationManager,
        filter: Int
    ): String {
        // Check if we have notification policy access for detailed messages
        val hasAccess = notificationManager.isNotificationPolicyAccessGranted

        return if (hasAccess) {
            when (filter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> 
                    context.getString(R.string.dnd_disabled)
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> 
                    context.getString(R.string.dnd_priority)
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> 
                    context.getString(R.string.dnd_alarms)
                NotificationManager.INTERRUPTION_FILTER_NONE -> 
                    context.getString(R.string.dnd_enabled)
                else -> context.getString(R.string.dnd_changed_generic)
            }
        } else {
            // No policy access - use generic message
            when (filter) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> 
                    context.getString(R.string.dnd_disabled)
                else -> context.getString(R.string.dnd_changed_generic)
            }
        }
    }

    /**
     * Fallback DND check for devices that don't reliably send DND broadcasts.
     * Called when ringer mode changes to also check DND state.
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun checkDndStateFallback(context: Context, poster: SystemHyperIslandPoster) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentFilter = notificationManager.currentInterruptionFilter
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFilter = prefs.getInt(KEY_LAST_DND_FILTER, -1)
        
        // Only post if DND state actually changed (not just ringer mode)
        if (currentFilter != lastFilter && lastFilter != -1) {
            val now = System.currentTimeMillis()
            val lastTime = prefs.getLong(KEY_LAST_DND_TIME, 0L)
            
            // Still apply debounce
            if ((now - lastTime) >= DEBOUNCE_INTERVAL_MS) {
                prefs.edit {
                    putInt(KEY_LAST_DND_FILTER, currentFilter)
                    putLong(KEY_LAST_DND_TIME, now)
                }

                val message = getDndMessage(context, notificationManager, currentFilter)
                val title = context.getString(R.string.app_name)
                
                poster.postSystemNotification(
                    SystemHyperIslandPoster.DND_NOTIFICATION_ID,
                    title,
                    message
                )
            }
        } else if (lastFilter == -1) {
            // First run - just save state without posting
            prefs.edit {
                putInt(KEY_LAST_DND_FILTER, currentFilter)
            }
        }
    }

}
