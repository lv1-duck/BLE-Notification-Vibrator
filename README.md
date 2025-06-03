# [Project Title TBD] – Android Notification Vibration Controller

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

This Android app connects to your ESP32 device via Bluetooth Low Energy (BLE) and triggers a vibration when you receive specific notifications.

⚠️ **Note:** This project is a work in progress. Many features are still under development, and some may break. Contributions, suggestions, or testing feedback are welcome!

---

## Features (So Far)
- BLE connection to ESP32 devices with BLE support (only ESP devices that match the hardcoded credentials in the main activity Kotlin file)
- Auto-scanning and pairing based on predefined device name
- Notification listener service *(currently limited to Facebook, Messenger, and WhatsApp)*
- Manual vibration trigger button for testing
- Connection status monitoring
- Basic automatic reconnection support
- Android 8.0+ (API 26+) compatibility

---

## Hardware Requirements
- ESP32 development board (with BLE)
- Vibration motor module
- Suitable power source for ESP32

---

## Setup Instructions

### ESP32 Setup
A companion ESP32 firmware repository is **not yet published**. Though a setup using ESP32 with BLE would work when the sketch accomplishes the following: 
1. The Bluetooth credentials provided match the hardcoded credentials in the main activity Kotlin file.
2. The ESP device is programmed to receive the UTF-8 string "vibrate", which is the command to vibrate.

### Android App Installation
1. Download the latest APK from [Releases](https://github.com/lv1-duck/BLE-Notification-Vibrator/tree/main/releases)
2. Enable **"Install from unknown sources"** in Android settings
3. Go to Google Play Store > Tap the User Icon > Go to Play Protect> Click the settings > Pause app scanning with Play Protect. (hindi ako hecker promise hehehe)
4. Install the APK
5. Grant required permissions:
   - Bluetooth
   - Location (required for BLE scanning)
   - Notification access

---

## Usage
Note: **The app will not prompt you to enable notification access at this time (this feature will be implemented later), so you will need to manually enable it in the settings.**
1. Launch the app
2. Make sure Bluetooth is turned on
3. Power up your ESP32 device
4. The app will:
   - Scan for nearby devices
   - Connect to your ESP32
   - Display the connection status
5. Press **"Send Command"** to test
6. When a Facebook, Messenger, or WhatsApp notification arrives, your ESP32 will vibrate

---  

