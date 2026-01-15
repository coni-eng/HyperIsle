# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v1.0.3] - Native Integration & Anchor Redesign
### Added
- **Native System Integration:**
  - Music, Timer, and Recorder notifications are now handled by the system's native island for a seamless experience.
  - Added specific ignore rules for Xiaomi/HyperOS system apps (Sound Recorder, Screen Recorder, Camera).
- **Redesigned Active Call Anchor:**
  - New layout: Icon + Waveform Animation on the Left, Call Duration on the Right.
  - Enhanced visual hierarchy for active calls.

### Changed
- **Anchor Dimensions:** Increased default anchor size (Height: 37dp, Slot Width: 54dp) to better match native system aesthetics.
- **Google Maps Blocker:** Improved "Block Google Maps Island" logic to correctly dismiss the system PiP window.
- **Onboarding:** Updated Feature Overview to highlight seamless native integration.
- **Settings:** Moved "Block Google Maps Island" to Navigation Settings and removed obsolete Music Island settings.

## [v1.0.0] - Visual Polish & Customization
### Added
- **Island Colors:** Per-app customization support.
  - **Standard:** Default iOS-style solid black.
  - **Dynamic:** Extracts accent color from app icon (legacy behavior).
  - **Custom:** Manually select hex color or presets.
- **Haptic Lab:** New diagnostics tool to test hardware vibration motor patterns (Tick, Click, Heavy, Buzz).
- **Onboarding Redesign:**
  - Unified permissions page for streamlined setup.
  - Anchor Style selection (Dynamic vs Smart) during setup.
  - Feature overview page.
- **Island Color Apps Screen:** New settings page to manage per-app color preferences.

### Changed
- **Default Visuals:** Notification islands now use **Standard Black** (100% opaque) by default for a closer-to-native look.
- **Anchor Pill:** Updated to solid black to match new notification style.
- **Navigation Layout:** Fixed centering issues where the island would drift off-screen; now strictly centered relative to the camera cutout.
- **Setup Flow:** significantly reduced steps and improved clarity.

## [v0.9.6] - Permission Guidance & Setup Health
### Added
- Setup Health screen with required and recommended permission guidance.
- Setup incomplete banner that highlights missing required permissions.
- Debug/QA "Mark Issue" action to log a diagnostics timeline marker.
### Improved
- Locale-aware formatting for diagnostics sizes and timer durations.
- Shade-cancel hint posting now checks notification permission and handles security exceptions.

## [v0.9.5] - iOS Pill Overlay & Shade Cancel Clarity
### Added
- Optional iOS-style pill overlay alongside the system island (requires overlay permission).
- Dedicated notification channel for the overlay service.
- Per-app shade-cancel hint for persistent notifications that Android/MIUI cannot clear.

## [v0.9.4] - Debug Export & Smart Priority Transparency
### Added
- Diagnostics time range filtering (10m to 24h).
- Diagnostics export options (Clipboard, Text, JSON).
- Per-app Smart Priority profiles (Normal, Lenient, Strict).

### Improved
- Smart Priority explainability in diagnostics.
- Clearer debug summaries.

## [v0.9.3] - Smart Priority Intelligence
### Added
- **Burst control:** Suppresses rapid-fire notifications from the same app.
- **Adaptive learning signals:** Fast dismisses reduce priority; taps increase it.
- **Context-aware Smart Priority:** Meeting/Driving presets protect calls/nav/timers.

### Improved
- Priority decision performance ("cheap-first" evaluation).
- Bounded learning signals with decay.

## [v0.9.2] - Action Diagnostics & Debug UX
### Added
- Debug Action Diagnostics (counters, ring buffer).
- Long-press on actions to view routing info (Debug builds).

### Improved
- Robust PendingIntent handling and type inference.
- Focus action string parsing hardening.

## [v0.9.1] - Summary Digest Hardening
### Added
- Explicit settings entry for Notification Summary.

### Improved
- Digest now reliably records suppressed notifications (Focus, Priority, etc.).
- Suppression diagnostics for troubleshooting.

## [v0.9.0] - Context Presets & Summary Upgrades
### Added
- **Context Presets:** OFF, MEETING, DRIVING, HEADPHONES.
- **Summary V2:** Time-bucket grouping, top apps, inline actions.

### Changed
- SmartFeatures screen UI update for presets.
- Reader service applies presets before Focus mode.

## [v0.8.0] - Polishes
### Changed
- Live Activity polish (minVisibleMs, dedupe window).
- Smart Priority learning improvements (weighted decay, quiet-hours bias).

## [v0.7.0] - Context-Aware Islands
### Added
- Screen-off filtering for important types.
- Charging banner suppression.
- ContextSignalsReceiver.

## [v0.6.0] - Smart Priority Engine
### Added
- Spam detection and throttling engine.
- Smart Priority settings and aggressiveness slider.
- Dismiss counters integration.

## [v0.5.0] - Live Activity & Adaptive Visuals
### Added
- Live Activity state machine.
- Adaptive accent color extraction.
- Progress smoothing.

## [v0.1.0] - Initial Release
- Core HyperIsland functionality.
