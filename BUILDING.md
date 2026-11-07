# Building Noodoe Offline

This guide builds the app from source on **Windows 10/11** and **Linux**.
The project uses the Gradle wrapper, so you do not need to install Gradle
separately.

## Requirements (both systems)

| Tool | Version |
|---|---|
| JDK | **17** (AGP 8.7 requires JDK 17) |
| Android SDK platform | **35** |
| Android SDK build-tools | **35.0.0** |
| Android platform-tools | current (provides `adb`) |
| Git | any recent version |

The first build downloads Gradle 8.9 and the project dependencies, so an
internet connection is required once.

`local.properties` is intentionally ignored by Git. Create it in the project
root and point it at your Android SDK:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

Android Studio creates this file automatically when you open the project.

---

## Windows 10 / 11

### Option A — Android Studio (recommended)

1. Install [Android Studio](https://developer.android.com/studio) with the
   default options. It includes a compatible JDK.
2. Open **More Actions → SDK Manager** (or **Tools → SDK Manager**) and
   install:
   - **Android 15.0 (API 35)** under *SDK Platforms*
   - **Android SDK Build-Tools 35** under *SDK Tools*
   - **Android SDK Platform-Tools**
3. Clone the repository:
   ```bat
   git clone https://github.com/mgke90/noodoe-offline.git
   cd noodoe-offline
   ```
4. Open the `noodoe-offline` folder in Android Studio and wait for the Gradle
   sync to finish.
5. Build the APK:
   - **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or
   - open the terminal in Android Studio and run:
     ```bat
     gradlew.bat assembleDebug
     ```
6. The APK is at:
   ```
   app\build\outputs\apk\debug\app-debug.apk
   ```

### Option B — command line only

1. Install **Eclipse Temurin JDK 17**:
   ```bat
   winget install EclipseAdoptium.Temurin.17.JDK
   ```
   Or download it from <https://adoptium.net/temurin/releases/?version=17>.
   Verify:
   ```bat
   java -version
   ```
   The output must say `17`.
2. Set `JAVA_HOME` to the JDK folder, for example:
   ```bat
   setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
   ```
   Open a new terminal after `setx`. Android Studio's bundled JDK also works:
   `C:\Program Files\Android\Android Studio\jbr`.
3. Install the Android command-line tools:
   - Download **Command line tools only** for Windows from
     <https://developer.android.com/studio#command-tools>.
   - Extract the ZIP. It contains a `cmdline-tools` folder.
   - Move it to:
     ```
     %LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest
     ```
     The resulting path must be
     `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat`.
4. Install the SDK packages:
   ```bat
   sdkmanager.bat --licenses
   sdkmanager.bat "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   ```
   Accept every license prompt.
5. Set the SDK environment:
   ```bat
   setx ANDROID_HOME "%LOCALAPPDATA%\Android\Sdk"
   setx PATH "%PATH%;%LOCALAPPDATA%\Android\Sdk\platform-tools"
   ```
   Open a new terminal and verify `adb version`.
6. Clone and configure:
   ```bat
   git clone https://github.com/mgke90/noodoe-offline.git
   cd noodoe-offline
   echo sdk.dir=C:/Users/%USERNAME%/AppData/Local/Android/Sdk> local.properties
   ```
   Forward slashes are accepted and avoid escaping problems. If your SDK is
   elsewhere, use that path instead.
7. Build and install:
   ```bat
   gradlew.bat assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

### Windows troubleshooting

- **`JAVA_HOME` is wrong / “Unsupported class file major version”**: the build
  is using a JRE or the wrong JDK. Point `JAVA_HOME` at a JDK 17 folder and
  open a new terminal.
- **`gradlew` is not recognized**: use `gradlew.bat`, not `gradlew`.
- **PowerShell execution policy**: `.bat` files are not affected. If you use
  `.\gradlew.bat`, keep the leading `.\`.
- **Path too long / weird Gradle errors**: clone to a short path such as
  `C:\src\noodoe-offline` and enable Windows long-path support.
- **SDK not found**: check that `local.properties` contains the correct
  `sdk.dir` and that `cmdline-tools\latest\bin` is on `PATH`.

---

## Linux

The commands below are for Debian/Ubuntu. Package names for other
distributions are listed afterwards.

### 1. Install JDK 17 and basic tools

```sh
sudo apt update
sudo apt install -y openjdk-17-jdk unzip curl git
java -version
```

The output must say `17`.

Fedora:

```sh
sudo dnf install -y java-17-openjdk-devel unzip curl git
```

Arch:

```sh
sudo pacman -S jdk17-openjdk unzip curl git
```

### 2. Install the Android SDK

1. Download **Command line tools only** for Linux from
   <https://developer.android.com/studio#command-tools>.
2. Install it under your home directory:

   ```sh
   mkdir -p "$HOME/Android/Sdk/cmdline-tools"
   cd "$HOME/Android/Sdk/cmdline-tools"
   unzip ~/Downloads/commandlinetools-linux-*.zip
   mv cmdline-tools latest
   ```

   The resulting path must be
   `$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager`.

3. Add the SDK tools to your shell:

   ```sh
   cat >> ~/.bashrc <<'EOF'
   export ANDROID_HOME="$HOME/Android/Sdk"
   export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
   EOF
   source ~/.bashrc
   ```

4. Accept licenses and install the packages:

   ```sh
   sdkmanager --licenses
   sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   ```

### 3. Build

```sh
git clone https://github.com/mgke90/noodoe-offline.git
cd noodoe-offline
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug testDebugUnitTest
```

The APK is at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Linux troubleshooting

- **`adb: no permissions`**: install the Android udev rules (for example the
  `android-sdk-platform-tools-common` package on Debian/Ubuntu), add your user
  to the `plugdev` group, and replug the phone:
  ```sh
  sudo usermod -aG plugdev "$USER"
  ```
- **`SDK location not found`**: `local.properties` is missing or `sdk.dir` is
  wrong.
- **`Unsupported class file major version`**: `java -version` is not 17, or
  `JAVA_HOME` points at another JDK.
- **No display / headless machine**: the build itself is headless. Only `adb`
  device authorization and the phone UI need a screen.

---

## Build variants and tests

| Command | Result |
|---|---|
| `./gradlew assembleDebug` | installable debug APK |
| `./gradlew testDebugUnitTest` | JVM/Robolectric unit tests |
| `./gradlew assembleRelease` | unsigned release APK |
| `./gradlew clean assembleDebug` | clean rebuild |

On Windows replace `./gradlew` with `gradlew.bat`.

### Release APK signing

`assembleRelease` produces an **unsigned** APK. Sign it with your own keystore.
Never commit the keystore or its passwords.

Create a keystore once:

```sh
keytool -genkeypair -v \
  -keystore my-release.keystore \
  -alias noodoe \
  -keyalg RSA -keysize 4096 -validity 10000
```

Build, align, sign, and verify:

```sh
./gradlew assembleRelease

"$ANDROID_HOME/build-tools/35.0.0/zipalign" -f -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app-release-aligned.apk

"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
  --ks my-release.keystore \
  --ks-key-alias noodoe \
  --out NoodoeOffline-0.6.0-release.apk \
  app-release-aligned.apk

"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  NoodoeOffline-0.6.0-release.apk
```

On Windows use `%ANDROID_HOME%\build-tools\35.0.0\zipalign.exe` and
`apksigner.bat`.

Back up `my-release.keystore`, the alias, and the passwords. Losing them means
you cannot publish updates that install over the existing app.

---

## Common questions

**Do I need Android Studio?**
No. The command-line path is fully supported. Android Studio is simply easier
for first-time SDK setup and debugging.

**Can I build without an internet connection?**
Only after the first successful build has populated the Gradle cache.

**Why JDK 17 and not 21?**
The Android Gradle Plugin 8.7 is tested with JDK 17. Newer JDKs often work but
are not guaranteed.

**Where is `local.properties`?**
In the project root, next to `settings.gradle.kts`. It is ignored by Git and
must exist on every machine.

**Do I need a scooter to build or run the tests?**
No. The unit tests use captured frames and Robolectric.
