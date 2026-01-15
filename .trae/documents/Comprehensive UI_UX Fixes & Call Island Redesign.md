I have analyzed the issues and the codebase. Here is the detailed plan to address all your requests:

### 1. Onboarding & Permissions UI (Visual & Functional Fixes)
*   **Page Count Fix:** In `OnboardingScreen.kt`, the page count is inconsistently defined (dots loop vs pager count). I will unify them to `7` pages (0-6) and ensure the "X / 7" text matches the dots.
*   **Permissions Layout:** In `UnifiedPermissionsPage.kt`, I will:
    *   Move the "Enable" button to the bottom of the card for a cleaner look.
    *   Ensure text doesn't overlap or truncate.
    *   Use `stringResource` for all text to prepare for localization.
*   **Autostart Logic:** The "Enable" button for Autostart currently doesn't update its state because Xiaomi's API doesn't return a reliable "enabled" status. I will implement a "best effort" check: when the user clicks it and returns, we will assume it's granted (or store a preference) so the UI updates to "Checked/Green" to give positive feedback.

### 2. Library "Select All" Toggle
*   I will modify the "Select All" button in the App List (likely in `NotificationManagementAppsScreen` or `AppListViewModel`) to toggle between "Select All" and "Unselect All" based on the current selection state.

### 3. Google Maps & Navigation Fixes
*   **Right-Shift Issue:** The logs show `clampedX=0` (centered), but the screenshot shows a shift. This usually happens when the `widthIn` or `padding` constraints in `NotificationOverlayContent` (in `IslandOverlayService.kt`) conflict with the screen width or cutouts. I will enforce a strict center alignment for the Navigation island layout.
*   **Floating Island Blocking:**
    *   The "Block Google Maps Island" setting in `GlobalSettingsScreen.kt` is currently **UI-only** (logic is missing).
    *   I will implement the logic: When this setting is ON, we cannot strictly "close" the other app's overlay (Android security restriction), BUT we can try to dismiss the *notification* that triggers it. However, Maps usually posts a foreground service notification that cannot be dismissed easily.
    *   **Alternative:** I will add a guide/prompt (or use the existing "Ghost Mode" logic) to help the user disable the system notification for Maps navigation, leaving only HyperIsle.
    *   **Toggle Location:** I will move the "Google Maps Block" toggle from `GlobalSettingsScreen` to `NavCustomizationScreen` (Navigation Settings).

### 4. Call Island Redesign (Anchor Integration)
*   **Objective:** Remove the "Collapsed" call pill entirely. Active calls will now live in the **Anchor Island**.
*   **Implementation:**
    *   Modify `AnchorPill.kt`:
        *   **Left Slot:** Show Phone Icon + Timer (Duration).
        *   **Right Slot:** Show Waveform animation (green bars).
    *   **Interaction:** Long-pressing the Anchor Island while in a call will expand to the full Call Overlay (`CallExpandedContent`).
    *   **Cleanup:** Delete `CallCollapsedContent` from `CallOverlay.kt` (or equivalent) and ensure `IslandOverlayService` redirects "collapse" events to the Anchor instead of showing a mini pill.

### 5. Fix Expanded Call Buttons
*   The buttons (Hangup, Speaker, Mute) in the expanded view are reported as non-functional.
*   I will verify `CallActionReceiver` and the `PendingIntent` logic in `CallFeature.kt` to ensure they are correctly broadcasting the commands to the system telecom service.

### 6. Settings UI Bug
*   The "invisible switch" at the bottom of Global Settings is likely a padding/scroll issue. I will increase the bottom padding of the `LazyColumn` or `Column` in `GlobalSettingsScreen.kt` to ensure the last item is visible.

### 7. Localization (Turkish/English)
*   I noticed hardcoded Turkish strings. I will extract these to `strings.xml` and add English translations to ensure the app works in both languages as requested.

**Execution Order:**
1.  **Onboarding & Permissions** (Visuals first)
2.  **Settings Reorg** (Move Maps toggle, fix scroll)
3.  **Call Island Redesign** (Logic change)
4.  **Navigation Fixes** (Layout & Logic)
5.  **Library Toggle** (Small fix)

I will start by executing the **Onboarding** and **Settings** changes.
