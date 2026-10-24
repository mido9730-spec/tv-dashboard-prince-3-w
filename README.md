# TV Dashboard (Android TV / Google TV)

A dashboard for your smart TV: clock, date and a grid of your apps, controlled from your phone's browser.

## How it works
- The TV app shows the dashboard and runs a small web server on port 8080.
- It shows an address like `http://192.168.1.20:8080` and a 4-digit PIN at the bottom of the screen.
- Open that address in your phone's browser (same Wi-Fi), enter the PIN, and you get:
  - a D-pad + OK to move around the dashboard,
  - a searchable list of every app on the TV with an **Open** button,
  - a **Show** checkbox per app to choose what appears on the dashboard.

## Build the APK (pick one)
1. **Android Studio**: open this folder, then Build > Build APK(s).
2. **Command line** (JDK 17 + Android SDK): `./gradlew assembleDebug`
   Output: `app/build/outputs/apk/debug/app-debug.apk`
3. **No setup**: push this folder to a GitHub repo. The included workflow builds the APK; download it from the Actions tab > Artifacts.

## Install on the TV
- Easiest: put the APK on a USB stick, open it with a file manager app on the TV (e.g. "File Commander"), or use "Send Files to TV".
- Or with ADB: enable Developer options > USB/Network debugging on the TV, then
  `adb connect <tv-ip>` and `adb install app-debug.apk`.
- Open "TV Dashboard" from the app row.

## Limits to know
- Phone control works while the dashboard is on screen. Android blocks apps from starting other apps while a different app is in the foreground, so after you open Netflix, use the TV's own remote to go Home and come back.
- The D-pad on your phone moves the dashboard only, not other apps.
- Setting it as the default home screen works on many Android TV boxes, but Google TV often forces its own launcher.
- The PIN stops other people on your network from controlling the TV.
