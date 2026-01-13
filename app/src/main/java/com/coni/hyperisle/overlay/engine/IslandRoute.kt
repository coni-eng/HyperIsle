package com.coni.hyperisle.overlay.engine

/**
 * Routing decision for an island event.
 * Determines how/where the island should be displayed.
 * 
 * CRITICAL: Only ONE route can be active at a time for the same notification key.
 * Route tekilleştirme (deduplication) happens in IslandCoordinator.
 */
enum class IslandRoute {
    /**
     * Show in our own overlay window (app-drawn island).
     * Used when MIUI bridge is not available or when we want full control.
     */
    APP_OVERLAY,

    /**
     * Use MIUI/HyperOS native island bridge.
     * Preferred when available as it integrates with system UI.
     */
    MIUI_BRIDGE,

    /**
     * Route to system media controls (e.g., HyperOS native media island).
     * Used for media when user prefers system UI.
     */
    SYSTEM_MEDIA,

    /**
     * Route to HyperOS native island (non-media).
     * Used for calls/timers when HyperOS island is preferred.
     */
    HYPEROS,

    /**
     * No overlay should be shown.
     * Used for suppressed notifications or when dialer is foreground.
     */
    NONE;

    fun isOverlay(): Boolean = this == APP_OVERLAY
    fun isBridge(): Boolean = this == MIUI_BRIDGE || this == HYPEROS
    fun isSystem(): Boolean = this == SYSTEM_MEDIA || this == HYPEROS
    fun shouldRender(): Boolean = this != NONE
}
