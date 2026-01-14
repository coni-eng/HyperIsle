package com.coni.hyperisle.service

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.models.NotificationType
import com.coni.hyperisle.models.ShadeCancelMode
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.util.NotificationChannels



/**
 * Island Decision Engine - Decoupled decision-making for Island display and shade management.
 * 
 * Contract A (Island delivery): If the app is enabled to show on Island, then ANY posted 
 * notification from that package must produce an Island UI update.
 * 
 * Contract B (Shade management): Based on ShadeCancelMode:
 *   - ISLAND_ONLY: Show island, leave system notification untouched
 *   - HIDE_POPUP_KEEP_SHADE: Snooze notification to suppress popup, keep in shade
 *   - FULLY_HIDE: Cancel notification from shade (if eligible)
 *   - AGGRESSIVE: Force cancel even harder-to-cancel notifications
 * 
 * Contract C (Safety gate): If HyperIsle notifications are disabled or the Island channel 
 * importance is NONE, never cancel/snooze the system notification.
 */
object IslandDecisionEngine {

    private const val TAG = "HI_NOTIF"

    /**
     * Result of the decision engine for a notification.
     * showInIsland, cancelShade, and snoozeShade are INDEPENDENT decisions.
     */
    data class Decision(
        val showInIsland: Boolean,
        val cancelShade: Boolean,
        val snoozeShade: Boolean,
        val cancelShadeAllowed: Boolean,
        val cancelShadeEligible: Boolean,
        val cancelShadeSafe: Boolean,
        val ineligibilityReason: String?,
        val mode: ShadeCancelMode
    ) {
        fun toLogString(pkg: String, keyHash: Int): String {
            val reason = when {
                mode == ShadeCancelMode.STASH -> "STASH_MODE"
                mode == ShadeCancelMode.ISLAND_ONLY -> "ISLAND_ONLY_MODE"
                cancelShadeAllowed && !cancelShadeEligible -> "NOT_ELIGIBLE_${ineligibilityReason ?: "UNKNOWN"}"
                cancelShadeAllowed && !cancelShadeSafe -> "ISLAND_CHANNEL_DISABLED"
                !cancelShadeAllowed -> "SHADE_CANCEL_OFF"
                else -> "OK"
            }
            return "event=DECISION pkg=$pkg keyHash=$keyHash showInIsland=$showInIsland " +
                   "mode=${mode.name} cancelShade=$cancelShade snoozeShade=$snoozeShade " +
                   "cancelShadeEligible=$cancelShadeEligible cancelShadeSafe=$cancelShadeSafe reason=$reason"
        }
    }

    /**
     * Computes the Island decision for a notification.
     * 
     * @param context Application context
     * @param sbn The status bar notification
     * @param type The notification type
     * @param isAppAllowedForIsland Whether the app is in the allowed packages list
     * @param shadeCancelEnabled Whether shade cancel is enabled for this app
     * @param shadeCancelMode The shade cancel mode (SAFE or AGGRESSIVE)
     * @param isOngoingCall For call notifications, whether it's an ongoing call
     * @return Decision with independent showInIsland and cancelShade flags
     */
    fun computeDecision(
        context: Context,
        sbn: StatusBarNotification,
        type: NotificationType,
        isAppAllowedForIsland: Boolean,
        shadeCancelEnabled: Boolean,
        shadeCancelMode: ShadeCancelMode,
        isOngoingCall: Boolean = false
    ): Decision {
        // Contract A: showInIsland is based ONLY on app being allowed
        val showInIsland = isAppAllowedForIsland

        // Contract C: Safety gate - check if Island channel is usable
        val cancelShadeSafe = NotificationChannels.isSafeToCancel(context)

        // Normalize legacy SAFE mode to ISLAND_ONLY
        @Suppress("DEPRECATION")
        val effectiveMode = when (shadeCancelMode) {
            ShadeCancelMode.SAFE -> ShadeCancelMode.ISLAND_ONLY
            else -> shadeCancelMode
        }

        // Contract B: Determine action based on mode
        // STASH: Never cancel or snooze - keep in shade + status bar (best for messaging)
        // ISLAND_ONLY: Never cancel or snooze
        // HIDE_POPUP_KEEP_SHADE: Snooze only (no cancel)
        // FULLY_HIDE / AGGRESSIVE: Cancel if eligible
        val isStash = ShadeCancelMode.isStashMode(effectiveMode)
        val cancelShadeAllowed = shadeCancelEnabled && !isStash && ShadeCancelMode.shouldCancel(effectiveMode)
        val snoozeShadeAllowed = shadeCancelEnabled && !isStash && ShadeCancelMode.shouldSnooze(effectiveMode)

        // Compute eligibility based on notification properties
        val eligibilityResult = checkShadeCancelEligibility(sbn, type, effectiveMode, isOngoingCall)
        val cancelShadeEligible = eligibilityResult.first
        val ineligibilityReason = if (!cancelShadeEligible) eligibilityResult.second else null

        // Final decisions
        val cancelShade = cancelShadeAllowed && cancelShadeEligible && cancelShadeSafe
        val snoozeShade = snoozeShadeAllowed && cancelShadeSafe && !cancelShade

        val decision = Decision(
            showInIsland = showInIsland,
            cancelShade = cancelShade,
            snoozeShade = snoozeShade,
            cancelShadeAllowed = cancelShadeAllowed,
            cancelShadeEligible = cancelShadeEligible,
            cancelShadeSafe = cancelShadeSafe,
            ineligibilityReason = ineligibilityReason,
            mode = effectiveMode
        )

        // Log decision in debug builds
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, decision.toLogString(sbn.packageName, sbn.key.hashCode()))
        }

        return decision
    }

    /**
     * Checks if a notification is eligible for shade cancellation.
     * Returns Pair(eligible, reason) where reason explains why not eligible (if false).
     * 
     * Eligibility rules depend on ShadeCancelMode:
     * - SAFE (default): Do NOT cancel foreground service or ongoing notifications
     * - AGGRESSIVE: Allow cancelling even FOREGROUND_SERVICE notifications
     * 
     * Common rules (both modes):
     * - NOT call/alarm/timer/navigation category (unless it's an ongoing call with calls-only-island)
     * - No full-screen intent
     * - Not a group summary
     */
    private fun checkShadeCancelEligibility(
        sbn: StatusBarNotification,
        type: NotificationType,
        mode: ShadeCancelMode,
        isOngoingCall: Boolean
    ): Pair<Boolean, String> {
        val notification = sbn.notification
        val flags = notification.flags
        val category = notification.category

        // Special handling for call notifications (calls-only-island feature)
        if (type == NotificationType.CALL) {
            return checkCallShadeCancelEligibility(sbn, isOngoingCall)
        }

        if (!sbn.isClearable) {
            return Pair(false, "NOT_CLEARABLE")
        }

        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return Pair(false, "FOREGROUND_SERVICE")
        }

        if (category == Notification.CATEGORY_SERVICE) {
            return Pair(false, "CATEGORY_SERVICE")
        }

        // Check group summary flag (always blocked)
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return Pair(false, "GROUP_SUMMARY")
        }

        // Check full-screen intent (always blocked)
        if (notification.fullScreenIntent != null) {
            return Pair(false, "FULLSCREEN_INTENT")
        }

        // Check notification type (CALL, TIMER, NAVIGATION are never cancelled via standard path)
        if (type == NotificationType.TIMER) {
            return Pair(false, "TYPE_TIMER")
        }
        if (type == NotificationType.NAVIGATION) {
            return Pair(false, "TYPE_NAVIGATION")
        }

        // Check category for alarm-like notifications (always blocked)
        if (category == Notification.CATEGORY_CALL) {
            return Pair(false, "CATEGORY_CALL")
        }
        if (category == Notification.CATEGORY_ALARM) {
            return Pair(false, "CATEGORY_ALARM")
        }
        if (category == Notification.CATEGORY_NAVIGATION) {
            return Pair(false, "CATEGORY_NAVIGATION")
        }
        if (category == Notification.CATEGORY_STOPWATCH) {
            return Pair(false, "CATEGORY_STOPWATCH")
        }

        // Mode-dependent checks
        @Suppress("DEPRECATION")
        when (mode) {
            ShadeCancelMode.STASH -> {
                // STASH: Never cancel - keep in shade + status bar (best for messaging)
                return Pair(false, "STASH_MODE")
            }
            ShadeCancelMode.ISLAND_ONLY -> {
                // ISLAND_ONLY: Never cancel - this shouldn't be reached but handle gracefully
                return Pair(false, "ISLAND_ONLY_MODE")
            }
            ShadeCancelMode.HIDE_POPUP_KEEP_SHADE -> {
                // HIDE_POPUP_KEEP_SHADE: Snooze only, same eligibility as FULLY_HIDE
                if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) {
                    return Pair(false, "ONGOING_EVENT")
                }
            }
            ShadeCancelMode.FULLY_HIDE, ShadeCancelMode.SAFE -> {
                // FULLY_HIDE/SAFE: Block ongoing and foreground service notifications
                if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) {
                    return Pair(false, "ONGOING_EVENT")
                }
            }
            ShadeCancelMode.AGGRESSIVE -> {
                // AGGRESSIVE mode: Allow foreground service, but still block ongoing non-FGS
                val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
                val isForegroundService = (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
                if (isOngoing && !isForegroundService) {
                    return Pair(false, "ONGOING_NON_FGS")
                }
            }
        }

        // All checks passed - eligible for cancellation
        return Pair(true, "ELIGIBLE")
    }

    /**
     * Checks if a call notification is eligible for shade cancellation.
     * Returns Pair(eligible, reason) where reason explains why not eligible (if false).
     * 
     * Eligibility rules for calls-only-island (must ALL be true):
     * - isOngoingCall == true (not incoming)
     * - isOngoing flag set (FLAG_ONGOING_EVENT)
     * - No full-screen intent
     * - Not a group summary
     */
    private fun checkCallShadeCancelEligibility(
        sbn: StatusBarNotification,
        isOngoingCall: Boolean
    ): Pair<Boolean, String> {
        val notification = sbn.notification
        val flags = notification.flags

        if (!sbn.isClearable) {
            return Pair(false, "NOT_CLEARABLE")
        }

        if ((flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return Pair(false, "FOREGROUND_SERVICE")
        }

        // Must be an ongoing call (not incoming)
        if (!isOngoingCall) {
            return Pair(false, "NOT_ONGOING_CALL")
        }

        // Must have ongoing flag set
        if ((flags and Notification.FLAG_ONGOING_EVENT) == 0) {
            return Pair(false, "NO_ONGOING_FLAG")
        }

        // Check group summary flag
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return Pair(false, "GROUP_SUMMARY")
        }

        // Check full-screen intent - never hide incoming/full-screen calls
        if (notification.fullScreenIntent != null) {
            return Pair(false, "FULLSCREEN_INTENT")
        }

        // All checks passed - eligible for cancellation
        return Pair(true, "ELIGIBLE")
    }

    /**
     * Checks if a notification is a group summary.
     * Group summaries should still trigger Island for child notifications.
     */
    fun isGroupSummary(sbn: StatusBarNotification): Boolean {
        return (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
    }

    /**
     * Gets the group key for a notification, or null if not grouped.
     */
    fun getNotificationGroupKey(sbn: StatusBarNotification): String? {
        return sbn.notification.group
    }
}
