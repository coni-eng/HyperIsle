package com.coni.hyperisle.models



/**
 * Per-app Smart Priority profile that affects throttling aggressiveness.
 * 
 * - NORMAL: Default behavior, no bias applied
 * - LENIENT: Less aggressive throttling (fewer suppressions)
 * - STRICT: More aggressive throttling (more suppressions)
 * 
 * Only affects STANDARD notification throttling. Does NOT affect:
 * - CALL/TIMER/NAV bypass logic (priority types always allowed)
 * - Focus mode hard overrides
 * - Preset integration
 */
enum class SmartPriorityProfile {
    NORMAL,
    LENIENT,
    STRICT
}
