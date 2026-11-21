[Русский](README.md) · **English**

**Android · iPhone · iPad · 1.0.3**

[Releases](https://github.com/v-e-c-s-i/Fortem/releases) · [User guide](docs/USER_GUIDE.en.md) · [iPhone and iPad](apple/README.en.md)

<div align="center">
  <img src="app/src/main/res/drawable-nodpi/fortem_logo.png" width="128" alt="Fortem">
  <h1>Fortem</h1>
  <p><strong>Musical tunings at your fingertips.</strong></p>
  <p>Open-source piano for Android, iPhone and iPad · pure tone and grand piano · performance recording</p>
  <p>
    <img src="https://img.shields.io/badge/Android-8.1%2B-91bda9?style=flat-square" alt="Android 8.1+">
    <img src="https://img.shields.io/badge/iOS%20%2F%20iPadOS-16%2B-91bda9?style=flat-square" alt="iOS / iPadOS 16+">
    <img src="https://img.shields.io/badge/source-1.0.3-91bda9?style=flat-square" alt="Source version 1.0.3">
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--only-91bda9?style=flat-square" alt="GPL-3.0-only"></a>
  </p>
</div>

Fortem lets you play, compare musical tuning systems and retune notes while they
are sounding. Hold a note, choose a different tonic or tuning, and listen to how
the intervals change. Save a performance together with its settings and export
it as WAV (*an audio file*) to listen outside the app.

Fresh installs use piano, equal temperament (12-TET) and tonic C; upward-swipe latching is off, and recordings and Trash are empty.

## Features

- **88 keys, A0–C8.** Multi-touch input, a keyboard range map, adjustable
  key widths and smooth scrolling with inertia.
- **Six built-in tunings.** Equal temperament, Pythagorean, just intonation,
  quarter-comma meantone, Werckmeister III and Kirnberger III.
- **Retuning while playing.** Choose a tonic (*the root note of the tuning*)
  and an A4 reference frequency. View each note's frequency and deviation
  in cents (*hundredths of a semitone*).
- **Two sounds.** A pure sine wave and the sampled Salamander Grand Piano.
- **Note sustain.** Swipe up to latch a note, use individual note locks,
  or use the pedal to connect successive chords.
- **Performance recording.** Capture notes and changes to settings, play
  performances back, start a new take immediately, export WAV audio and
  recover deleted recordings from a 30-day trash folder.
- **Custom tunings.** Import Scala `.scl` files (*lists of tuning intervals*)
  for 12-note octave layouts. Examples are in [`examples/scala`](examples/scala).
- **External instruments.** Send MIDI (*note-control messages*) through
  available Android or Core MIDI ports, with a choice of whether the device also plays sound.

- **Two interface languages.** Russian by default or English; the choice is saved and applied immediately.

## Start playing

1. Use the range map to choose a keyboard area and a comfortable key width.
2. Select a tuning and tonic. <img src="docs/assets/ui/settings.svg" width="22" height="22" alt="Settings"> Settings let you show only the tunings you need.
3. Switch sounds: the upper dot <img src="docs/assets/ui/timbre-tone.svg" width="22" height="22" alt="Pure tone"> selects the pure tone; the lower dot <img src="docs/assets/ui/timbre-piano.svg" width="22" height="22" alt="Piano"> selects piano.
4. With upward-swipe latching enabled in settings, swipe up from a note to sustain it; tap its lock <img src="docs/assets/ui/lock.svg" width="22" height="22" alt="Latched note"> to release it.
5. <img src="docs/assets/ui/record.svg" width="22" height="22" alt="Record"> starts recording; <img src="docs/assets/ui/stop.svg" width="22" height="22" alt="Stop and save"> saves the take,
   and <img src="docs/assets/ui/rerecord.svg" width="22" height="22" alt="Re-record"> immediately restarts the current draft. <img src="docs/assets/ui/play.svg" width="22" height="22" alt="Play"> plays a saved performance.

[Interface controls](docs/USER_GUIDE.en.md#interface-controls) · [User guide](docs/USER_GUIDE.en.md) · [Connecting to Ardour](docs/MIDI.en.md)


## How tuning works

In unequal tunings, Fortem retunes the instrument relative to the selected
tonic (*the root note of the tuning*). Just intonation uses a fixed chromatic
template; automatic adjustment to arbitrary chords is not implemented.
Some sharp and flat spellings of the tonic produce different frequencies.

When MIDI is connected, equal temperament with A4 = 440 Hz is used.
Unequal tuning of external MIDI instruments is not yet supported.
Internal recordings store performance events, rather than microphone audio.

## Privacy

The original app contains no ads, analytics or account sign-in. The Android app operates
without internet or microphone permissions. Neither version records microphone audio. Settings and performances are
stored locally; export uses a folder selected through the system dialog.
Android may include app data in system backups if backups are enabled
on the device.
On iPhone/iPad, backups follow iOS/iPadOS settings; WAV export uses the system Files and Share interfaces.

## Development

Android uses Kotlin, Jetpack Compose, C++20 and Oboe; its project is at the repository root.
iOS/iPadOS uses SwiftUI, UIKit and C++; the Xcode project is `apple/Fortem.xcodeproj`.
Both platforms share the piano resources, so Apple builds require the complete repository. Pitch calculations are separate from the interface,
and internal recordings preserve the tuning settings of the performance.

- [Changelog](docs/CHANGELOG.en.md)
- [Contributing](CONTRIBUTING.en.md)

## Licenses and credits

Fortem code and new graphic assets: **GPL-3.0-only** — [LICENSE](LICENSE).

Salamander Grand Piano recordings: **Alexander Holm**;
Accurate Salamander processing: **C. Yamauchi**.
The audio files remain licensed under **CC BY 3.0**;
see [attribution and modifications](app/src/main/assets/licenses/SALAMANDER_GRAND_PIANO.txt).

Native **Oboe 1.10.0** libraries: Google, **Apache-2.0**.
See [source, license and checksums](third_party/oboe/README.md).


