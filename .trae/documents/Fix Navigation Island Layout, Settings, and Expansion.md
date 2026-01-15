I will fix the reported issues with the Lock Screen visibility, Navigation Island layout, functionality (Tap/Long Press), and settings.

**1. Fix Lock Screen Visibility**

* **Diagnosis:** `IslandOverlayService` respects `AnchorVisibilityMode`, but the default preference might be preventing it from showing, or the View visibility isn't correctly toggled even if the Window flag `FLAG_SHOW_WHEN_LOCKED` is set.

* **Action:** Ensure `updateAnchorVisibility` in `IslandOverlayService.kt` correctly sets the view to `VISIBLE` when `AnchorVisibilityMode.ALWAYS` is active, regardless of lock state.

**2. Fix Navigation Island Layout (Stuck Right & Cut-off)**

* **Diagnosis:** The `NavAnchorPill` implementation in `AnchorPill.kt` likely causes layout issues when combined with `OverlayWindowController`'s centering logic. The "sticking to right" suggests an unbalanced `Row` or incorrect width constraints.

* **Action:** Refactor `NavAnchorPill` in `AnchorPill.kt` to ensure it is properly centered and respects the `WRAP_CONTENT` window behavior. I will check the `Spacer` and slot width logic.

**3. Implement Navigation Settings (Left/Right Slots)**

* **Diagnosis:** `IslandOverlayService` currently hardcodes the navigation slots to `INSTRUCTION` and `ETA`, ignoring user preferences.

* **Action:**

  * Modify `AnchorCoordinator.kt` to read `AppPreferences` (or accept configuration) for `leftInfoType` and `rightInfoType`.

  * Update `IslandOverlayService.kt` to pass these preferences when updating the navigation state.

**4. Fix Navigation Tap & Long Press (Expanded Mode)**

* **Diagnosis:**

  * **Tap:** `IslandOverlayService` handles the tap, but `handleNotificationTap` might need verification for Navigation intents.

  * **Long Press:** `handleAnchorLongPress` is empty, so long-pressing does nothing.

  * **Expansion:** There is no logic to render an expanded Navigation view in `currentExpandedContent`.

* **Action:**

  * Implement `handleAnchorLongPress` in `IslandOverlayService.kt` to switch to a new `NAV_EXPANDED` mode.

  * Update `AnchorCoordinator.kt` to support `NAV_EXPANDED`.

  * Update `currentExpandedContent` in `IslandOverlayService.kt` to render the expanded Navigation view (using `NavFeature.Render` or a similar composable) when in `NAV_EXPANDED` mode.

**5. Verify & Fix Double Overlay**

* **Diagnosis:** Google Maps PiP appearing alongside HyperIsle is expected behavior (system PiP vs app overlay), but HyperIsle shouldn't be cut off. Fixing the layout in Step 2 should resolve the cut-off.

**Files to Modify:**

* `app/src/main/java/com/coni/hyperisle/overlay/IslandOverlayService.kt`

* `app/src/main/java/com/coni/hyperisle/overlay/anchor/AnchorCoordinator.kt`

* `app/src/main/java/com/coni/hyperisle/overlay/anchor/AnchorPill.kt`

* `app/src/main/java/com/coni/hyperisle/models/AnchorState.kt` (to add `NAV_EXPANDED`)

