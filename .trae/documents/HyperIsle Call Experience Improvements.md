# HyperIsle Call Experience Improvements

## 1. Suppress System Heads-Up Notification for Incoming Calls
The user reports that the Google Phone app's popup (heads-up notification) appears *over* the HyperIsle anchor for incoming calls. We need to suppress this system behavior so only HyperIsle's UI is visible.
- **Approach:** Use `snoozeNotification(key, duration)` for incoming call notifications. This temporarily removes the notification from the heads-up area (and status bar) without rejecting the call, effectively hiding the system UI while the call continues to ring in the background.
- **Implementation:**
  - Modify `NotificationReaderService.kt` to ensure `snoozeNotification` is called for `NotificationType.CALL` when `isIncoming` is true.
  - The existing code has logic for this (`CALL_INCOMING_SNOOZE_OK`), but we need to ensure it's triggering correctly and aggressively enough to prevent the system popup from appearing first. We might need to adjust the snooze duration or ensure it runs *immediately* before any other processing.

## 2. Fix Double Duration Display
The user sees two different "times" on the anchor island during a call (e.g., "11:34" and "00:19").
- **Analysis:** This happens because `CallTranslator.kt` prioritizes `subText` (which often contains the call start time like "11:34") over our calculated `durationSeconds` timer.
- **Fix:** In `CallTranslator.kt`, we will change the priority logic for `rightText`. If `durationSeconds` is available (which it is for ongoing calls), we should **always** use it as the primary display text, ignoring the system's static `subText` unless the duration is invalid.
- **Action:** Modify `CallTranslator.kt` to prefer `formatDuration(durationSeconds)` over `subText`.

## 3. Adjust Anchor Icon Alignment
The user wants the phone icon in the anchor island to be shifted more to the left.
- **Analysis:** The current `AnchorPill` layout likely has default padding that is too wide for the user's taste in the new compact design.
- **Fix:** In `AnchorPill.kt`, specifically for `CallAnchorPill` and the `IconWithWaveBar` component, we will reduce the start padding (e.g., from `8.dp` to `4.dp` or `0.dp` with a `Spacer`) to push the icon closer to the left edge of the pill.

## Verification
- **Incoming Call:** Verify that the system heads-up notification is suppressed (snoozed) and only the HyperIsle anchor appears.
- **Call Timer:** Verify that only the counting timer (e.g., "00:19") is shown, not the clock time.
- **Layout:** Verify the phone icon is closer to the left edge.
