I will implement the requested color changes and the Per-App Color customization UI.

**1. Backend & Defaults (Standard Black by Default)**

* **File:** `app/src/main/java/com/coni/hyperisle/data/AppPreferences.kt`

* **Action:**

  * Change `isAppColorAuto` default to `false`. This disables "Dynamic Color" by default.

  * Update `getEffectiveIslandColor` to return `#FF000000` (Solid Black) when in "Standard" mode (not auto, no custom color).

**2. Update Anchor & Pill Colors**

* **File:** `app/src/main/java/com/coni/hyperisle/overlay/anchor/AnchorPill.kt`

* **Action:** Change `PillBackgroundColor` to `Color.Black` (100% Opaque).

* **File:** `app/src/main/java/com/coni/hyperisle/ui/components/IosPills.kt`

* **Action:** Ensure the notification background logic respects the solid black color without applying unwanted transparency.

**3. Create "Island Colors" Screen (Per-App UI)**

* **File:** `app/src/main/java/com/coni/hyperisle/ui/screens/settings/IslandColorAppsScreen.kt` (New)

* **Action:** Create a screen listing all apps. Clicking an app opens a configuration dialog.

* **Dialog Features:**

  * **Mode Selector:** \[Standard (Black)] | \[Dynamic (App Icon)] | \[Custom]

  * **Custom Color Picker:** A grid of preset colors (Red, Blue, Green, etc.) and a Hex Input field.

**4. Integrate into Settings**

* **File:** `app/src/main/java/com/coni/hyperisle/MainActivity.kt`

* **Action:** Add navigation route for `Screen.ISLAND_COLOR_APPS`.

* **File:** `app/src/main/java/com/coni/hyperisle/ui/screens/settings/InfoScreen.kt`

* **Action:** Add an "Island Colors" menu item under the Configuration section.

**5. Add Resources**

* **File:** `app/src/main/res/values/strings.xml`

* **Action:** Add strings for "Island Colors", "Color Mode", "Standard", "Dynamic", "Custom", etc.

