# Building an Android APK

[Русский](RELEASE.md) · **English**

Fortem **1.0.3** · Android `versionCode 78`.

## Project and dependencies

The Android project root contains `settings.gradle.kts` and the Gradle Wrapper.
The app version and `versionCode` are defined in `app/build.gradle.kts`.
Android SDK, NDK and CMake versions are pinned there; library versions are in
`gradle/libs.versions.toml`.

Open the project in Android Studio with **Open**. After Gradle synchronization,
the IDE offers to install missing SDK and NDK components.
Command-line builds use JDK 17 and do not require Android Studio.

```bash
# Run unit tests from the project root.
./gradlew :app:testDebugUnitTest
# Build a debug APK and verify its native library set.
./gradlew :app:verifyDebugNativeLibraries
```

Use the same tasks with `gradlew.bat` on Windows.
The debug APK is written to `app/build/outputs/apk/debug/`.

## Signed APK

1. In Android Studio, select **Build → Generate Signed Bundle / APK → APK**.
2. Select the **app** module.
3. Updates use the existing signing key: **Choose existing**, the keystore path,
   key name (**Alias**) and passwords. For the first release, **Create new** creates
   a key; store it outside the source directory and keep a backup.
4. Select **release**, choose an output directory, then **Create / Finish**.
5. **Locate** in the completion notification opens the APK's directory.

The keystore and key passwords may be identical. Each **Confirm** field repeats
its corresponding password. Private keys and passwords are not source or release assets.
An Android update requires a compatible signing identity; debug and release keys
normally differ.

## Publishing

Release tags use `v<versionName>` and APK names use `Fortem-<versionName>.apk`.
APKs belong in GitHub Releases, not in the source tree. Release notes describe
behavior changes, system requirements and available packages.

Release checks cover launch, both sounds, touch input and map inertia, pedal,
optional latching, recording and re-recording, playback, WAV export,
recording deletion and restoration, and both interface languages.
Update checks cover preservation of personal recordings and saved settings.

[Interface controls](USER_GUIDE.en.md#interface-controls) ·
[Changelog](CHANGELOG.en.md)

## Official documentation

- [Building an app](https://developer.android.com/build/build-for-release)
- [APK signing](https://developer.android.com/studio/publish/app-signing)
