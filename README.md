<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="150" alt="HyperIsle Logo" style="border-radius: 20%;" />
</p>

<h1 align="center">HyperIsle</h1>

<p align="center">
  <strong>Bring the native HyperIsland experience to third-party apps on HyperOS.</strong>
</p>

<p align="center">
  HyperIsle brings boring and useless HOS pill new life by adding 
    iOS-inspired Dynamic Island features and experience on Xiaomi / HyperOS devices.
</p>

<br>

<!-- Optional hero image -->
<!--
<p align="center">
  <img src="docs/hero.png" width="85%" />
</p>
-->

---

## Recent Updates (v1.0.0)

- **Island Colors:** Full per-app customization support. Choose between **Standard (Black)**, **Dynamic (App Icon)**, or **Custom (Hex)** colors.
- **Visual Polish:** Standard iOS-style solid black is now the default for a cleaner, native look.
- **Onboarding Redesign:** Streamlined setup flow with unified permissions and anchor style selection.
- **Haptic Lab:** New diagnostics tool to test hardware vibration motor capabilities.
- **Navigation Fixes:** Improved centering logic for the navigation island.

## 🚀 Features (Highlights)

* **Native HyperOS Visuals** – Notifications rendered as system-style islands.
* **🎨 Island Colors** – Customize notification colors per app (Standard Black, Dynamic, or Custom).
* **🎛️ Context Presets** – One-tap notification filtering:
  **OFF / MEETING / DRIVING / HEADPHONES** (non-media only, Focus Mode always overrides).
* **📊 Notification Summary V2** – iOS-style daily digest with:
  time-bucket grouping, top 3 apps (24h), and inline mute/block actions.
* **🎵 Media & Navigation Islands** – Album art, now playing, and turn-by-turn navigation support.
* **⚡ Smart Priority Engine** – Spam detection, throttling, and adaptive cooldowns.
* **🎯 Focus Mode** – Quiet-hours rules that override floating behavior and restrict visible types.
* **🧠 Live Activity Engine** – Smooth updates instead of stacking notifications.
* **📳 Haptics & Lab** – Subtle haptic feedback with a dedicated lab for hardware testing.

---

<details>
<summary><strong>🔍 Full feature list</strong></summary>

### Native & Smart Integration

* **🎵 Media**
  * Album art, now playing status, visualizer support.
* **🧭 Navigation**
  * Real-time turn-by-turn instructions (Google Maps, Waze).
  * Customizable split layout (Distance / ETA / Instruction).
* **⬇️ Downloads**
  * Circular progress ring with success animation on completion.
* **📞 Calls**
  * Dedicated layouts for incoming and active calls with timers.
* **🔕 System Notifications**
  * DND and Silent mode visibility directly on the island.
* **✨ Smart Silence**
  * Prevents spammy repeats within a configurable time window.
* **📳 Haptics**
  * Subtle “shown” haptic on island display.
  * Distinct “success” haptic on dismiss.
  * **Haptic Lab:** Test hardware patterns (Tick, Click, Heavy, Buzz).

### Explainable Notification Decisions

When Smart Priority suppresses or allows a notification, the reason is traceable:

- Burst suppression
- Fast dismiss learning
- App profile bias (Strict / Lenient)
- Preset bias (Meeting / Driving)
- Focus mode overrides

This makes HyperIsle predictable, transparent, and trustworthy.

### Standard Island Actions

* **Tap** – Opens the source app (safe fallback).
* **Options** – Quick Actions (mute / block per app).
* **Dismiss** – Closes the island and applies cooldown (prevents immediate re-appearing).

### Smart UX & Behavior

* **🧠 Live Activity Engine** – Smooth updates instead of stacking.
* **⚡ Smart Priority** – Auto-throttling based on user behavior.
  * **⚡ Smart Priority Engine** – Learns how you interact with notifications to reduce noise:
  - Burst control shows only the latest notification from noisy apps.
  - Fast dismisses reduce priority, while tapping to open boosts important apps.
  - During MEETING or DRIVING presets, calls, timers, and navigation are never throttled.
* **📱 Context-Aware Islands** – Adapts behavior based on screen and charging state.
* **🎨 Adaptive Visuals** – App-based accent colors and polished progress animations.
* **🎛️ Context Presets (v0.9.0)** – Quick filters for different scenarios.
* **📊 Notification Summary V2 (v0.9.0)** – Time buckets, top apps, inline actions.

### Privacy & Control

* **🛡️ Spoiler Protection**
  * Block specific terms globally or per app (e.g. message spoilers).
* **👻 Ghost Mode**
  * Hide the persistent service notification while keeping islands active.
* **Total Control**
  * Choose which apps trigger islands.
  * Customize timeouts and floating behavior per app.

</details>

---

## 🧪 Debug Diagnostics (Developer builds only)

HyperIsle includes powerful debug-only diagnostics tools to make real-world issues easy to investigate.

<details>
<summary><strong>🔍 Details</strong></summary>

- **Time-range filtering:**  
  View or export diagnostics for:
  - Last 10 minutes
  - Last 30 minutes
  - Last 1 hour
  - Last 6 hours
  - Last 12 hours
  - Last 24 hours

- **Export options:**
  - Copy to clipboard
  - Share diagnostics as plain text
  - Save diagnostics as `.txt` (debug builds only)
  - Optional JSON format for structured analysis

- **Issue markers (debug/QA builds):**
  - "Mark Issue" button writes a timeline marker for later review.

- **PII-safe by design:**
  - No notification titles or message content
  - Only package names, timestamps, counters, and reason codes

These tools are **strictly disabled in release builds** and have zero performance impact unless explicitly used.

</details>

**To help with logs:**
> Open **Action diagnostics** → reproduce the issue →  
> tap **Copy diagnostics summary** → send it.

---

## 🎵 Media Music Island Mode

HyperIsle lets you control how Music Island behaves on Xiaomi / HyperOS devices.

By default, the system’s native (HyperOS) music island is used, and HyperIsle does not create duplicates.

<details>
<summary><strong>🔍 Full Modes</strong></summary>

### Modes

#### 🟢 SYSTEM_ONLY (Default – Recommended)
* Uses the native HyperOS music island.
* HyperIsle does not generate any music island.
* Most stable option.
* Lock screen and notification media controls remain intact.

#### 🔴 BLOCK_SYSTEM (Advanced)
* Suppresses the native HyperOS music island for selected music apps.
* Implemented by cancelling the app’s MediaStyle notification.

⚠️ **Important**
* Lock screen and notification shade media controls may disappear.
* Selecting at least one music app is mandatory.
* Misconfiguration may negatively affect the lock screen music experience.

👉 If you are unsure, keep **SYSTEM_ONLY** enabled.

</details>

---

## 👀 For whom is HyperIsle?

* Xiaomi / HyperOS users who like the Dynamic Island concept.
* Power users who want fine-grained control over notifications.
* Developers and tinkerers who appreciate transparency and debug tooling.
* Users who want fewer interruptions without missing important notifications.


---

## 🌐 Supported Languages

* 🇹🇷 **Turkish** (Default)
* 🇺🇸 **English**

---

## 🛠️ Tech Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose (Material 3 Expressive)
* **Services:** NotificationListenerService, BroadcastReceiver
* **Concurrency:** Kotlin Coroutines & Flow

---

## 📥 Installation

### Build from Source
1. Clone this repository.
2. Open in Android Studio (Ladybug or newer recommended).
3. Sync Gradle and run on a Xiaomi / POCO / Redmi device (API 35+).

### ⚙️ Required Setup
1. Grant **Notification Access** when prompted.
2. Follow the in-app guide to enable:
   * **Autostart**
   * **No Restrictions** (Battery)
   
   This prevents the system from killing the background service.

3. Optional: If you enable the iOS-style pill overlay, grant "Display over other apps".

---

## 📜 Open Source & License (Apache 2.0)

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

## 🙏 Attribution

This project is based on open-source work by **D4vidDf**, licensed under Apache 2.0:

* **HyperBridge** – Original application foundation  
* **HyperIsland ToolKit** – HyperOS Dynamic Island toolkit  
  (mirrored at `coni-eng/My-HyperIsland-ToolKit`)

---

## 👤 Maintainer

**coni-eng**  
GitHub: [@coni-eng](https://github.com/coni-eng)
