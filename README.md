# [Project Title TBD] – Android Notification Vibration Controller

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

This Android app connects to your ESP32 device via Bluetooth Low Energy (BLE) and triggers a vibration when you receive specific notifications.

⚠️ **Note:** This project is a work in progress. Many features are still under development, and some may break. Contributions, suggestions, or testing feedback are welcome! Also, I've vibecoded like 99% of the project since I'm completely new to Kotlin.

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
A companion ESP32 firmware repository is **not yet published**.

### Android App Installation
1. Download the latest APK from [Releases](https://github.com/lv1-duck/BLE-Notification-Vibrator/tree/main/releases)
2. Enable **"Install from unknown sources"** in Android settings
3. Install the APK
4. Grant required permissions:
   - Bluetooth
   - Location (required for BLE scanning)
   - Notification access

---

## Usage
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

