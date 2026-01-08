package com.coni.hyperisle.models

/**
 * Per-app shade cancel mode for notification management.
 * 
 * SAFE (default): Do NOT cancel foreground service or critical notifications.
 * AGGRESSIVE: Allow cancelling even FOREGROUND_SERVICE notifications.
 */
enum class ShadeCancelMode {
    SAFE,
    AGGRESSIVE
}
