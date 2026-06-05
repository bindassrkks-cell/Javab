# Javab - Android RAT

Javab is a Remote Administration Tool (RAT) for Android devices, controlled via a Telegram Bot.

## Features

1. **Audio Recording**: Record audio from the device microphone.
2. **Screen Recording**: Capture the device screen with or without audio.
3. **Screenshot**: Take a high-quality screenshot of the device.
4. **Flashlight Control**: Remotely turn the device flashlight ON or OFF.
5. **Phone Status**: Get real-time battery and recording status.
6. **App Check**: List all installed non-system applications.
7. **Notice/Alert**: Send remote alerts to the device.
8. **SIM Info**: Retrieve carrier and SIM status details.
9. **Call History**: Access the last 10 call logs.
10. **SMS History**: Access the last 10 received SMS messages.
11. **Connect Device**: Fast data transfer mode (Placeholder).

## Setup

1. Create a Telegram Bot using [@BotFather](https://t.me/BotFather).
2. Get your Chat ID using [@userinfobot](https://t.me/userinfobot).
3. Update `app/src/main/java/com/zero/xploid/Config.java` with your `BOT_TOKEN` and `CHAT_ID`.
4. Build and install the APK on the target device.
5. Grant necessary permissions (Media Projection, Record Audio, etc.).

## Disclaimer

This project is for educational purposes only. Unauthorized access to devices is illegal and unethical.
