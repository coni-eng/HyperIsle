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

<p align="center">
  <img src="https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Material%20Design-757575?style=for-the-badge&logo=material-design&logoColor=white" alt="Material Design" />
</p>

<br>

## 🚀 Features

* **Native Visuals:** Transforms notifications into HyperOS system-style islands.
* **Smart Integration:**
    * **🎵 Media:** Show album art and "Now Playing" status with visualizer support.
    * **🧭 Navigation:** Real-time turn-by-turn instructions (Google Maps, Waze). Customize the split layout (Distance, ETA, or Instruction).
    * **⬇️ Downloads:** Circular progress ring with a satisfying "Green Tick" animation upon completion.
    * **📞 Calls:** Dedicated layout for incoming and active calls with timers.
* **🛡️ Spoiler Protection:** Define blocked terms globally or per-app to prevent specific notifications (e.g., message spoilers) from popping up on the Island.
* **👻 Ghost Mode:** Option to hide the persistent service notification from the system shade while keeping the Island fully active.
* **Total Control:** Choose exactly which apps trigger the island, customize timeouts, and toggle floating behavior per app.

## 🌐 Supported Languages

* 🇹🇷 **Turkish** (Default)
* 🇺🇸 **English**

## 🛠️ Tech Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose (Material 3 Expressive)
* **Architecture:** MVVM
* **Storage:** Room Database (SQLite)
* **Services:** NotificationListenerService, BroadcastReceiver
* **Concurrency:** Kotlin Coroutines & Flow

## 📸 Screenshots

| Home Screen | Settings | Active Island |
|:---:|:---:|:---:|
| ![Home](./screenshots/home.png) | ![Settings](./screenshots/settings.png) | ![Island](./screenshots/island_example.png) |

## 📥 Installation

### Build from Source
1. Clone this repository.
2. Open in Android Studio (Ladybug or newer recommended).
3. Sync Gradle and run on a Xiaomi/POCO/Redmi device (API 35+).

### ⚙️ Setup (Required)
1. Grant **"Notification Access"** when prompted.
2. **Critical:** Follow the in-app guide to enable **Autostart** and **No Restrictions** (Battery) to prevent the system from killing the background service.

## 🤝 Contributing

Contributions are welcome! Please read our [Contributing Guidelines](CONTRIBUTING.md) before submitting a Pull Request.

1. **Fork** the repository.
2. Create a new branch (`git checkout -b feature/AmazingFeature`).
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`).
4. Push to the branch (`git push origin feature/AmazingFeature`).
5. Open a **Pull Request**.

## 📜 License

Distributed under the **Apache 2.0 License**. See `LICENSE` for more information.

## 🙏 Attribution

This project is based on open-source work by D4vidDf, licensed under Apache 2.0:

- **[HyperBridge](https://github.com/D4vidDf/HyperBridge)** - The original application this fork is based on.
- **[HyperIsland-ToolKit](https://github.com/D4vidDf/HyperIsland-ToolKit)** - The toolkit library for HyperOS Dynamic Island notifications (mirrored at [coni-eng/My-HyperIsland-ToolKit](https://github.com/coni-eng/My-HyperIsland-ToolKit)).

We are grateful for D4vidDf's contributions to the open-source community.

## 👤 Maintainer

**coni-eng**
* GitHub: [@coni-eng](https://github.com/coni-eng)
