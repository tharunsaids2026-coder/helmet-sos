# Helmet SOS (Android)

Background Android app for the Smart Helmet. It keeps a Bluetooth LE link to the ESP32
("SmartHelmet", see helmet_ble.ino) and, only when the helmet sends "CRASH", posts the
phone's location to a Telegram chat.

## Get the APK (no Android Studio needed)
1. Create a GitHub repository and upload everything in this folder (keep the .github folder).
2. Open the repository's Actions tab, run "Build APK" (it also runs on every push).
3. When it finishes, open the run and download the artifact "HelmetSOS-apk". Unzip it.
4. Copy app-debug.apk to your Android phone, open it, and allow "Install unknown apps".

## First run
- Enter the Telegram bot token and family chat ID, then tap "Start protection".
- Allow Location, Nearby devices (Bluetooth) and Notifications.
- Tap "Allow background running" and set the app to Unrestricted.
- Use "Send test alert" to check Telegram and location without the helmet.
