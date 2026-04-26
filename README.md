# Homey for Android Automotive 🚗🏡

Bring your smart home to your car's dashboard! **Homey for Android Automotive** is a native Android Automotive OS (AAOS) application that allows you to monitor and control your Homey smart home ecosystem directly from your vehicle's infotainment system.

## 🌟 Key Features

- **Native AAOS Experience**: Built using the official Android for Cars App Library, ensuring a distraction-free, driver-optimized interface that complies with strict automotive safety guidelines.
- **Real-Time Control**: Toggle lights, open/close garage doors and locks, and monitor the real-time state of your devices.
- **Smart Filtering**: Choose exactly which devices to expose to the car through the companion app's settings, or automatically sync your Homey Favorite Devices.
- **Flow Integration**: Execute your manual Homey Flows with a simple tap from the driver's seat.
- **Secure Authentication**: Uses a seamless, secure OAuth2 flow. A QR code bridges the gap between your car screen and your smartphone—no typing passwords on the dashboard.
- **Multi-Hub Support**: Connect and manage multiple Homey hubs seamlessly.

## 🏗️ Architecture

This project consists of two tightly integrated components:

1. **Android Automotive App**: The client application running on the car's head unit. Built in Kotlin using the `androidx.car.app` IoT templates.
2. **Homey Companion App**: A lightweight app installed on your Homey Pro. It acts as an OAuth2 relay between the car and the Athom Cloud, while providing custom configuration views in the Homey app.

## 🚀 Installation & Setup

### 1. Install the Homey Companion App
1. Install the **Android Automotive** app from the Homey App Store (or manually via [Homey CLI](https://github.com/s-dimaio/com.dimapp.aaos)).
2. Open the Homey mobile app (or Web App), go to **More** > **Apps** > **Android Automotive** > **Configure App**.
3. Use the settings view to select up to 9 devices you want quickly accessible in the car.

### 2. Install the Android Automotive App
1. Download the latest `.apk` from the [Releases](https://github.com/s-dimaio/HomeyAutomotive/releases) page.
2. Install the APK on your Android Automotive OS vehicle or emulator via ADB or USB flash drive.

### 3. Connect the Car to your Homey
1. Open the app in your car.
2. Insert your HomeyPro ID (you can find it in the Homey mobile app, under Settings > General).
3. Then a QR code will be displayed on the screen.
4. Scan the QR code with your smartphone and follow the authorization steps on the Athom portal.
5. Once authorized, the car app will automatically refresh and display your smart home devices!

## 🛠️ Development

### Prerequisites
- Android Studio (latest stable release)
- Node.js & `homey` CLI (for the Companion app)
- Android Automotive OS Emulator (API 29+)

### Building the Android App
Open the `Android/HomeyAutomotive` folder in Android Studio. Sync the Gradle project and hit **Run** to deploy it to your AAOS emulator.

### Building the Homey App
Navigate to the `Homey/com.dimapp.aaos` directory and use the Homey CLI to run the app locally:
```bash
cd com.dimapp.aaos
npm install
homey app run
```

## 📄 Privacy Policy

We take your privacy seriously. **Homey for Android Automotive** is designed with data minimization in mind and does not collect, store, or transmit any personal identification data. For more information, please refer to our [Privacy Policy](https://s-dimaio.github.io/HomeyAutomotive/privacy.html).

## 📄 License
This project is licensed under the GNU GPL v3 License.
