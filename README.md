<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="150" alt="HyperIsle Logo" style="border-radius: 20%;" />
</p>

<h1 align="center">HyperIsle</h1>

<p align="center">
  <strong>Bring the native HyperIsland experience to third-party apps on HyperOS.</strong>
</p>

<p align="center">
  HyperIsle bridges standard Android notifications into the pill-shaped UI around the camera cutout, offering a seamless, iOS-like experience on Xiaomi phones.
</p>



<br>

## 🚀 Features

* **Native Visuals:** Transforms notifications into HyperOS system-style islands.
* **Smart Integration:**
    * **🎵 Media:** Show album art and "Now Playing" status with visualizer support.
    * **🧭 Navigation:** Real-time turn-by-turn instructions (Google Maps, Waze). Customize the split layout (Distance, ETA, or Instruction).
    * **⬇️ Downloads:** Circular progress ring with a satisfying "Green Tick" animation upon completion.
    * **📞 Calls:** Dedicated layout for incoming and active calls with timers.
    * **🔕 System Notifications:** DND, Silent mode is visible from island.
    * **✨ Smart Silence:** Prevents spammy repeats within a configurable time window.
    * **🎯 Focus Mode:** Quiet-hours rules can override floating/timeout behavior and restrict which types are shown.
    * **🧾 Notification Summary:** Optional daily digest notification (iOS-style summary).
    * **📳 Haptics:** Subtle “shown” haptic on island display + distinct “success” haptic on dismiss.
    * **🧩 Standard Actions:** Consistent island interactions:
      * **Tap:**  opens the source app (with safe fallback)
      * **Options:** opens Quick Actions (mute/block per app)
  	  * **Dismiss:**  closes the island and applies cooldown (prevents immediate re-appearing)
    *  **🧠 Smart UX:**
       * **🧠 Live Activity Engine:** Islands update smoothly instead of stacking.
       * **⚡ Smart Priority:** Spam detection and auto-throttling based on user behavior.
       * **📱 Context-Aware Islands:** Adapts behavior based on screen and charging state.
       * **🎨 Adaptive Visuals:** App-based accent colors and polished progress animations.

* **🛡️ Spoiler Protection:** Define blocked terms globally or per-app to prevent specific notifications (e.g., message spoilers) from popping up on the Island.
* **👻 Ghost Mode:** Option to hide the persistent service notification from the system shade while keeping the Island fully active.
* **Total Control:** Choose exactly which apps trigger the island, customize timeouts, and toggle floating behavior per app.
* **MUCH MORE TO COME**


## **🎵 Media Music Island Mode Update:**
* HyperIsle allows you to control how Music Island behaves on Xiaomi / HyperOS devices.
By default, the system’s native (HyperOS) music island is used, and the app does not create duplicate islands.

* **MODES**
* **🟢 SYSTEM_ONLY (Default)**

    * Uses the native HyperOS music island.

    * HyperIsle does not generate any music island.

    * Most stable and recommended mode.

    * Lock screen and notification media controls remain intact.

* **🔴 BLOCK_SYSTEM (Advanced)**

    * Suppresses the HyperOS native music island for selected music apps.

    * This is done by cancelling the app’s MediaStyle notification.

* **⚠️ IMPORTANT WARNING**
    * When this mode is enabled:
    * Lock screen and notification shade media controls (play / pause / skip) may disappear.
    * Selecting at least one music app is mandatory.
    * Misconfiguration may negatively affect the lock screen music experience.



## 🌐 Supported Languages

* 🇹🇷 **Turkish** (Default)
* 🇺🇸 **English**

## 🛠️ Tech Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose (Material 3 Expressive)
* **Services:** NotificationListenerService, BroadcastReceiver
* **Concurrency:** Kotlin Coroutines & Flow


## 📥 Installation

### Build from Source
1. Clone this repository.
2. Open in Android Studio (Ladybug or newer recommended).
3. Sync Gradle and run on a Xiaomi/POCO/Redmi device (API 35+).

### ⚙️ Setup (Required)
1. Grant **"Notification Access"** when prompted.
2. **Critical:** Follow the in-app guide to enable **Autostart** and **No Restrictions** (Battery) to prevent the system from killing the background service.




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

### Under the Apache 2.0 license, HyperIsle:

* Copyright 2023 D4vidDf

* Distributed under the Apache 2.0 License. See LICENSE for more information

* Properly attributes the original authors

* Complies with all license terms

* Clearly separates modified and original work

### This project is based on the above work but is developed and maintained independently.

## 🙏 Attribution

This project is based on open-source work by D4vidDf, licensed under Apache 2.0:

- **[HyperBridge](https://github.com/D4vidDf/HyperBridge)** - The original application this fork is based on.
- **[HyperIsland-ToolKit](https://github.com/D4vidDf/HyperIsland-ToolKit)** - The toolkit library for HyperOS Dynamic Island notifications (mirrored at [coni-eng/My-HyperIsland-ToolKit](https://github.com/coni-eng/My-HyperIsland-ToolKit)).

We are grateful for D4vidDf's contributions to the open-source community.

## 👤 Maintainer

**coni-eng**
* GitHub: [@coni-eng](https://github.com/coni-eng)
