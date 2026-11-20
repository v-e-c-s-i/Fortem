# Connecting Fortem to an external instrument

[Русский](MIDI.md) · **English**

[Fortem interface controls](USER_GUIDE.en.md#interface-controls). The icons below refer to Fortem; Ardour controls are named in text.

Fortem labels below use the English interface. Select <img src="assets/ui/settings.svg" width="22" height="22" alt="Settings"> **Settings → INTERFACE LANGUAGE → English**. Ardour labels follow the language specified in each walkthrough.

## Using the phone as a MIDI keyboard for Ardour

The following steps use Linux with ALSA (*the system for audio and MIDI ports*).
If Ardour is already running through JACK or PipeWire, port connections
may be configured differently.

1. Connect the phone to the computer with a USB cable that supports data.
   In Android's USB notification, select **MIDI**, rather than charging only.
2. In Fortem, open <img src="assets/ui/settings.svg" width="22" height="22" alt="Settings"> **Settings → MIDI output** and select
   **Android USB Peripheral Port**, or the name of the USB port that appears.
   Wait for the MIDI label on the playing screen.
3. In Ardour's **Audio/MIDI Setup** window, select **ALSA** and, if available,
   **ALSA Sequencer** under **MIDI System**. Do not select None.
   With ALSA, Ardour can see the system's MIDI ports without a separate bridge.
4. Under **Edit → Preferences → MIDI → MIDI Port Config**, find the phone's
   input and enable **Music Data** (*receiving note events*).
5. Add a **MIDI track**, choose the phone as its input, and add an instrument
   (*a plugin that turns notes into sound*). Accept channel 1 or all channels.
   Enable **In** monitoring to hear live playing and route the instrument's
   output to Master. Notes may arrive silently if no instrument is loaded.
6. To record, arm the MIDI track, enable the main record button, then press
   Play in Ardour's transport controls. Play the keys in Fortem.

If it does not work, open **Window → MIDI Tracer** (*a view of incoming MIDI messages*)
and choose the phone's input. Note On/Note Off messages mean that commands have
reached Ardour: check the track input, monitoring and instrument.
If no messages arrive, check USB MIDI mode, the cable and the selected Fortem port.
Another app may be using the Android port. MIDI transfers notes, rather than
Salamander audio; use WAV export to transfer Fortem's sound.

Official documentation:

- [Android: USB peripheral mode](https://source.android.com/docs/core/audio/midi_test#usb-test-peripheral-mode)
- [Ardour: MIDI on Linux](https://manual.ardour.org/setting-up-your-system/setting-up-midi/midi-on-linux/)
- [Ardour: MIDI Port Config](https://manual.ardour.org/preferences-and-session-properties/preferences-dialog/)
- [Ardour: MIDI tracks and instruments](https://manual.ardour.org/working-with-tracks/track-types/)
- [Ardour: full manual, including MIDI Tracer](https://manual.ardour.org/ardourmanual.html)

## Checking sound from the beginning

This walkthrough also identifies the Russian labels used in Ardour 8.12.
Some labels may differ in other versions; **ACE Reasonable Synth** is not translated.
Check the computer's sound first, then the phone, then drums.

1. Connect headphones to the computer. Open **Window → Audio/MIDI Setup**
   (**Окна → Настройка звука и MIDI**).
   Under **Output Device** (**Устройство вывода**), or **Device**
   (**Устройство**) if input and output are combined, select the computer's
   audio device connected to the headphones or speakers. This is not the
   phone's MIDI port. If changing the device is unavailable, stop the audio
   engine in this window, select the device, then restart the engine.
   With JACK, the device is set in JACK's configuration, and Ardour's window
   may offer fewer options.
2. Right-click empty space in the track-name area **to the left of the timeline**.
   Select **Add Tracks/Busses/VCA…** (**Добавить дорожки/шины/VCA…**).
   Choose **MIDI Tracks** (**MIDI-дорожки**) and name the track “Test”.
   Under **Instrument** (**Инструмент**), choose **ACE Reasonable Synth**
   (*a simple synthesizer with a piano sound that needs no external sound bank*).
   Click **Add and Close** (**Добавить и закрыть**).
   Create a separate test track and keep your existing track.
3. The Test track has a narrow **vertical keyboard** to the left of its note area.
   Click a key to check sound without the phone. If the controls are cramped,
   double-click empty space in the track's left-hand panel to increase its height.
4. If you hear sound, open **Window → MIDI Connections**
   (**Окна → Соединения MIDI**).
   Find the phone's working MIDI output among the sources and the Test track's
   input among the destinations. Click their intersecting cell to connect them.
   The phone/port name depends on its firmware; use the port that previously
   received MIDI successfully.
5. Enable the track's **Monitor Input** control (**Мониторинг входа**),
   identified by its tooltip. The button may be labelled **In** or **I**,
   including in Russian builds. It belongs to the track, rather than the
   main transport controls.
6. Connect MIDI in Fortem and clear the checkbox <img src="assets/ui/checked.svg" width="22" height="22" alt="Enabled setting"> for “Phone sound with MIDI”.
   Play a key: the computer's instrument should respond.
   Check chords, repeated notes, sliding, release and <img src="assets/ui/play.svg" width="22" height="22" alt="Play"> playback of a saved
   performance. Stop playback <img src="assets/ui/stop.svg" width="22" height="22" alt="Stop"> and disconnect the cable; no MIDI note should
   remain sounding.
7. If **step 3 is silent**, troubleshoot the computer's audio setup first.


Sources:

- [Creating and monitoring a MIDI track](https://manual.ardour.org/working-with-tracks/midi-track-controls/)
- [Instruments](https://manual.ardour.org/plugins-instruments/)
- [Audio setup](https://manual.ardour.org/sessions-tracks/sessions/audiomidi-setup/)
- [Russian labels in Ardour 8.12](https://sources.debian.org/src/ardour/1:8.12.0%2Bds-1/gtk2_ardour/po/ru.po/)

## Drums in Ardour after checking piano sound

1. Create another MIDI track named “Drums”. Choose **General MIDI Synth**
   if it is listed. Official Ardour builds include it with a sound bank;
   distribution packages may omit it.
   ACE Reasonable Synth provides piano sound, rather than drums.
   ACE Fluid Synth needs a separately loaded `.sf2` file (*a sound bank*).
2. Connect the same phone input to Drums and enable input monitoring.
   Disable the other track's input monitoring while testing one track,
   so that the instruments do not play together.
3. Right-click the Drums track name and choose **Channel Selector…**
   (**Выбор каналов…**).
   For incoming events, choose the option that forces all channels to one
   selected channel (**Принудительно все каналы в 1 канал**), then select **10**.
   Here “one channel” means the chosen destination channel, not necessarily
   channel number 1. Fortem sends on channel 1; Ardour remaps it to channel 10.
4. Select the C2 area on Fortem's range map. In the standard General MIDI map,
   C2 (note 36) is bass drum, D2 (38) is snare, and F♯2 (42) is closed hi-hat
   (*a pair of cymbals*). If piano pitches sound instead, check the instrument
   and channel 10.
5. Turn off the pedal and manual note locks for the drum test.
   Check single hits, rapid repeats and two hits together.
   You can then play a saved performance: matching note numbers become
   drum hits, while other notes may be silent.

[Ardour channel controls](https://manual.ardour.org/working-with-tracks/midi-track-controls/) ·
[Available instruments](https://manual.ardour.org/plugins-instruments/) ·
[General MIDI percussion note numbers](https://usermanuals.finalemusic.com/Finale2011Win/Content/Finale/General_MIDI_Percussion_Map_Table.htm)
