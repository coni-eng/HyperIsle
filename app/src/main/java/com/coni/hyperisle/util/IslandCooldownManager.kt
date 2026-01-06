package com.coni.hyperisle.util

import android.content.Context
import com.coni.hyperisle.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages cooldown periods after island dismissal.
 * Prevents the same notification type from the same app from showing
 * for a configurable period after explicit dismissal.
 */
object IslandCooldownManager {

    // Key format: "packageName:notificationType" -> dismissal timestamp
    private val cooldownMap = ConcurrentHashMap<String, Long>()

    // Meta map: notificationId -> (packageName, notificationType)
    // Used for per-island dismiss/options actions
    private val metaMap = ConcurrentHashMap<Int, Pair<String, String>>()

    // Last active island tracking for receiver usage (fallback only)
    @Volatile private var lastActiveNotificationId: Int? = null
    @Volatile private var lastActivePackage: String? = null
    @Volatile private var lastActiveType: String? = null

    /**
     * Records a dismissal event for the given package and notification type.
     * @param packageName The source app package name
     * @param notificationType The notification type (e.g., "STANDARD", "PROGRESS")
     */
    fun recordDismissal(packageName: String, notificationType: String) {
        val key = buildKey(packageName, notificationType)
        cooldownMap[key] = System.currentTimeMillis()
    }

    /**
     * Checks if the given package/type combination is currently in cooldown.
     * @param context Context for accessing preferences
     * @param packageName The source app package name
     * @param notificationType The notification type
     * @return true if in cooldown (should skip showing), false otherwise
     */
    fun isInCooldown(context: Context, packageName: String, notificationType: String): Boolean {
        val key = buildKey(packageName, notificationType)
        val dismissTime = cooldownMap[key] ?: return false
        
        val cooldownSeconds = getCooldownSeconds(context)
        if (cooldownSeconds <= 0) return false
        
        val cooldownMs = cooldownSeconds * 1000L
        val now = System.currentTimeMillis()
        
        if (now - dismissTime < cooldownMs) {
            return true
        }
        
        // Cooldown expired, clean up
        cooldownMap.remove(key)
        return false
    }

    /**
     * Clears cooldown for a specific package/type (e.g., when user unmutes).
     */
    fun clearCooldown(packageName: String, notificationType: String) {
        val key = buildKey(packageName, notificationType)
        cooldownMap.remove(key)
    }

    /**
     * Clears all cooldowns for a package (e.g., when user unblocks app).
     */
    fun clearAllCooldownsForPackage(packageName: String) {
        val keysToRemove = cooldownMap.keys.filter { it.startsWith("$packageName:") }
        keysToRemove.forEach { cooldownMap.remove(it) }
    }

    /**
     * Clears all cooldowns (e.g., on service restart).
     */
    fun clearAll() {
        cooldownMap.clear()
    }

    /**
     * Stores the last active island info for receiver usage.
     * Called when an island is posted/updated.
     */
    fun setLastActiveIsland(notificationId: Int, packageName: String, notificationType: String) {
        lastActiveNotificationId = notificationId
        lastActivePackage = packageName
        lastActiveType = notificationType
    }

    /**
     * Gets the last active notification ID.
     */
    fun getLastActiveNotificationId(): Int? = lastActiveNotificationId

    /**
     * Gets the last active package name.
     */
    fun getLastActivePackage(): String? = lastActivePackage

    /**
     * Gets the last active notification type.
     */
    fun getLastActiveType(): String? = lastActiveType

    /**
     * Clears last active island info (e.g., when island is dismissed).
     */
    fun clearLastActiveIsland() {
        lastActiveNotificationId = null
        lastActivePackage = null
        lastActiveType = null
    }

    /**
     * Stores island metadata for per-island action handling.
     * @param notificationId The bridge notification ID
     * @param packageName The source app package name
     * @param notificationType The notification type
     */
    fun setIslandMeta(notificationId: Int, packageName: String, notificationType: String) {
        metaMap[notificationId] = Pair(packageName, notificationType)
    }

    /**
     * Gets island metadata by notification ID.
     * @return Pair of (packageName, notificationType) or null if not found
     */
    fun getIslandMeta(notificationId: Int): Pair<String, String>? {
        return metaMap[notificationId]
    }

    /**
     * Clears island metadata for a specific notification ID.
     */
    fun clearIslandMeta(notificationId: Int) {
        metaMap.remove(notificationId)
    }

    private fun buildKey(packageName: String, notificationType: String): String {
        return "$packageName:$notificationType"
    }

    private fun getCooldownSeconds(context: Context): Int {
        return try {
            val preferences = AppPreferences(context)
            runBlocking { preferences.dismissCooldownSecondsFlow.first() }
        } catch (e: Exception) {
            30 // Default 30 seconds
        }
    }
}
