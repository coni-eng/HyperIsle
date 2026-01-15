# Fix Anchor Island Inconsistency, Stash Logic, and Touch Blocking

## 1. Fix Touch Blocking (Status Bar Inaccessible)

The Anchor Island overlay currently consumes all touch events across the entire width of the screen, preventing interaction with the system status bar (Notification Shade/Control Center).

* **Action**: Modify `AnchorOverlayHost.kt` to remove `.fillMaxWidth()` from the root `Box`.

* **Rationale**: The `OverlayWindowController` is configured with `WRAP_CONTENT` width. By removing `fillMaxWidth()` from the Compose content, the window will shrink to fit the actual island pill size when idle. This allows touch events in the empty space (left/right of the island) to pass through to the system status bar.

## 2. Fix Stash System Failure (Test Notifications)

Test notifications from the "Notification Lab" (internal diagnostics) are being ignored by the listener service, preventing the "Stash" logic from being tested or working for them.

* **Action**: Update `NotificationReaderService.kt` -> `shouldIgnore()` function.

* **Change**: Allow notifications from the app's own package (`BuildConfig.APPLICATION_ID`) if `BuildConfig.DEBUG` is true.

* **Rationale**: The current logic strictly ignores `this.packageName` to prevent feedback loops, but this blocks the internal diagnostic tools.

## 3. Fix Anchor Island Flicker & Inconsistency

The Anchor Island sometimes disappears or flickers ("comes and goes") due to race conditions between the "Keep Alive" logic and the dismissal logic.

* **Action**: Refine `IslandOverlayService.kt`.

  * In `dismissAllOverlays()`: Ensure `removeOverlay()` is **never** called if `shouldStayAlive` is true. The previous fix had a conditional, but I will harden it to ensure no edge cases trigger removal.

  * In `dismissNotificationOverlay()`: Apply the same strict guard.

  * In `updateAnchorVisibility()`: Ensure it cancels any pending `stopForegroundJob` immediately to prevent a delayed "stop" command from killing the just-revived anchor.

* **Rationale**: Consolidating the "Keep Alive" check ensures that transitioning from a notification/call back to the Anchor never destroys the window, maintaining a stable, flicker-free state.

## 4. Verification

* **Touch**: Verify status bar can be pulled down when Anchor is idle.

* **Stash**: Verify test notifications from Lab appear in the status bar after the initial "snooze".

* **Anchor**: Verify toggling "Anchor Always" works instantly and the island remains stable.

