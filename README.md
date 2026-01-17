<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="150" alt="HyperIsle Logo" style="border-radius: 20%;" />
</p>

<h1 align="center">HyperIsle</h1>

<p align="center">
  <strong>Bring a native-style HyperIsland to third-party apps on HyperOS.</strong>
</p>

<p align="center">
  HyperIsle gives the system island real utility by adding iOS-inspired dynamic
  island behavior on Xiaomi / HyperOS devices while keeping the native look.
</p>

<br>

<!-- Optional hero image -->
<!--
<p align="center">
  <img src="docs/hero.png" width="85%" />
</p>
-->

---

## Recent Updates (v0.9.9)

- Native system integration for Music, Timer, and Recorder notifications.
- Redesigned active call anchor with icon and waveform on the left, duration on the right.
- Google Maps blocker logic improved to handle Picture-in-Picture windows.
- Island colors with per-app customization: Standard (black), Dynamic (app icon), or Custom (hex).
- Default visuals now use solid black for a cleaner, more native look.
- Onboarding simplified with a unified permissions flow and anchor style selection.
- Haptic Lab added for testing hardware vibration patterns.
- Navigation centering fixes for a more stable island position.

## Features (Highlights)

- Native HyperOS visuals for notification islands.
- Island colors per app: Standard, Dynamic, or Custom.
- Context presets for quick filtering: OFF, MEETING, DRIVING, HEADPHONES.
- Notification Summary V2 with time buckets, top apps, and inline mute/block actions.
- Media and navigation islands with album art and turn-by-turn instructions.
- Smart Priority engine to reduce noise with throttling and cooldowns.
- Focus Mode for quiet hours with priority overrides.
- Live Activity engine for smooth updates instead of stacking.
- Haptics with a dedicated hardware test lab.

---

<details>
<summary><strong>Full feature list</strong></summary>

### Native and Smart Integration

* Media
  * Album art, now playing status, visualizer support.
* Navigation
  * Real-time turn-by-turn instructions (Google Maps, Waze).
  * Customizable split layout (Distance / ETA / Instruction).
* Downloads
  * Progress indicator with completion feedback.
* Calls
  * Dedicated layouts for incoming and active calls with timers.
* System Notifications
  * DND and Silent mode visibility on the island.
* Smart Silence
  * Prevents spammy repeats within a configurable time window.
* Haptics
  * Subtle "shown" haptic on island display.
  * Distinct "success" haptic on dismiss.
  * Haptic Lab: Test hardware patterns (Tick, Click, Heavy, Buzz).

### Explainable Notification Decisions

When Smart Priority suppresses or allows a notification, the reason is traceable:

- Burst suppression
- Fast dismiss learning
- App profile bias (Strict / Lenient)
- Preset bias (Meeting / Driving)
- Focus mode overrides

This makes HyperIsle predictable, transparent, and trustworthy.

### Standard Island Actions

* Tap - Opens the source app (safe fallback).
* Options - Quick Actions (mute / block per app).
* Dismiss - Closes the island and applies cooldown (prevents immediate re-appearing).

### Smart UX and Behavior

* Live Activity engine for smooth updates instead of stacking.
* Smart Priority engine that learns from dismisses and taps:
  - Burst control shows only the latest notification from noisy apps.
  - Fast dismisses reduce priority, while taps boost important apps.
  - During MEETING or DRIVING presets, calls, timers, and navigation are not throttled.
* Context-aware islands adapt to screen and charging state.
* Adaptive visuals with app-based accent colors and polished progress animations.
* Context presets (v0.9.0) for quick filtering.
* Notification Summary V2 (v0.9.0) with time buckets, top apps, and inline actions.

### Privacy and Control

* Spoiler protection
  * Block specific terms globally or per app (for example, message spoilers).
* Ghost Mode
  * Hide the persistent service notification while keeping islands active.
* Total control
  * Choose which apps trigger islands.
  * Customize timeouts and floating behavior per app.

</details>

---

## Debug Diagnostics (Developer builds only)

HyperIsle includes debug-only diagnostics tools to make real-world issues easy to investigate.

<details>
<summary><strong>Details</strong></summary>

- Time-range filtering:
  View or export diagnostics for:
  - Last 10 minutes
  - Last 30 minutes
  - Last 1 hour
  - Last 6 hours
  - Last 12 hours
  - Last 24 hours

- Export options:
  - Copy to clipboard
  - Share diagnostics as plain text
  - Save diagnostics as `.txt` (debug builds only)
  - Optional JSON format for structured analysis

- Issue markers (debug/QA builds):
  - "Mark Issue" button writes a timeline marker for later review.

- PII-safe by design:
  - No notification titles or message content
  - Only package names, timestamps, counters, and reason codes

These tools are disabled in release builds and have zero performance impact unless explicitly used.

</details>

**To help with logs:**
> Open Action diagnostics -> reproduce the issue -> tap Copy diagnostics summary -> send it.

---

## Media Music Island Mode

HyperIsle lets you control how Music Island behaves on Xiaomi / HyperOS devices.

By default, the system native (HyperOS) music island is used, and HyperIsle does not create duplicates.

<details>
<summary><strong>Full Modes</strong></summary>

### Modes

#### SYSTEM_ONLY (Default - Recommended)
* Uses the native HyperOS music island.
* HyperIsle does not generate any music island.
* Most stable option.
* Lock screen and notification media controls remain intact.

#### BLOCK_SYSTEM (Advanced)
* Suppresses the native HyperOS music island for selected music apps.
* Implemented by cancelling the app MediaStyle notification.

Important
* Lock screen and notification shade media controls may disappear.
* Selecting at least one music app is mandatory.
* Misconfiguration may negatively affect the lock screen music experience.

If you are unsure, keep SYSTEM_ONLY enabled.

</details>

---

## Who is HyperIsle for?

* Xiaomi / HyperOS users who like the Dynamic Island concept.
* Power users who want fine-grained control over notifications.
* Developers and tinkerers who appreciate transparency and debug tooling.
* Users who want fewer interruptions without missing important notifications.

---

## Supported Languages

* Turkish (Default)
* English

---

## Tech Stack

* Language: Kotlin
* UI: Jetpack Compose (Material 3 Expressive)
* Services: NotificationListenerService, BroadcastReceiver
* Concurrency: Kotlin Coroutines and Flow

---

## Installation

### Build from Source
1. Clone this repository.
2. Open in Android Studio (Ladybug or newer recommended).
3. Sync Gradle and run on a Xiaomi / POCO / Redmi device (API 35+).

### Required Setup
1. Grant Notification Access when prompted.
2. Follow the in-app guide to enable:
   * Autostart
   * No Restrictions (Battery)

   This prevents the system from killing the background service.

3. Optional: If you enable the iOS-style pill overlay, grant "Display over other apps".

---

## Open Source and License (Apache 2.0)

This project is built on top of the following open-source projects:

### HyperBridge
* Author: D4vidDf
* License: Apache License 2.0
* Source: https://github.com/D4vidDf/HyperBridge

### HyperIsland ToolKit
* Author: D4vidDf
* License: Apache License 2.0
* Source: https://github.com/D4vidDf/HyperIsland-ToolKit

HyperIsle:
* Properly attributes original authors
* Complies with all Apache 2.0 terms
* Clearly separates modified and original work

---

## Attribution

This project is based on open-source work by D4vidDf, licensed under Apache 2.0:

* HyperBridge - Original application foundation
* HyperIsland ToolKit - HyperOS Dynamic Island toolkit
  (mirrored at `coni-eng/My-HyperIsland-ToolKit`)

---

## Maintainer

coni-eng
GitHub: [@coni-eng](https://github.com/coni-eng)
