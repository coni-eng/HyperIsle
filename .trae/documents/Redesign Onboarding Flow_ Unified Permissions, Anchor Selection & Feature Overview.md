I will redesign the onboarding flow to combine permissions, add an anchor selection screen, and include a feature overview.

**1. Create** **`UnifiedPermissionsPage`**

* **Goal:** Consolidate all permission requests into a single, scrollable list.

* **Content:**

  * Iterate through `PermissionRegistry.allPermissions` (Notification Listener, Overlay, Post Notifications, Battery).

  * Display each permission with an Icon, Title, Description, and a Action Button (Grant/Allow).

  * Show a "Check" icon when granted.

  * **Logic:** The "Continue" button in the main pager will only be enabled when *Required* permissions (Listener, Overlay) are granted.

**2. Create** **`AnchorSelectionPage`**

* **Goal:** Allow users to choose their preferred Island behavior.

* **Options:**

  * **Dynamic Island (Always On):** The black pill is always visible around the camera (iPhone 14 Pro style). Maps to `AnchorVisibilityMode.ALWAYS`.

  * **Smart Island (Auto-Hide):** The pill is hidden and only appears when there is an active task (Music, Timer, Nav). Maps to `AnchorVisibilityMode.SMART`.

* **Implementation:** Two large selectable cards with visual previews (icons).

**3. Create** **`FeatureOverviewPage`**

* **Goal:** A final summary page showing key capabilities.

* **Content:** A grid or list of feature cards:

  * **Navigation:** Turn-by-turn directions & ETA.

  * **Music Control:** Play/Pause & Seek without opening apps.

  * **Notifications:** Quick Reply & Expandable details.

  * **Smart Priority:** Important alerts (Calls, Timers) take precedence.

* **Design:** Simple cards with icons and short text, fitting on one screen.

**4. Update** **`OnboardingScreen.kt`**

* **Refactor Pager:** Reduce page count and reorder:

  1. Welcome
  2. Explanation
  3. Privacy
  4. Compatibility
  5. **UnifiedPermissionsPage** (Replaces the 3 separate permission pages)
  6. **AnchorSelectionPage** (New)
  7. **FeatureOverviewPage** (New - Final Step)

* **Navigation Logic:** Ensure the "Finish" button appears only on the Feature Overview page.

**Files to Modify/Create:**

* `app/src/main/java/com/coni/hyperisle/ui/screens/onboarding/OnboardingScreen.kt` (Main Orchestrator)

* `app/src/main/java/com/coni/hyperisle/ui/screens/onboarding/UnifiedPermissionsPage.kt` (New)

* `app/src/main/java/com/coni/hyperisle/ui/screens/onboarding/AnchorSelectionPage.kt` (New)

* `app/src/main/java/com/coni/hyperisle/ui/screens/onboarding/FeatureOverviewPage.kt` (New)

**Verification:**

* I will compile the app to ensure no compilation errors.

* The logic for checking permissions will be verified against `PermissionRegistry`.

