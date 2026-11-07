# Releasing

This is the maintainer checklist for publishing an APK. The keystore and its
passwords are **never** committed.

## 1. Bump the version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = 29
versionName = "0.7.0"
```

`versionCode` must increase for every published APK.

## 2. Build and test

```sh
./gradlew clean testDebugUnitTest assembleRelease
```

On Windows use `gradlew.bat`.

## 3. Sign the release APK

```sh
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -f -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app-release-aligned.apk

"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign \
  --ks /secure/location/my-release.keystore \
  --ks-key-alias noodoe \
  --out NoodoeOffline-<version>-release.apk \
  app-release-aligned.apk

"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  NoodoeOffline-<version>-release.apk
```

If the keystore or password file is lost, future releases cannot update the
installed app. Back both up offline.

## 4. Record the checksum

```sh
sha256sum NoodoeOffline-<version>-release.apk > SHA256SUMS.txt
cat SHA256SUMS.txt
```

## 5. Tag and release

```sh
git tag -a v<version> -m "Noodoe Offline <version>"
git push origin v<version>

gh release create v<version> \
  --title "Noodoe Offline <version>" \
  --notes-file release-notes/<version>.md \
  NoodoeOffline-<version>-release.apk \
  SHA256SUMS.txt
```

Check the release page and download the asset once to confirm the hash:

```sh
sha256sum -c SHA256SUMS.txt
```

## 6. Verify the tag source

The GitHub-generated source ZIP/TAR for the tag must contain the same app code
as the APK. Documentation-only commits after the app code are acceptable; the
APK version and tag must match.

## Notes

- Do not commit APKs, keystores, `keystore.properties`, logs, or local SDK
  paths. The `.gitignore` covers them.
- Keep the previous release APKs available. A user who skipped a version may
  need to install an intermediate build if the dashboard format changes.
- Release notes should state whether the APK is debug- or release-signed and
  whether an uninstall is required when switching keys.
