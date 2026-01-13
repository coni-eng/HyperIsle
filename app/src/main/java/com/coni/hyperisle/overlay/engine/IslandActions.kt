package com.coni.hyperisle.overlay.engine

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle

/**
 * Action interface for features to request host/service operations.
 * Features call these methods; the host/service implements them.
 * 
 * This decouples features from Context, WindowManager, and service internals.
 */
interface IslandActions {
    // ==================== OVERLAY CONTROL ====================

    /**
     * Request overlay dismiss.
     */
    fun dismiss(reason: String)

    /**
     * Request expand to full view.
     */
    fun expand()

    /**
     * Request collapse to mini view.
     */
    fun collapse()

    /**
     * Toggle expanded state.
     */
    fun toggleExpand()

    /**
     * Enter reply mode (for notifications with RemoteInput).
     */
    fun enterReplyMode()

    /**
     * Exit reply mode.
     */
    fun exitReplyMode()

    // ==================== INTENT ACTIONS ====================

    /**
     * Send a PendingIntent.
     * @return true if sent successfully
     */
    fun sendPendingIntent(intent: PendingIntent?, actionLabel: String): Boolean

    /**
     * Send inline reply.
     * @return true if sent successfully
     */
    fun sendInlineReply(
        pendingIntent: PendingIntent,
        remoteInputs: Array<RemoteInput>,
        message: String
    ): Boolean

    /**
     * Open the source app (via launch intent).
     */
    fun openApp(packageName: String)

    /**
     * Show in-call UI (for call islands).
     */
    fun showInCallScreen()

    // ==================== CALL ACTIONS ====================

    /**
     * Accept incoming call via TelecomManager.
     * @param fallbackIntent PendingIntent to try if TelecomManager fails
     * @return true if successful
     */
    fun acceptCall(fallbackIntent: PendingIntent? = null): Boolean

    /**
     * End/reject call via TelecomManager.
     * @param fallbackIntent PendingIntent to try if TelecomManager fails
     * @return true if successful
     */
    fun endCall(fallbackIntent: PendingIntent? = null): Boolean

    /**
     * Toggle speaker via AudioManager (fallback for MIUI actions=0).
     * @param fallbackIntent PendingIntent to try first
     * @return true if successful
     */
    fun toggleSpeaker(fallbackIntent: PendingIntent? = null): Boolean

    /**
     * Toggle mute via AudioManager (fallback for MIUI actions=0).
     * @param fallbackIntent PendingIntent to try first
     * @return true if successful
     */
    fun toggleMute(fallbackIntent: PendingIntent? = null): Boolean

    // ==================== HAPTICS ====================

    /**
     * Trigger haptic feedback for island shown.
     */
    fun hapticShown()

    /**
     * Trigger haptic feedback for action success.
     */
    fun hapticSuccess()

    // ==================== LOGGING ====================

    /**
     * Log debug event (only in DEBUG builds).
     */
    fun logDebug(tag: String, event: String, vararg params: Pair<String, Any?>)
}

/**
 * No-op implementation for previews and testing.
 */
object NoOpIslandActions : IslandActions {
    override fun dismiss(reason: String) {}
    override fun expand() {}
    override fun collapse() {}
    override fun toggleExpand() {}
    override fun enterReplyMode() {}
    override fun exitReplyMode() {}
    override fun sendPendingIntent(intent: PendingIntent?, actionLabel: String) = false
    override fun sendInlineReply(pendingIntent: PendingIntent, remoteInputs: Array<RemoteInput>, message: String) = false
    override fun openApp(packageName: String) {}
    override fun showInCallScreen() {}
    override fun acceptCall(fallbackIntent: PendingIntent?) = false
    override fun endCall(fallbackIntent: PendingIntent?) = false
    override fun toggleSpeaker(fallbackIntent: PendingIntent?) = false
    override fun toggleMute(fallbackIntent: PendingIntent?) = false
    override fun hapticShown() {}
    override fun hapticSuccess() {}
    override fun logDebug(tag: String, event: String, vararg params: Pair<String, Any?>) {}
}
