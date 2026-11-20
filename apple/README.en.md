# Fortem for iPhone and iPad

[Русский](README.md) · **English**

**Version 1.0.3 · build 19.**

Native Fortem for iOS/iPadOS 16 or later.
Xcode project: `apple/Fortem.xcodeproj`; shared scheme: `Fortem`.
[Android](https://github.com/v-e-c-s-i/Fortem-app) · [Releases](https://github.com/v-e-c-s-i/Fortem-app/releases)

## Features

- Landscape 88-key piano, multi-touch, dark interface and an inertial keyboard range map.
- Salamander Grand Piano and pure tone; six built-in tunings, tonic, A4 reference and Scala.
- Toolbar sizing fits the safe area of the current window. In a narrow window
  the map spans the bottom, and the current note takes its place in the toolbar.
- Settings slide in from the right and take up about 45% of the window width. Settings text follows
  the system text size; the in-app slider adjusts the playing controls.
- Pedal and optional upward-swipe note latching. Clearing manual latches preserves pedal-held notes.
- Recording, playback, replacement takes, rename and draft recovery.
- Full-screen recordings: play, re-record and trash stay visible; rename and WAV
  actions are in the More menu. Long titles scroll within their row. Playing rows
  show time and progress without moving the keyboard viewport.
- 30-day Trash with remaining retention time, restoration and deletion undo.
- Core MIDI output: USB, compatible apps, configured network sessions and the system Bluetooth MIDI screen.
- Russian and English interfaces; an interface-symbol reference is available in settings.

Fresh 1.0.3 installs use piano, equal temperament and tonic C, with gesture
latching disabled and empty recordings and Trash.

## WAV and MIDI

WAV exports use stereo 16-bit audio at 48 kHz. Local copies appear under
**Files → On My iPhone/iPad → Fortem → WAV**.
**Save WAV to Files** opens the folder picker; **Share WAV** opens the system share sheet.

MIDI uses equal temperament at A4 = 440 Hz. Device sound is optional. Saved
performances can play through MIDI; recording new performances is disabled while
MIDI is connected. Disconnecting restores the saved tuning.

## Source and testing

The Xcode project references `app/src/main/assets/piano` and the shared licenses.
Builds require the complete source directory, including `app/`.
The synthesis engine and limiter use C++; the interface uses SwiftUI and UIKit.

Automated tests cover tunings, touch ownership, pedal behavior, recording storage,
draft recovery, WAV export and core interface controls. Virtual MIDI-port tests
are skipped where iOS prohibits creating the receiver. Physical MIDI connections
and audio latency depend on the device and equipment. IPAs are signed locally before installation;
source files contain no signing keys.

## Privacy and licenses

No ads, analytics, microphone access or account login. Recordings and WAV copies
stay on the device; cloud export uses the selected system provider. Backups follow
iOS settings. Audio stops on focus loss or audio-session interruption. Exit uses
the system Home gesture.

Code: [GPL-3.0-only](../LICENSE). Piano recordings by Alexander Holm and Accurate
Salamander processing by C. Yamauchi: [CC BY 3.0](../app/src/main/assets/licenses/SALAMANDER_GRAND_PIANO.txt).
