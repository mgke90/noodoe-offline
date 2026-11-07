# Noodoe Offline

An unofficial, offline-only Android companion for **KYMCO Noodoe / Sunray**
dashboards. No account, no cloud, no OTA, no social features, and no server
dependency.

[Download the latest APK](../../releases/latest) ·
[Build from source](BUILDING.md) ·
[Changelog](CHANGELOG.md)

> **Unofficial project.** Not affiliated with, endorsed by, or supported by
> KYMCO or Noodoe. Use at your own risk. See [NOTICE.md](NOTICE.md) for the
> bundled official assets and trademark information.

Package: `com.noodoe.offline` · version 0.6.0 · Android 6.0+ (minSdk 23)

## What it does

- Classic Bluetooth discovery, system pairing/bonding, and SPP connection to
  `KYMCO Noodoe…` / `Sunray…` dashboards.
- Handshake and device-info parsing (firmware, protocol, hardware, MAC).
- Optional clock sync on connect, plus **SYNC NOW** and **SET CUSTOM**. A
  custom clock survives force-stop/reopen.
- Dashboard preferences: 12/24-hour clock, km/miles, °C/°F, brightness, and
  dashboard language. Preference changes never overwrite the clock.
- Riding status: speed, ODO, max speed, and dash key-on.
- Dashboard notifications for apps, incoming/missed calls, and SMS.
- Optional foreground keepalive service for background delivery and reconnect.
- **Dashboard Studio** (0.6.0): local clock and speedometer creation using the
  official SR1.5 Pack B assets, a live 480×480 preview, and installation over
  Bluetooth with the stock `0x0A`/`0x0B`/`0x0D` transfer sequence.
- 0.6.0 in-flight transfer engine: up to ten active files, asynchronous
  start/finish handling, and the official continue/done commands.
- Native **64×90** stock speedometer digits instead of upscaled low-resolution
  glyphs.
- Safe diagnostic logs with new/share/share-all/clear.
- No storage permission; logs stay in the app's own external folder.

## Requirements

- A KYMCO Noodoe / Sunray dashboard. Compatibility varies by model, firmware,
  and resource pack.
- An Android 6.0+ phone with Classic Bluetooth (SPP).
- The official Noodoe app must not be connected to the same dashboard at the
  same time.
- For notifications: notification access, phone/SMS permissions, and the
  optional foreground keepalive service.

## Download and install

1. Download the APK from [Releases](../../releases/latest).
2. Install it on the phone:
   ```sh
   adb install -r NoodoeOffline-0.6.0-release.apk
   ```
   or open the APK on the phone and allow installation from that source.
3. If you previously installed a debug build, uninstall it once. Release and
   debug builds use different signing keys, so Android will not update across
   them.
4. Turn the scooter on. Within about 30 seconds, hold the orange Toggle button
   for 3–5 seconds to put the dashboard in pairing mode.
5. Open the app, grant Nearby devices + Location, tap **SCAN & PAIR**, and
   confirm the system pairing prompt.
6. The app connects, applies the saved dashboard preferences, and reads status.
   Clock sync on connect is off by default; enable it if you want it.
7. Open **DASHBOARD STUDIO → CLOCK** and install **MINIMAL STOCK CLOCK** first.
   This 2-file install checks the transfer path before custom creations are
   unlocked. Then install the full 9-file stock clock, followed by the
   speedometer.

The most common connection problem is another phone still bonded to the
dashboard, or the official Noodoe app running in the background. Forget the
bond on the other phone and force-stop the official app first.

## Build from source

Full step-by-step instructions for **Windows 10/11** and **Linux** are in
[BUILDING.md](BUILDING.md). Quick version:

```sh
git clone https://github.com/mgke90/noodoe-offline.git
cd noodoe-offline
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

On Windows use `gradlew.bat`:

```bat
gradlew.bat assembleDebug
```

The debug APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

Requirements: **JDK 17**, **Android SDK platform 35**, and **build-tools
35.0.0**. The Gradle wrapper downloads Gradle 8.9 automatically. Run the unit
tests with `./gradlew testDebugUnitTest` or `gradlew.bat testDebugUnitTest`.

## Status and limitations

- 0.6.0 is the stable release. It includes pairing, status, clock,
  notifications, preferences, and the stock clock and speedometer
  installations.
- Dashboard support varies by model, firmware, and installed resource pack.
  Some units report key status incorrectly or use fixed unit faces.
- This app does not update firmware and is not a replacement for the official
  app for OTA or account features.
- Do not use the app while riding. Set clocks and settings while stationary.

## Architecture

```
NoodoeApp                 application-scoped controller
ui/MainActivity           permissions, discovery, bonding, UI
ui/MainViewModel          UI state + settings
data/NoodoeController     session, commands, notifications, keepalive
bluetooth/NoodoeSession   RFCOMM session, handshake, sequence/ACK
protocol/SunrayProtocol   frame builders/parsers, device info, status,
                          call/SMS/app-notification commands
data/LogStore             app-specific log files
data/SettingsStore        SharedPreferences
notification/NoodoeService           foreground keepalive service
notification/NoodoeNotificationListener  app notifications
notification/NoodoeSmsReceiver       incoming SMS
notification/PhoneStateWatcher       incoming/missed calls
creation/CreationAssets              official Pack B/APK assets + tint/compose
creation/ClockCreationBuilder        clock cfg rewrite + stock template
creation/SpeedometerCreationBuilder  analog/digital speedometer bundle
creation/DashboardPreviewView        live 480×480 clock/speedometer preview
```

The protocol layer is unit-tested with fixture frames. The tests run entirely
on the JVM/Robolectric.

## Logs

No storage permission is requested. Logs are written only to:

```
/sdcard/Android/data/com.noodoe.offline/files/logs/noodoe-YYYYMMDD-HHMMSS-SSS.txt
```

Use **SHARE** for the current log or **SHARE ALL LOGS (ZIP)** for everything.
`adb pull` the folder if you prefer.

## License and assets

The source code is released under the [MIT License](LICENSE).

The app bundles image assets extracted from the official KYMCO Noodoe / Sunray
SR1.5 resource pack and official app presets. Those assets remain the property
of their respective owners and are **not** covered by the MIT license. They are
included so that the Dashboard Studio can build dashboard-compatible clock and
speedometer creations. See [NOTICE.md](NOTICE.md).

If you are a rights holder and want an asset removed, please open an issue.
