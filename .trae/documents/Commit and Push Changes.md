I will commit and push the latest changes which include fixes for:
1.  **Anchor Positioning:** Corrected `OverlayWindowController` flags and `AnchorOverlayHost` padding to align the island with the camera cutout.
2.  **Anchor "Always" Mode:** Updated `MainActivity` and `IslandOverlayService` to ensure the anchor service starts correctly on app launch.
3.  **Notification Animation:** Improved `OverlayWindowController` to reuse windows, preventing blinking during notification updates.
4.  **Notification Stash:** Added system notification generation in `NotificationLabScreen` to test the stash feature.
5.  **Compilation Fixes:** Resolved missing imports in various feature files.

**Git Commands to be executed:**
1.  `git add .`
2.  `git commit -m "Fix anchor positioning, animation blinking, and always-on mode; add stash test support"`
3.  `git push origin main`