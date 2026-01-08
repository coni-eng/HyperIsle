package com.coni.hyperisle.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Centralized notification channel management for HyperIsle.
 * Single source of truth for channel IDs and channel state checks.
 */
object NotificationChannels {
    
    const val ISLAND_CHANNEL_ID = "hyper_isle_island_channel"
    const val SUMMARY_CHANNEL_ID = "hyper_isle_summary_channel"
    
    /**
     * Creates all required notification channels for HyperIsle.
     * Safe to call multiple times - channels are only created if they don't exist.
     */
    fun createChannels(context: Context, islandChannelName: String, summaryChannelName: String? = null) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        
        // Island channel - high importance for Dynamic Island notifications
        val islandChannel = NotificationChannel(
            ISLAND_CHANNEL_ID,
            islandChannelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(islandChannel)
        
        // Summary channel - default importance for daily summaries
        if (summaryChannelName != null) {
            val summaryChannel = NotificationChannel(
                SUMMARY_CHANNEL_ID,
                summaryChannelName,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(summaryChannel)
        }
    }
    
    /**
     * Checks if HyperIsle notifications are enabled at system level.
     * Returns true if notifications are enabled.
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    
    /**
     * Checks if the Island notification channel is enabled (not IMPORTANCE_NONE).
     * Returns true if the channel exists and is enabled.
     */
    fun isIslandChannelEnabled(context: Context): Boolean {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(ISLAND_CHANNEL_ID)
        return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }
    
    /**
     * Gets the importance level of the Island channel.
     * Returns IMPORTANCE_NONE if channel doesn't exist.
     */
    fun getIslandChannelImportance(context: Context): Int {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(ISLAND_CHANNEL_ID)
        return channel?.importance ?: NotificationManager.IMPORTANCE_NONE
    }
    
    /**
     * Safety gate check: Returns true if it's safe to cancel system notifications.
     * This should be called before any cancelNotification() call.
     * 
     * Conditions for safety:
     * 1. HyperIsle notifications must be enabled
     * 2. Island channel must not be IMPORTANCE_NONE
     * 
     * If either condition fails, the user won't see the Island notification,
     * so we must NOT cancel the system notification.
     */
    fun isSafeToCancel(context: Context): Boolean {
        return areNotificationsEnabled(context) && isIslandChannelEnabled(context)
    }
    
    /**
     * Returns a human-readable reason why it's not safe to cancel.
     * Returns null if it IS safe to cancel.
     */
    fun getUnsafeReason(context: Context): String? {
        if (!areNotificationsEnabled(context)) {
            return "NOTIFICATIONS_DISABLED"
        }
        if (!isIslandChannelEnabled(context)) {
            return "ISLAND_CHANNEL_DISABLED"
        }
        return null
    }
}
