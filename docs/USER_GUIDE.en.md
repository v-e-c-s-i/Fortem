# Fortem user guide

[Русский](USER_GUIDE.md) · **English**

For Fortem **1.0.3** on Android and **1.0.3** on iPhone and iPad.

## Window size on Android

In full-screen landscape, the keyboard map sits above the keys. The toolbar fits
the space available inside system bars, display cutouts and rounded corners.

In a narrow window, such as a phone in split screen, the map spans the width below
the keys. The current note, frequency and tuning offset sit between the recording
controls and the pedal. You can still tap or drag the map to move the range and
pinch with two fingers to zoom.

## Interface language

Android settings use separate sections for Interface, Tunings, Scala, Reference pitch, MIDI output, Held notes, Recordings/WAV and Symbol help. Piano sample credits are at the bottom, beside the app version.

**Settings → Interface language** opens **Русский / English** from one row. Russian is the default, independently of the device language. A previously selected English preference is preserved on update.
Switching languages inside Fortem does not restart the app or reset playing or recording.
Names of custom tunings, recordings and MIDI devices remain unchanged.

<a id="interface-controls"></a>

## Interface controls

The images reproduce Fortem's control shapes. Text buttons are shown by their labels.

| Control | Name | Action |
| --- | --- | --- |
| <img src="assets/ui/record.svg" width="22" height="22" alt="Record"> | Record | Start a new recording. |
| <img src="assets/ui/play.svg" width="22" height="22" alt="Play"> | Play | Play the selected performance. |
| <img src="assets/ui/stop.svg" width="22" height="22" alt="Stop"> | Stop | During recording, stop and save the take; during playback, stop playback. |
| <img src="assets/ui/rerecord.svg" width="22" height="22" alt="Re-record"> | Re-record | During recording, tap to restart the draft; in the recording list, hold to replace the selected take. |
| <img src="assets/ui/export.svg" width="22" height="22" alt="Export WAV"> | Export WAV | Save the performance as an audio file. |
| <img src="assets/ui/delete.svg" width="22" height="22" alt="Delete recording"> | Delete recording | Move the take to the trash, where it can be recovered for 30 days. |
| <img src="assets/ui/lock.svg" width="22" height="22" alt="Latched note"> | Latched note | Appears on a key after an upward swipe; tap the lock to release it. |
| <img src="assets/ui/unlock.svg" width="22" height="22" alt="Release manual locks"> | Release manual locks | Release manually latched notes; pedal-held notes are controlled separately. |
| <img src="assets/ui/settings.svg" width="22" height="22" alt="Settings"> | Settings | Tap to open Settings on the right. **Android:** hold for 0.6 seconds until Exit appears, then another 0.9 seconds until the ring fills; an interrupted hold does not open Settings. **iPhone/iPad:** leave the app with the system Home gesture or the device Home button. The current take is saved when closing or entering the background. |
| <img src="assets/ui/checked.svg" width="22" height="22" alt="Enabled setting"> | Enabled setting | A checkbox beside the title indicates a visible tuning or an enabled setting. |
| <img src="assets/ui/delete-tuning.svg" width="22" height="22" alt="Delete tuning"> | Delete tuning | Delete an imported Scala tuning; built-in tunings cannot be deleted. |
| <img src="assets/ui/timbre-tone.svg" width="22" height="22" alt="Pure tone"> | Pure tone | The upper dot in the sound selector is active. |
| <img src="assets/ui/timbre-piano.svg" width="22" height="22" alt="Piano"> | Piano | The lower dot in the sound selector is active. |
| <img src="assets/ui/pedal.svg" width="22" height="22" alt="Pedal"> | Pedal | Sustain between successive chords; teal highlighting means enabled. |
| **Recordings** | Recording list | Open saved performances; the timer is shown alongside it. |
| **MIDI** | Connection status | Indicates that an external MIDI port is connected. |
| **Recording trash · 30 days** | Recording trash | A text button in settings; open deleted takes to play or restore them. |

The same reference is available inside the app: **Settings → INTERFACE SYMBOLS**.

## Keyboard and tuning

The range map shows the full A0–C8 keyboard. Move the selected area to scroll
the main keyboard; change its width to adjust the zoom.
A quick gesture continues scrolling with inertia. A new touch stops it.
A held finger follows the key underneath it, including during inertial scrolling.

Select the tuning on the playing screen. Tap **C ▾** (or the current tonic) to open the compact tonic diagram. Natural notes occupy the top row, with sharp and flat choices below, offset between them. Unequal tunings offer separate sharp and flat choices, such as C♯ and D♭. **Settings → Control size** adjusts the top panels from 80% to 120%, independently of keyboard zoom.
<img src="assets/ui/settings.svg" width="22" height="22" alt="Settings"> Settings let you choose which tunings are visible and import a 12-note,
octave-based Scala `.scl` file (*a list of tuning intervals*).
A4 sets the overall pitch; unequal tunings are calculated relative to the tonic.
Changing the settings retunes notes that are already sounding.

The current note uses two lines: **name · frequency in Hz**, then a gray cents offset from 12-TET at the same A4 reference. “+” means higher; “−” means lower.

## Sustaining notes

Enable **Settings → Held notes → Latch notes with an upward swipe** only when needed; it is off by default. Leave it off to slide onto black keys without latching them. Current manual latches are released; the pedal remains independent and saved recordings play unchanged. The preference is remembered.

Swipe up from a sounding key to latch it. The note keeps sounding after you lift
your finger. Tap its lock icon <img src="assets/ui/lock.svg" width="22" height="22" alt="Latched note"> to release that note; the shared open-lock button <img src="assets/ui/unlock.svg" width="22" height="22" alt="Release manual locks">
releases manually latched notes.

The pedal sustains notes between successive chords. Manual locks and the pedal
are independent ways to sustain notes; their state is captured in recordings.

## Initial state

On Android, a fresh install shows C4 (middle C) through E5. Saved ranges are preserved when updating.

A fresh install starts with piano, equal temperament (12-TET), tonic C and upward-swipe latching disabled. Recordings and Trash are empty. Updating preserves saved settings and personal recordings, including personal Trash; only the former bundled test recordings are removed.

## Recordings

On Android, the list opens at the playing take, or at the start when idle. It does not follow playback while you browse. Close it with Close, system Back or a tap outside. Long titles scroll continuously within the space before the action buttons.

Start recording with <img src="assets/ui/record.svg" width="22" height="22" alt="Record">. During recording, <img src="assets/ui/stop.svg" width="22" height="22" alt="Stop and save"> stops
and saves the take. <img src="assets/ui/rerecord.svg" width="22" height="22" alt="Re-record"> resets the current draft and timer:
the rejected take is not added to the list of completed recordings.

In the **Recordings** list, **hold** <img src="assets/ui/rerecord.svg" width="22" height="22" alt="Replace selected take"> to replace the selected take.
During an active recording, **tap** it to restart the current draft.

Fortem stores notes, timing, timbre and changes to the tuning settings.
Keys are highlighted during playback. The **Retrigger held notes during playback** setting determines
whether held notes are retriggered when the tuning or tonic changes during playback.

Saved performances can be played <img src="assets/ui/play.svg" width="22" height="22" alt="Play">,
exported as WAV (*an audio file*) <img src="assets/ui/export.svg" width="22" height="22" alt="Export WAV"> or deleted <img src="assets/ui/delete.svg" width="22" height="22" alt="Delete">.
Deleted recordings stay in the trash for 30 days. Select the WAV folder through
the system dialog; your choice is retained between sessions and can be changed
in settings.

## External instruments

MIDI (*note-control messages*) sends note commands, rather than Fortem's piano
audio. Select a sound on the external instrument. The “Phone sound with MIDI”
setting controls whether the phone also plays sound.

While connected, equal temperament with A4 = 440 Hz is used.
New internal recordings are unavailable, but saved performances can still
be played and exported as WAV.

[Connecting to Ardour](MIDI.en.md)

### Recordings on iPhone and iPad

Each row keeps ▶ play (■ while playing), re-record and trash visible. Rename, save WAV and share a copy are available through the More (⋯) menu. Hold re-record to start a replacement; the original stays available until the new take is saved, then moves to trash. An empty take does not replace the original. The timer stays inside the transport panel. Playback highlights notes on the map without moving the selected range; move it manually.


### Settings on iPhone and iPad

Tap the gear to open the right-side panel, which takes up about 45% of the window. Tap × or the dimmed area to close.
Leave the app using the system Home gesture or the device Home button.
Holding the gear to exit applies only to Android.
