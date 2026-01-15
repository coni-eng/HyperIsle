# Fix Test Notifications, Tap Actions, and Lock Screen Visibility

## 1. Fix Test Notifications Not Stashing
The "Notification Lab" test notifications are not stashing because the stash logic (`isClearableStandard`) likely excludes the app's own package (`BuildConfig.APPLICATION_ID`) or the `sbn.key` stability fix I implemented needs to be fully applied to the self-notification path.
- **Action**: In `NotificationReaderService.kt`, update `shouldIgnore()` to allow self-notifications if `BuildConfig.DEBUG` is true (already done, but verify).
- **Refinement**: The main issue is likely in `isClearableStandard` check or `isMiuiDevice` check. I will ensure that for debug builds, we bypass the strict manufacturer check or ensure the test notification has the correct category/flags to pass `isClearableStandard`.
- **Key Fix**: In `NotificationReaderService.kt`, specifically for `BuildConfig.DEBUG`, allow `IMMEDIATE_SNOOZE` even if not MIUI, to test the stash logic on any device (or ensure the emulator/test device mimics MIUI if needed).

## 2. Fix Tap Action (Opening Chat)
Tapping the notification currently tries to send the `contentIntent` (PendingIntent). If this fails or does nothing, it's often because:
- The `PendingIntent` requires background activity start permissions (which services don't have on modern Android).
- The `PendingIntent` is specific to the notification shade context.
**Action**: Modify `IslandOverlayService.kt` -> `handleNotificationTap`.
- **Fix**: Use `PendingIntent.send()` with an `ActivityOptions` bundle that specifies `setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` if possible (requires privilege), or more reliably:
- **Better Fix**: Request the `PendingIntent` to be sent, but if that fails, try to launch the package's main activity.
- **Root Cause**: The logs show `CALL_TAP_FAIL` for calls, but for regular notifications, `handleNotificationTap` is likely hitting a similar block. I need to ensure `handleNotificationTap` properly executes `contentIntent.send()`.
- **Refinement**: I will inspect `handleNotificationTap` (I read `handleCallTap` but need to double-check `handleNotificationTap` specifically). If it's missing, I'll add robust fallback logic similar to `handleCallTap` (try `contentIntent`, then `launchIntent`).

## 3. Fix Lock Screen Visibility
The anchor is not visible on the lock screen.
- **Action**: Modify `OverlayWindowController.kt`.
- **Fix**: Add `WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED` to the window flags.
- **Detail**: In `OverlayWindowController.kt`, inside `showOverlay`, add `FLAG_SHOW_WHEN_LOCKED` (or `setShowWhenLocked(true)` for newer APIs) to the `WindowManager.LayoutParams`. This is the standard way to allow an overlay to appear on top of the keyguard.

## Verification Plan
1.  **Stash**: Send test notification -> Verify it disappears and reappears in status bar.
2.  **Tap**: Send Telegram msg -> Tap island -> Verify Telegram opens.
3.  **Lock Screen**: Lock device -> Verify Anchor Island is visible.