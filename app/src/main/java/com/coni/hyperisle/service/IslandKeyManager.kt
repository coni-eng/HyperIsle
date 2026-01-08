package com.coni.hyperisle.service

import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages stable keying for Island notifications to ensure proper group/summary handling.
 * 
 * Contract A guarantee: Maintains a per-package "latest island candidate keyHash" 
 * and only dismisses when the latest is removed.
 * 
 * This prevents premature Island dismissal when:
 * - A group summary is removed but child notifications are still active
 * - An older notification is removed but a newer one is still active
 * - Notifications are "unautobundled" by the system
 */
object IslandKeyManager {

    /**
     * Per-package latest notification key hash.
     * Key: packageName
     * Value: Pair(keyHash, postTime) of the latest notification
     */
    private val latestIslandCandidate = ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * Maps sbn.key to packageName for cleanup on removal.
     */
    private val keyToPackage = ConcurrentHashMap<String, String>()

    /**
     * Tracks active notification keys per package.
     * Key: packageName
     * Value: Set of active sbn.key values
     */
    private val activeKeysPerPackage = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Records a notification as the latest candidate for Island display.
     * Should be called when a notification is about to be shown on Island.
     * 
     * @param sbn The status bar notification
     * @return true if this is a new/updated candidate, false if older than current
     */
    fun recordIslandCandidate(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        val keyHash = sbn.key.hashCode()
        val postTime = sbn.postTime

        keyToPackage[sbn.key] = pkg

        // Track active keys per package
        activeKeysPerPackage.getOrPut(pkg) { mutableSetOf() }.add(sbn.key)

        val current = latestIslandCandidate[pkg]
        if (current == null || postTime >= current.second) {
            latestIslandCandidate[pkg] = Pair(keyHash, postTime)
            return true
        }
        return false
    }

    /**
     * Checks if a notification removal should trigger Island dismissal.
     * 
     * @param sbn The removed notification
     * @return true if the Island should be dismissed (no more active notifications for this package)
     */
    fun shouldDismissIslandOnRemoval(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        val key = sbn.key

        // Remove from active keys
        activeKeysPerPackage[pkg]?.remove(key)
        keyToPackage.remove(key)

        // Check if there are still active notifications for this package
        val remainingKeys = activeKeysPerPackage[pkg]
        if (remainingKeys.isNullOrEmpty()) {
            // No more active notifications - safe to dismiss Island
            latestIslandCandidate.remove(pkg)
            activeKeysPerPackage.remove(pkg)
            return true
        }

        // There are still active notifications - don't dismiss Island
        return false
    }

    /**
     * Checks if the given notification is the latest candidate for its package.
     * 
     * @param sbn The notification to check
     * @return true if this is the latest candidate
     */
    fun isLatestCandidate(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        val keyHash = sbn.key.hashCode()
        val current = latestIslandCandidate[pkg] ?: return false
        return current.first == keyHash
    }

    /**
     * Gets the count of active notifications for a package.
     */
    fun getActiveCountForPackage(pkg: String): Int {
        return activeKeysPerPackage[pkg]?.size ?: 0
    }

    /**
     * Clears tracking for a specific package.
     * Should be called when user explicitly dismisses Island.
     */
    fun clearPackage(pkg: String) {
        latestIslandCandidate.remove(pkg)
        val keys = activeKeysPerPackage.remove(pkg)
        keys?.forEach { keyToPackage.remove(it) }
    }

    /**
     * Clears all tracking state.
     * Should be called on service destroy.
     */
    fun clearAll() {
        latestIslandCandidate.clear()
        keyToPackage.clear()
        activeKeysPerPackage.clear()
    }
}
