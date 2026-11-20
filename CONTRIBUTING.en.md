# Contributing

[Русский](CONTRIBUTING.md) · **English**

Report bugs through GitHub Issues. Include the Fortem version, Android or iOS version,
steps to reproduce and expected behavior. For touch-related problems, a finger
diagram or short screen recording is especially useful. For pitch problems,
include the tuning, tonic (*the root note*), reference frequency and specific notes.

Support interface suggestions with a concrete example of the desired behavior.
Changes to the musical model should include the tuning definition,
a source and frequency checks. New Scala tunings should have a documented
set of intervals.

## Version numbers

Use `MAJOR.MINOR.PATCH`: fixes increment PATCH, compatible new features increment
MINOR, and incompatible changes increment MAJOR. The baseline is `1.0.0`.
Android `versionCode` and the iOS build number increase independently of the
public version and are not reset when MAJOR changes.

Versions are defined in `app/build.gradle.kts` for Android and in the Xcode
project settings for iOS. Release tags use `v<version>`. Documentation-only
changes do not require a new app version.

## Translations

English strings are in `app/src/main/res/values/strings.xml`; Russian strings are in
`values-ru/strings.xml`. Keep the resource keys and format argument types consistent,
and align labels with both user guides. Check language selection, long labels and
enlarged system fonts. Custom tuning names are not translated.

```bash
# Check complete translations, format arguments and resource references without an Android SDK.
python3 tests/check_localization.py
```

## Checking changes

```bash
# Check app logic and the audio engine's dependencies in the built APK.
./gradlew :app:testDebugUnitTest :app:verifyDebugNativeLibraries
```

Test touch input, sustained notes <img src="docs/assets/ui/lock.svg" width="22" height="22" alt="Latched note">, the pedal <img src="docs/assets/ui/pedal.svg" width="22" height="22" alt="Pedal">,
recording <img src="docs/assets/ui/record.svg" width="22" height="22" alt="Record">, stop <img src="docs/assets/ui/stop.svg" width="22" height="22" alt="Stop">, re-recording <img src="docs/assets/ui/rerecord.svg" width="22" height="22" alt="Re-record"> and playback <img src="docs/assets/ui/play.svg" width="22" height="22" alt="Play"> on a device.
[Control descriptions](docs/USER_GUIDE.en.md#interface-controls).
Changes to the mathematical model must preserve playback of older recordings.
Check interface changes with both normal and enlarged system fonts.

Code is licensed under GPL-3.0-only. Third-party assets retain their own licenses.

Native checks on Linux:

```bash
# Build tests for WAV loading and audio processing without an Android device.
g++ -std=c++20 -O2 -Wall -Wextra -Itests/native_stubs tests/native_audio_test.cpp app/src/main/cpp/piano_sample_bank.cpp -o /tmp/fortem-native-test
# Run the checks against the application's assets.
/tmp/fortem-native-test app/src/main/assets
```
