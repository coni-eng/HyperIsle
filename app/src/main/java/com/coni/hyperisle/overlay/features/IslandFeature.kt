package com.coni.hyperisle.overlay.features

import androidx.compose.runtime.Composable
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute

/**
 * Interface for island features.
 * Each feature handles a specific type of island (call, notification, timer, etc.)
 * 
 * Features are stateless - state is managed by the coordinator.
 * Features do NOT have access to Context or WindowManager.
 */
interface IslandFeature {
    /**
     * Unique identifier for this feature.
     */
    val id: String

    /**
     * Check if this feature can handle the given event.
     */
    fun canHandle(event: IslandEvent): Boolean

    /**
     * Reduce previous state + event into new state.
     * Returns null if the event should be ignored.
     * 
     * @param prev Previous state (null if first event for this feature/key)
     * @param event The incoming event
     * @param nowMs Current timestamp
     * @return New state, or null to ignore
     */
    fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any?

    /**
     * Calculate priority for this state.
     * Higher = more important. Used for preemption decisions.
     */
    fun priority(state: Any?): Int

    /**
     * Determine preferred route for this state.
     */
    fun route(state: Any?): IslandRoute

    /**
     * Get policy for this state.
     */
    fun policy(state: Any?): IslandPolicy

    /**
     * Render the composable UI for this state.
     * 
     * @param state Feature-specific state object
     * @param uiState Current UI state (expanded, collapsed, replying)
     * @param actions Actions interface for host callbacks
     * @return Composable content
     */
    @Composable
    fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    )
}

/**
 * UI state passed to feature render functions.
 */
data class FeatureUiState(
    val isExpanded: Boolean = false,
    val isCollapsed: Boolean = false,
    val isReplying: Boolean = false,
    val debugRid: Int = 0
) {
    companion object {
        fun from(island: ActiveIsland): FeatureUiState = FeatureUiState(
            isExpanded = island.isExpanded,
            isCollapsed = island.isCollapsed,
            isReplying = island.isReplying,
            debugRid = island.notificationKey.hashCode()
        )
    }
}
