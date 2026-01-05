package com.d4viddf.hyperbridge.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Live Activity State Machine for HyperIsle.
 * Manages in-memory state for island activities to enable UPDATE behavior
 * and completion detection.
 */
object IslandActivityStateMachine {

    /**
     * Represents the state of an active island activity.
     */
    data class ActivityState(
        val groupKey: String,
        val notificationId: Int,
        val lastUpdateMs: Long,
        val progress: Int? = null,
        val completed: Boolean = false
    )

    /**
     * Result of processing an activity update.
     */
    sealed class ActivityResult {
        /** New activity - show island */
        data class New(val state: ActivityState) : ActivityResult()
        /** Update existing activity - replace island content */
        data class Update(val state: ActivityState) : ActivityResult()
        /** Activity completed - show completion visuals then dismiss */
        data class Completed(val state: ActivityState, val timeoutMs: Long) : ActivityResult()
    }

    private val activeActivities = ConcurrentHashMap<String, ActivityState>()

    // Completion timeout range (1500-2500ms)
    private const val COMPLETION_TIMEOUT_MIN = 1500L
    private const val COMPLETION_TIMEOUT_MAX = 2500L

    /**
     * Process an activity update.
     * @param groupKey Unique key for the activity (pkg:type)
     * @param notificationId The bridge notification ID
     * @param progress Optional progress value (0-100)
     * @return ActivityResult indicating how to handle this update
     */
    fun processUpdate(
        groupKey: String,
        notificationId: Int,
        progress: Int? = null
    ): ActivityResult {
        val now = System.currentTimeMillis()
        val existing = activeActivities[groupKey]

        // Check for completion
        val isCompleted = progress != null && progress >= 100

        val newState = ActivityState(
            groupKey = groupKey,
            notificationId = notificationId,
            lastUpdateMs = now,
            progress = progress,
            completed = isCompleted
        )

        activeActivities[groupKey] = newState

        return when {
            isCompleted -> {
                val timeout = (COMPLETION_TIMEOUT_MIN..COMPLETION_TIMEOUT_MAX).random()
                ActivityResult.Completed(newState, timeout)
            }
            existing != null -> ActivityResult.Update(newState)
            else -> ActivityResult.New(newState)
        }
    }

    /**
     * Mark an activity as completed (for text-based completion detection).
     * @param groupKey The activity group key
     * @return Completion timeout in ms, or null if activity not found
     */
    fun markCompleted(groupKey: String): Long? {
        val existing = activeActivities[groupKey] ?: return null
        val now = System.currentTimeMillis()

        val completedState = existing.copy(
            lastUpdateMs = now,
            progress = 100,
            completed = true
        )
        activeActivities[groupKey] = completedState

        return (COMPLETION_TIMEOUT_MIN..COMPLETION_TIMEOUT_MAX).random()
    }

    /**
     * Check if an activity exists and is an update (not new).
     */
    fun isUpdate(groupKey: String): Boolean {
        return activeActivities.containsKey(groupKey)
    }

    /**
     * Get the current state of an activity.
     */
    fun getState(groupKey: String): ActivityState? {
        return activeActivities[groupKey]
    }

    /**
     * Get the last known progress for an activity.
     * Used for smooth progress updates (ignore backward jitter).
     */
    fun getLastProgress(groupKey: String): Int? {
        return activeActivities[groupKey]?.progress
    }

    /**
     * Remove an activity from tracking.
     */
    fun remove(groupKey: String) {
        activeActivities.remove(groupKey)
    }

    /**
     * Clear all tracked activities.
     */
    fun clear() {
        activeActivities.clear()
    }
}
