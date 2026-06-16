# 🪄 Air Mouse – Magic Wand for Your PC

Turn your Android phone into a 3D air mouse. Simply move your phone in the air to control your computer's cursor — no touchpad needed. Built on the **serverless Bluetooth HID** technology, your PC sees the phone as a standard Bluetooth mouse, so no server software is required.

## ✨ Features

- **Gyroscope-based air pointer** – tilt your phone to move the cursor seamlessly.
- **Tap zones for left/right clicks** – intuitive on‑screen buttons.
- **One‑tap calibration** – freeze the pointer to rest your hand or reset drift.
- **Foreground service** – keeps the connection alive even when the screen is off.
- **Works without root** – uses Android’s `BluetoothHidDevice` API (Android 9.0+).
- **100% serverless** – PC sees a plain Bluetooth mouse, no companion app needed.
- **Ultra‑low latency** – HID reports sent every 10 ms via Bluetooth.

## 📱 Requirements

- **Android device** with:
    - Android 9.0 (Pie) or later
    - Gyroscope sensor
    - Bluetooth 5.0 or newer
- **PC with Bluetooth** – Windows, macOS, Linux, or ChromeOS

## 🚀 How to Use

1. **Pair your phone with your PC** via Bluetooth (standard pairing).
2. **Launch the Air Mouse app** on your phone.
3. Grant Bluetooth permissions when prompted.
4. The app registers as a Bluetooth mouse. On your PC, you may see “Air Mouse” or a generic mouse appear in Bluetooth devices.
5. Hold your phone in **landscape mode** (USB port to the right).
6. **Tilt the phone** – up/down moves the cursor vertically, left/right moves it horizontally.
7. **Tap the top half** of the screen for a left click, **bottom half** for a right click.
8. Press the **“Calibrate” button** to lock the cursor when you stop moving (the current phone orientation becomes the new zero point).

## 🛠️ Build from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/AirMouse.git