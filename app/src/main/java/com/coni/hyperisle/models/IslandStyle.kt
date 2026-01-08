package com.coni.hyperisle.models

/**
 * Island UI Style Contract.
 * 
 * Defines the allowed visual styles for Dynamic Island rendering.
 * Legacy/Android-like expanded action-row styles are blocked and replaced with MODERN_PILL.
 */
enum class IslandStyle {
    /**
     * Modern pill-style island with compact actions.
     * Used for: STANDARD, PROGRESS, TIMER, NAVIGATION notifications.
     * This is the default fallback when legacy styles are detected.
     */
    MODERN_PILL,

    /**
     * Modern call-style island with prominent accept/reject buttons.
     * Used for: CALL notifications (incoming/ongoing).
     */
    MODERN_CALL,

    /**
     * Represents a detected legacy/Android-like expanded action-row style.
     * This style is NEVER rendered - always blocked and replaced with MODERN_PILL.
     * Used internally for logging blocked style attempts.
     */
    LEGACY_BLOCKED
}
