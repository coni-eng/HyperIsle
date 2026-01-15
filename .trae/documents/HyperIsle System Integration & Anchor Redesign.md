# HyperIsle System Integration & Anchor Redesign Plan

## 1. Native System Integration (Music, Timer, etc.)
We will disable HyperIsle's handling of specific categories to allow the native Xiaomi/HyperOS island to take over.
- **NotificationReaderService.kt**: 
  - Update logic to **ignore** `MEDIA` and `TIMER` notifications completely.
  - Add system packages (Sound Recorder, Screen Recorder) to the ignore list to prevent them from appearing as generic notifications.
- **SystemModesReceiver.kt**: Disable System State islands (Mute/DND/Flashlight) logic to let the system handle them.

## 2. Settings Cleanup
We will remove settings that are no longer relevant since the system handles these features.
- **GlobalSettingsScreen.kt**: Remove "Music Island" and "System State" settings cards.
- **AppPreferences.kt**: Ensure default values for these disabled features are set to `false`.

## 3. Anchor Island Redesign
We will update the Anchor Island to match the requested aesthetics and layout.
- **AnchorPill.kt**:
  - **Dimensions**: Increase `pillHeight` (34dp -> 37dp) and `slotMinWidth` (48dp -> 54dp) for a more substantial look matching the native style.
  - **Active Call Layout**: Redesign `CallAnchorPill` to place the **Icon + Waveform on the LEFT** and **Duration on the RIGHT**.

## 4. User Information
- **OnboardingScreen.kt**: Update the feature overview to inform users that "Daily tools like Music, Timer, and Recorder are handled natively by your system for the best experience."

## 5. Priority Behavior
- The existing priority logic naturally supports the requirement: Since we are disabling Media/Timer in HyperIsle, the system island will show. If a high-priority HyperIsle event (like a Call or Navigation) occurs, HyperIsle will draw its overlay, effectively "crushing" (overlaying) the system island.

**Verification:**
- Compiling and running will ensure no build errors.
- Visual verification (by user) will confirm the new Anchor size and Call layout.
