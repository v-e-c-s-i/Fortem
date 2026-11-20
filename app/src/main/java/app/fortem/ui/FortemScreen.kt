// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.fortem.audio.AudioEngine
import app.fortem.audio.AudioTimbre
import app.fortem.recording.AndroidMonotonicClock
import app.fortem.recording.ConfigurationPerformanceEvent
import app.fortem.recording.LatchPerformanceEvent
import app.fortem.recording.NotePerformanceEvent
import app.fortem.recording.PedalPerformanceEvent
import app.fortem.recording.PerformanceConfiguration
import app.fortem.recording.PerformancePlayer
import app.fortem.recording.PerformanceRecorder
import app.fortem.recording.RecorderState
import app.fortem.recording.PerformanceRecording
import app.fortem.recording.recordingName
import app.fortem.settings.UserSettings
import app.fortem.tuning.TuningModel
import app.fortem.tuning.TonicSpelling
import app.fortem.tuning.TonicAnchors
import app.fortem.tuning.TunedNote
import app.fortem.tuning.TuningEngine
import app.fortem.tuning.TuningSystem
import app.fortem.tuning.ScalaScale
import app.fortem.tuning.ScalaLibrary
import app.fortem.midi.forMidiOutput
import app.fortem.midi.MidiOutputEngine
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private enum class PlaybackHighlight { TUNING, TONIC, REFERENCE, TIMBRE }

@Composable
fun FortemScreen(
    audioEngine: AudioEngine,
    onLocalOutputEnabled: (Boolean) -> Unit,
    userSettings: UserSettings,
    midiOutput: MidiOutputEngine,
    audioSessionGeneration: Int,
    pianoAvailable: Boolean,
    performanceRecorder: PerformanceRecorder,
    onExit: () -> Unit,
    inMultiWindow: Boolean = false,
    cornerInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
) {
    val strings = LocalAppStrings.current
    var interfaceScale by remember { mutableStateOf(userSettings.loadInterfaceScale()) }
    // Interpolate panel layout without changing the piano's touch coordinates.
    val displayedInterfaceScale by animateFloatAsState(interfaceScale,
        animationSpec = tween(120), label = "control-size")
    // Save after dragging pauses, instead of scheduling a disk write for every frame.
    LaunchedEffect(interfaceScale) { delay(200); userSettings.saveInterfaceScale(interfaceScale) }
    var latchGesturesEnabled by remember { mutableStateOf(userSettings.loadLatchGesturesEnabled()) }
    var scalaScales by remember { mutableStateOf(userSettings.loadScalaScales()) }
    var visibleScalaIds by remember { mutableStateOf(userSettings.loadVisibleScalaIds()) }
    val midiState by midiOutput.state.collectAsState()
    val midiConnected = midiState.selected != null && !midiState.connecting
    var midiLocalSound by remember { mutableStateOf(userSettings.loadMidiLocalSound()) }
    var scalaScale by remember { mutableStateOf(userSettings.loadScalaScale()) }
    val initiallyVisibleTunings = remember(userSettings) {
        userSettings.loadVisibleTuningSystems()
    }
    val initialTuning = remember(userSettings, initiallyVisibleTunings) {
        userSettings.loadTuningSystem().takeIf { it in initiallyVisibleTunings }
            ?: initiallyVisibleTunings.first()
    }
    var tuningName by rememberSaveable {
        mutableStateOf(initialTuning.name)
    }
    var tonicPitchClass by rememberSaveable {
        mutableIntStateOf(userSettings.loadTonicPitchClass())
    }
    var tonicSpelling by rememberSaveable { mutableStateOf(userSettings.loadTonicSpelling()) }
    var visibleTunings by remember { mutableStateOf(initiallyVisibleTunings) }
    var referenceFrequencyHz by remember {
        mutableStateOf(userSettings.loadReferenceFrequencyHz())
    }
    var retriggerHeldNotesDuringPlayback by remember {
        mutableStateOf(userSettings.loadRetriggerHeldNotesDuringPlayback())
    }
    var holdUntilNextChord by remember {
        mutableStateOf(userSettings.loadHoldUntilNextChord())
    }
    var activeNotes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var latchedNotes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var clearLatchesSignal by remember { mutableIntStateOf(0) }
    var keyboardResetSignal by remember { mutableIntStateOf(0) }
    // The dialog reveals the playing row once on opening; playback never follows scrolling.
    val recordingsListState = rememberLazyListState()
    var showRecordings by rememberSaveable { mutableStateOf(false) }
    var showRecordingTrash by rememberSaveable { mutableStateOf(false) }
    var transportBusy by remember { mutableStateOf(false) }
    var recordingError by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var keyboardViewport by remember {
        mutableStateOf(userSettings.loadKeyboardViewport())
    }
    var audioTimbreName by rememberSaveable {
        mutableStateOf(userSettings.loadAudioTimbre().name)
    }
    var recorderState by remember { mutableStateOf(performanceRecorder.state()) }
    var playbackNotes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var playbackRecording by remember { mutableStateOf<PerformanceRecording?>(null) }
    var playbackFromTrash by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf(false) }
    var playbackLatchedNotes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var playbackConfiguration by remember { mutableStateOf<PerformanceConfiguration?>(null) }
    var playbackPedalEnabled by remember { mutableStateOf(false) }
    var playbackPedalHighlighted by remember { mutableStateOf(false) }
    var pedalHighlightGeneration by remember { mutableIntStateOf(0) }
    var playbackHighlights by remember { mutableStateOf<Set<PlaybackHighlight>>(emptySet()) }
    var playbackHighlightGeneration by remember { mutableIntStateOf(0) }
    var playbackStartedAtNanos by remember { mutableLongStateOf(0L) }
    var playbackJob by remember { mutableStateOf<Job?>(null) }
    var trashPreviewLoadJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val tuningSystem = TuningSystem.entries.firstOrNull { it.name == tuningName }
        ?.takeIf { it in visibleTunings }
        ?: visibleTunings.first()
    val orderedVisibleTunings = TuningSystem.entries.filter { it in visibleTunings }
    val audioTimbre = AudioTimbre.entries.firstOrNull { it.name == audioTimbreName }
        ?.takeIf { it != AudioTimbre.PIANO || pianoAvailable }
        ?: AudioTimbre.PURE_TONE
    val currentConfiguration = PerformanceConfiguration(
        tuningSystem = tuningSystem,
        tonicPitchClass = tonicPitchClass,
        tonicSpelling = tonicSpelling,
        tuningModel = TuningModel.TONIC_RELATIVE,
        referenceFrequencyHz = referenceFrequencyHz,
        timbre = audioTimbre,
        scalaScale = if (tuningSystem == TuningSystem.SCALA) scalaScale else null,
    ).forMidiOutput(midiConnected)
    val isPlaying = playbackJob != null
    // Frame state is read only by progress drawing and second-granularity clocks.
    val frameTime = rememberTransportFrameTime(isPlaying || recorderState.isRecording)
    val playbackPositionNanos = remember(isPlaying, playbackStartedAtNanos, frameTime) {
        { if (isPlaying) (frameTime.longValue - playbackStartedAtNanos).coerceAtLeast(0L) else 0L }
    }
    val recordingPositionNanos = remember(recorderState, frameTime) {
        val origin = AndroidMonotonicClock.nowNanos() - recorderState.elapsedNanos;
        { if (recorderState.isRecording) (frameTime.longValue - origin).coerceAtLeast(0L) else 0L }
    }
    val effectiveConfiguration = (playbackConfiguration ?: currentConfiguration).forMidiOutput(midiConnected)
    // MIDI блокирует выбор, но не скрывает пользовательскую раскладку кнопок.
    val displayedTunings = if (effectiveConfiguration.tuningSystem in orderedVisibleTunings) {
        orderedVisibleTunings
    } else {
        listOf(effectiveConfiguration.tuningSystem) + orderedVisibleTunings
    }
    val displayedActiveNotes = if (isPlaying) playbackNotes else activeNotes
    // Частоты подготавливаются при смене настройки, а не в обработчике касания.
    // Тембр не входит в ключ: он не меняет частоты клавиш.
    val tunedKeyboard = remember(
        effectiveConfiguration.tuningSystem, effectiveConfiguration.tonicPitchClass,
        effectiveConfiguration.referenceFrequencyHz, effectiveConfiguration.scalaScale,
        effectiveConfiguration.tonicSpelling, effectiveConfiguration.tuningModel,
    ) {
        Array(128) { midiNote ->
            TuningEngine.tune(
                midiNote = midiNote,
                tuningSystem = effectiveConfiguration.tuningSystem,
                tonicPitchClass = effectiveConfiguration.tonicPitchClass,
                referenceFrequencyHz = effectiveConfiguration.referenceFrequencyHz,
                scalaScale = effectiveConfiguration.scalaScale,
                tonicSpelling = effectiveConfiguration.tonicSpelling,
                tuningModel = effectiveConfiguration.tuningModel,
            )
        }
    }
    val observedNote: TunedNote? = displayedActiveNotes.maxOrNull()?.let { tunedKeyboard[it] }

    fun applyTuningVisibility(newTunings: Set<TuningSystem>, newScalaIds: Set<String>) {
        val next = resolveTuningVisibility(newTunings, newScalaIds, scalaScales, TuningSystem.valueOf(tuningName), scalaScale)
        visibleTunings = next.tunings
        visibleScalaIds = next.scalaIds
        tuningName = next.selectedTuning.name
        scalaScale = next.selectedScale
        userSettings.saveVisibleTuningSystems(visibleTunings)
        userSettings.saveScalaLibrary(scalaScales, visibleScalaIds, scalaScale)
        userSettings.saveTuningSystem(next.selectedTuning)
    }

    LaunchedEffect(midiConnected, midiLocalSound) {
        // Выключается только конечный живой выход; note-off, запись и WAV независимы.
        onLocalOutputEnabled(!midiConnected || midiLocalSound)
    }

    fun stopPlayback() {
        playbackJob?.cancel()
    }

    fun startPlayback(sourceRecording: PerformanceRecording? = recorderState.lastRecording,
                      fromTrash: Boolean = false) {
        val recording = sourceRecording ?: return
        if (recorderState.isRecording || playbackJob != null) return

        showSettings = false
        activeNotes.forEach(audioEngine::noteOff)
        keyboardResetSignal += 1
        clearLatchesSignal += 1
        playbackRecording = recording
        playbackFromTrash = fromTrash
        playbackConfiguration = recording.initialConfiguration
        playbackNotes = emptySet()
        playbackLatchedNotes = emptySet()
        // Старые записи без PEDAL не позволяют восстановить положение кнопки.
        playbackPedalEnabled = false
        playbackPedalHighlighted = false
        playbackHighlights = emptySet()
        playbackStartedAtNanos = AndroidMonotonicClock.nowNanos()

        playbackJob = coroutineScope.launch {
            val soundingNotes = linkedMapOf<Int, Float>()
            var configuration = recording.initialConfiguration
            audioEngine.setTimbre(configuration.timbre)
            try {
                PerformancePlayer(AndroidMonotonicClock).play(recording) { event ->
                    when (event) {
                        is NotePerformanceEvent -> {
                            if (event.isNoteOn) {
                                val tunedNote = TuningEngine.tune(
                                    midiNote = event.midiNote,
                                    tuningSystem = configuration.tuningSystem,
                                    tonicPitchClass = configuration.tonicPitchClass,
                                    referenceFrequencyHz = configuration.referenceFrequencyHz,
                                    scalaScale = configuration.scalaScale,
                                    tonicSpelling = configuration.tonicSpelling,
                                    tuningModel = configuration.tuningModel,
                                )
                                audioEngine.noteOn(
                                    midiNote = event.midiNote,
                                    frequencyHz = tunedNote.frequencyHz,
                                    velocity = event.velocity,
                                )
                                soundingNotes[event.midiNote] = event.velocity
                            } else {
                                audioEngine.noteOff(event.midiNote)
                                soundingNotes.remove(event.midiNote)
                            }
                            playbackNotes = soundingNotes.keys.toSet()
                        }

                        is ConfigurationPerformanceEvent -> {
                            val nextConfiguration = event.configuration
                            playbackHighlights = buildSet {
                                if (nextConfiguration.tuningSystem != configuration.tuningSystem ||
                                    nextConfiguration.scalaScale != configuration.scalaScale ||
                                    nextConfiguration.tuningModel != configuration.tuningModel) {
                                    add(PlaybackHighlight.TUNING)
                                }
                                if (nextConfiguration.tonicPitchClass != configuration.tonicPitchClass ||
                                    nextConfiguration.tonicSpelling != configuration.tonicSpelling) {
                                    add(PlaybackHighlight.TONIC)
                                }
                                if (nextConfiguration.referenceFrequencyHz !=
                                    configuration.referenceFrequencyHz
                                ) {
                                    add(PlaybackHighlight.REFERENCE)
                                }
                                if (nextConfiguration.timbre != configuration.timbre) {
                                    add(PlaybackHighlight.TIMBRE)
                                }
                            }
                            if (playbackHighlights.isNotEmpty()) playbackHighlightGeneration += 1
                            val shouldRetrigger = retriggerHeldNotesDuringPlayback && !midiOutput.connected &&
                                nextConfiguration.timbre == configuration.timbre &&
                                (
                                    nextConfiguration.tuningSystem != configuration.tuningSystem ||
                                        nextConfiguration.scalaScale != configuration.scalaScale ||
                                        nextConfiguration.tonicPitchClass !=
                                        configuration.tonicPitchClass ||
                                        nextConfiguration.tonicSpelling != configuration.tonicSpelling ||
                                        nextConfiguration.tuningModel != configuration.tuningModel
                                    )
                            configuration = nextConfiguration
                            playbackConfiguration = configuration
                            if (PlaybackHighlight.TIMBRE in playbackHighlights) {
                                audioEngine.setTimbre(configuration.timbre)
                            }
                            soundingNotes.forEach { (midiNote, velocity) ->
                                val tunedNote = TuningEngine.tune(
                                    midiNote = midiNote,
                                    tuningSystem = configuration.tuningSystem,
                                    tonicPitchClass = configuration.tonicPitchClass,
                                    referenceFrequencyHz = configuration.referenceFrequencyHz,
                                    scalaScale = configuration.scalaScale,
                                    tonicSpelling = configuration.tonicSpelling,
                                    tuningModel = configuration.tuningModel,
                                )
                                if (shouldRetrigger) {
                                    audioEngine.noteOn(midiNote, tunedNote.frequencyHz, velocity)
                                } else {
                                    audioEngine.retune(midiNote, tunedNote.frequencyHz)
                                }
                            }
                        }

                        is PedalPerformanceEvent -> {
                            if (playbackPedalEnabled != event.enabled) {
                                playbackPedalEnabled = event.enabled
                                playbackPedalHighlighted = true
                                pedalHighlightGeneration += 1
                            }
                        }

                        is LatchPerformanceEvent -> {
                            playbackLatchedNotes = event.latchedNotes
                        }
                    }
                }
            } finally {
                soundingNotes.keys.forEach(audioEngine::noteOff)
                playbackNotes = emptySet()
                playbackLatchedNotes = emptySet()
                playbackConfiguration = null
                playbackPedalEnabled = false
                playbackPedalHighlighted = false
                playbackRecording = null
                playbackFromTrash = false
                playbackHighlights = emptySet()
                audioEngine.setTimbre(audioTimbre)
                playbackJob = null
            }
        }
    }

    LaunchedEffect(tuningSystem, tonicPitchClass, tonicSpelling, referenceFrequencyHz, scalaScale, midiConnected) {
        // Последний выбор восстанавливается при следующем запуске приложения.
        userSettings.saveTuningSystem(tuningSystem)
        userSettings.saveTonicPitchClass(tonicPitchClass)
        userSettings.saveTonicSpelling(tonicSpelling)
        userSettings.saveReferenceFrequencyHz(referenceFrequencyHz)

        // Живая игра, включая запись, всегда меняет только высоту уже звучащего
        // голоса. Настройка повторной атаки относится исключительно к проигрывателю.
        // При отключении MIDI возвращаем исходные частоты и живой игре,
        // и удерживаемым нотам текущей записи, без повторного удара.
        val source = playbackConfiguration ?: currentConfiguration
        val notesToRetune = if (playbackJob != null) playbackNotes else activeNotes
        notesToRetune.forEach { midiNote ->
            val tunedNote = TuningEngine.tune(
                midiNote = midiNote,
                tuningSystem = source.tuningSystem,
                tonicPitchClass = source.tonicPitchClass,
                referenceFrequencyHz = source.referenceFrequencyHz,
                scalaScale = source.scalaScale,
                tonicSpelling = source.tonicSpelling,
                tuningModel = source.tuningModel,
            )
            audioEngine.retune(midiNote, tunedNote.frequencyHz)
        }
    }

    LaunchedEffect(keyboardViewport) {
        // Частые промежуточные кадры жеста не записываются во флеш-память.
        delay(250)
        userSettings.saveKeyboardViewport(keyboardViewport)
    }

    LaunchedEffect(audioTimbre, pianoAvailable) {
        // Выбранный тембр применяется и к уже удерживаемым нотам, а также
        // восстанавливается при следующем запуске приложения.
        audioEngine.setTimbre(audioTimbre)
        // Если банк временно не загрузился, не затираем сохранённое предпочтение
        // пользователя: после исправления ресурсов фортепиано вернётся само.
        if (pianoAvailable || audioTimbreName != AudioTimbre.PIANO.name) {
            userSettings.saveAudioTimbre(audioTimbre)
        }
    }

    LaunchedEffect(currentConfiguration) {
        recorderState = performanceRecorder.recordConfiguration(currentConfiguration)
    }

    // Раз в секунду сохраняется небольшой черновик. Входящий звонок или
    // уничтожение процесса не должны стирать уже сыгранную часть.
    LaunchedEffect(recorderState.isRecording) {
        while (recorderState.isRecording) {
            delay(1_000)
            recorderState = runCatching {
                withContext(Dispatchers.IO) { performanceRecorder.checkpoint() }
            }.getOrElse {
                // Ошибка диска не должна завершать приложение и обрывать игру.
                performanceRecorder.state()
            }
        }
    }

    LaunchedEffect(playbackHighlightGeneration) {
        if (playbackHighlightGeneration > 0) {
            delay(850)
            playbackHighlights = emptySet()
        }
    }

    // Отдельный таймер: смена строя не обрывает акцент педали и наоборот.
    LaunchedEffect(pedalHighlightGeneration) {
        if (pedalHighlightGeneration > 0) {
            delay(850)
            playbackPedalHighlighted = false
        }
    }

    LaunchedEffect(audioSessionGeneration) {
        // Фоновое чтение корзины не должно запустить звук после смены сеанса/сворачивания.
        trashPreviewLoadJob?.cancel()
        recorderState = performanceRecorder.state()
        if (playbackJob != null) stopPlayback()
    }

    LaunchedEffect(recorderState.saveFailed) {
        if (recorderState.saveFailed) recordingError = true
    }

    LaunchedEffect(midiConnected) {
        // Подключение не должно потерять уже начатый дубль.
        if (midiConnected && recorderState.isRecording) {
            recorderState = performanceRecorder.stop()
        }
    }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    val focusManager = LocalFocusManager.current
    LaunchedEffect(showSettings) {
        // Панель остаётся в композиции за краем экрана, но её ввод не должен
        // сохранять фокус и экранную клавиатуру после закрытия.
        if (!showSettings) focusManager.clearFocus()
    }
    val dismissDistance = with(LocalDensity.current) { 72.dp.toPx() }
    Box(modifier = Modifier.fillMaxSize().pointerInput(showSettings, dismissDistance) {
        if (!showSettings) return@pointerInput
        awaitEachGesture {
            // Main runs after child controls: a consumed slider drag cannot close settings.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            val gesture = SettingsDismissGesture(viewConfiguration.touchSlop, dismissDistance)
            if (currentEvent.changes.count { it.pressed } > 1) gesture.cancel()
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pointer = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.count { it.pressed } > 1 || pointer == null) gesture.cancel()
                if (pointer != null && pointer.pressed && gesture.update(
                        pointer.position.x - down.position.x,
                        pointer.position.y - down.position.y,
                        consumedByChild = pointer.isConsumed,
                    )) {
                    event.changes.forEach { it.consume() }
                    showSettings = false
                }
            } while (event.changes.any { it.pressed })
        }
    }) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(
                        if (inMultiWindow) WindowInsets.safeDrawing
                        else WindowInsets.safeDrawing.union(cornerInsets)
                    )
                    .padding(2.dp),
            ) {
                val layout = performanceLayout(maxWidth.value, maxHeight.value, inMultiWindow, displayedInterfaceScale)
                val density = LocalDensity.current
                val panelDensity = Density(density.density * layout.controlScale, density.fontScale)
                val bottomMapPadding = if (layout.mapBelowPiano && !inMultiWindow) {
                    Modifier.windowInsetsPadding(WindowInsets.mandatorySystemGestures.only(WindowInsetsSides.Bottom))
                } else Modifier

                Column(Modifier.fillMaxSize().then(bottomMapPadding)) {
                    CompositionLocalProvider(LocalDensity provides panelDensity) {
                        FixedPerformanceControls(
                            tunings = displayedTunings,
                            selectedTuning = effectiveConfiguration.tuningSystem,
                            scalaScales = scalaScales.filter { it.libraryId in visibleScalaIds }.let { visible ->
                                val playingScale = effectiveConfiguration.scalaScale
                                if (playingScale != null && visible.none { it.libraryId == playingScale.libraryId })
                                    listOf(playingScale) + visible else visible
                            },
                            selectedScale = effectiveConfiguration.scalaScale,
                            onScalaSelected = { scale ->
                                if (!isPlaying && !midiConnected) {
                                    scalaScale = scale
                                    tuningName = TuningSystem.SCALA.name
                                    userSettings.saveScalaLibrary(scalaScales, visibleScalaIds, scale)
                                }
                            },
                            midiConnected = midiConnected,
                            tonicPitchClass = effectiveConfiguration.tonicPitchClass,
                            tonicSpelling = effectiveConfiguration.tonicSpelling,
                            observedNote = observedNote,
                            compact = layout.mapBelowPiano,
                            referenceFrequencyHz = effectiveConfiguration.referenceFrequencyHz,
                            onTuningSelected = { if (!isPlaying && !midiConnected) tuningName = it.name },
                            onTonicSelected = { pitchClass, spelling ->
                                if (!isPlaying && !midiConnected) { tonicPitchClass = pitchClass; tonicSpelling = spelling }
                            },
                            // Настройки доступны во время проигрывания: открытие панели
                            // не отменяет transport job и не сбрасывает звучащие ноты.
                            onSettingsClick = { showSettings = true },
                            onExit = onExit,
                            tuningHighlighted = PlaybackHighlight.TUNING in playbackHighlights,
                            tonicHighlighted = PlaybackHighlight.TONIC in playbackHighlights,
                            referenceHighlighted = PlaybackHighlight.REFERENCE in playbackHighlights && !midiConnected,
                        )

                        // The first row has a 4 dp lower inset: together this makes the same 8 dp gap.
                        Spacer(Modifier.height(4.dp))
                        ControlStrip(
                            audioTimbre = effectiveConfiguration.timbre,
                            pianoAvailable = pianoAvailable,
                            timbreEnabled = !midiConnected || midiLocalSound,
                            onTimbreToggle = {
                                if (!isPlaying && (!midiConnected || midiLocalSound)) {
                                    audioTimbreName = when (audioTimbre) {
                                        AudioTimbre.PURE_TONE -> AudioTimbre.PIANO.name
                                        AudioTimbre.PIANO -> AudioTimbre.PURE_TONE.name
                                    }
                                }
                            },
                            timbreHighlighted = PlaybackHighlight.TIMBRE in playbackHighlights,
                            holdUntilNextChord = if (isPlaying) playbackPedalEnabled else holdUntilNextChord,
                            pedalHighlighted = playbackPedalHighlighted,
                            pedalInteractive = !isPlaying && !transportBusy,
                            onPedalToggle = {
                                if (!isPlaying) {
                                    holdUntilNextChord = !holdUntilNextChord
                                    recorderState = performanceRecorder.recordPedalEnabled(holdUntilNextChord)
                                    userSettings.saveHoldUntilNextChord(holdUntilNextChord)
                                }
                            },
                            observedNote = observedNote,
                            tonicPitchClass = effectiveConfiguration.tonicPitchClass,
                            tonicSpelling = effectiveConfiguration.tonicSpelling,
                            compact = layout.mapBelowPiano,
                            mapActiveNotes = displayedActiveNotes,
                            latchedNoteCount = if (isPlaying) playbackLatchedNotes.size else latchedNotes.size,
                            onClearLatches = { clearLatchesSignal += 1 },
                            recordingEnabled = !midiConnected,
                            recorderState = recorderState,
                            recordingElapsedNanos = recordingPositionNanos,
                            isPlaying = isPlaying,
                            playbackElapsedNanos = playbackPositionNanos,
                            playbackDurationNanos = playbackRecording?.durationNanos ?: 0L,
                            onRecordToggle = {
                                // Проверка сохраняется и в обработчике: Record не открывает
                                // библиотеку и не меняет запись во время воспроизведения.
                                if (!transportBusy && !isPlaying && recorderState.isRecording) {
                                    recorderState = performanceRecorder.stop()
                                } else if (!transportBusy && !isPlaying && !midiOutput.connected) {
                                    recorderState = performanceRecorder.start(
                                        configuration = currentConfiguration,
                                        initiallyActiveNotes = activeNotes,
                                        initiallyLatchedNotes = latchedNotes,
                                        initiallyPedalEnabled = holdUntilNextChord,
                                    )
                                }
                            },
                            onRecordRestart = {
                                if (!transportBusy && !isPlaying && recorderState.isRecording) {
                                    recorderState = performanceRecorder.restart(
                                        configuration = currentConfiguration,
                                        initiallyActiveNotes = activeNotes,
                                        initiallyLatchedNotes = latchedNotes,
                                        initiallyPedalEnabled = holdUntilNextChord,
                                    )
                                }
                            },
                            onPlayToggle = {
                                if (!transportBusy) {
                                    if (recorderState.isRecording) recorderState = performanceRecorder.stop()
                                    else if (isPlaying) stopPlayback() else startPlayback()
                                }
                            },
                            onOpenRecordings = {
                                if (!recorderState.isRecording && !transportBusy) showRecordings = true
                            },
                            keyboardViewport = keyboardViewport,
                            onKeyboardViewportChange = { keyboardViewport = it.clamped() },
                        )

                        // Match the visible 8 dp gap between toolbar rows at every UI scale.
                        Spacer(Modifier.height(8.dp))
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        PianoKeyboard(
                            viewport = keyboardViewport,
                            onNoteOn = { midiNote ->
                                // Сначала запускаем звук: работа рекордера не предшествует атаке.
                                audioEngine.noteOn(
                                    midiNote = midiNote,
                                    frequencyHz = tunedKeyboard[midiNote].frequencyHz,
                                )
                                // Общие ноты соседних аккордов повторяют атаку и в записи.
                                if (recorderState.isRecording) {
                                    recorderState = performanceRecorder.recordRetriggeredNote(midiNote)
                                }
                            },
                            onNoteOff = audioEngine::noteOff,
                            onActiveNotesChanged = {
                                activeNotes = it
                                if (!isPlaying) recorderState = performanceRecorder.recordActiveNotes(it)
                            },
                            onLatchedNotesChanged = {
                                latchedNotes = it
                                if (!isPlaying) recorderState = performanceRecorder.recordLatchedNotes(it)
                            },
                            latchGesturesEnabled = latchGesturesEnabled,
                            clearLatchesSignal = clearLatchesSignal,
                            holdUntilNextChord = holdUntilNextChord,
                            resetSignal = audioSessionGeneration + keyboardResetSignal,
                            inputEnabled = !isPlaying && !transportBusy,
                            externalActiveNotes = playbackNotes,
                            externalLatchedNotes = playbackLatchedNotes,
                        )
                    }

                    if (layout.mapBelowPiano) {
                        Spacer(Modifier.height(6.dp))
                        KeyboardNavigator(
                            viewport = keyboardViewport,
                            onViewportChange = { keyboardViewport = it.clamped() },
                            activeNotes = displayedActiveNotes,
                            modifier = Modifier.fillMaxWidth().height(layout.bottomMapHeightDp.dp),
                        )
                    }
                }
            }
        }

        if (showRecordings) {
            RecordingsDialog(
                recorder = performanceRecorder, settings = userSettings,
                pianoAvailable = pianoAvailable,
                retriggerHeldNotes = retriggerHeldNotesDuringPlayback,
                recordingEnabled = !midiConnected,
                playingId = playbackRecording?.createdAtEpochMillis?.takeUnless { playbackFromTrash },
                listState = recordingsListState,
                // Read time in the active row/drawing, rather than recomposing the entire recording list.
                playbackPositionNanos = playbackPositionNanos,
                transportBusy = transportBusy,
                onPlay = { id ->
                    if (playbackRecording?.createdAtEpochMillis == id && isPlaying) {
                        stopPlayback()
                    } else {
                    transportBusy = true
                    coroutineScope.launch {
                        try {
                            playbackJob?.cancelAndJoin()
                            recorderState = withContext(Dispatchers.IO) { performanceRecorder.select(id) }
                            startPlayback()
                        } catch (error: CancellationException) { throw error
                        } catch (_: Exception) { libraryError = true
                        } finally { transportBusy = false }
                    }
                    }
                },
                onRerecord = { id ->
                    if (!midiOutput.connected) {
                        showRecordings = false
                        transportBusy = true
                        coroutineScope.launch {
                            try {
                                playbackJob?.cancelAndJoin()
                                // Соединение может измениться во время отмены проигрывателя.
                                if (!midiOutput.connected) {
                                    activeNotes.forEach(audioEngine::noteOff)
                                    keyboardResetSignal += 1
                                    clearLatchesSignal += 1
                                    recorderState = performanceRecorder.start(currentConfiguration, replaceId = id,
                                        initiallyPedalEnabled = holdUntilNextChord)
                                }
                            } finally { transportBusy = false }
                        }
                    }
                },
                onDeleted = { id, state ->
                    if (playbackRecording?.createdAtEpochMillis == id) stopPlayback()
                    recorderState = state
                },
                onRestored = { recorderState = it },
                onDismiss = { showRecordings = false },
            )
        }
        if (showRecordingTrash) {
            RecordingTrashDialog(
                recorder = performanceRecorder,
                playingId = playbackRecording?.createdAtEpochMillis?.takeIf { playbackFromTrash },
                playbackPositionNanos = playbackPositionNanos,
                previewEnabled = !recorderState.isRecording,
                transportBusy = transportBusy,
                onPreview = { id ->
                    if (!recorderState.isRecording && !transportBusy) {
                        if (playbackFromTrash && playbackRecording?.createdAtEpochMillis == id && isPlaying) {
                            stopPlayback()
                        } else {
                            transportBusy = true
                            trashPreviewLoadJob = coroutineScope.launch {
                                try {
                                    val recording = withContext(Dispatchers.IO) {
                                        requireNotNull(performanceRecorder.loadTrash(id))
                                    }
                                    playbackJob?.cancelAndJoin()
                                    startPlayback(recording, fromTrash = true)
                                } catch (cancelled: CancellationException) { throw cancelled
                                } catch (_: Exception) { libraryError = true
                                } finally { transportBusy = false; trashPreviewLoadJob = null }
                            }
                        }
                    }
                },
                onRestored = { recorderState = it },
                onDismiss = {
                    trashPreviewLoadJob?.cancel()
                    if (playbackFromTrash) stopPlayback()
                    showRecordingTrash = false
                },
            )
        }
        if (libraryError) {
            AlertDialog(onDismissRequest = { libraryError = false },
                text = { Text(strings.text(R.string.selected_recording_open_failed)) },
                confirmButton = { TextButton(onClick = { libraryError = false }) { Text(strings.text(R.string.ok)) } })
        }
        if (recordingError) {
            AlertDialog(onDismissRequest = { recordingError = false },
                title = { Text(strings.text(R.string.recording_save_failed)) },
                text = { Text(strings.text(R.string.recording_save_hint)) },
                confirmButton = { TextButton(onClick = {
                    recorderState = performanceRecorder.retrySave()
                    recordingError = recorderState.saveFailed
                }) { Text(strings.text(R.string.retry)) } },
                dismissButton = { TextButton(onClick = { recordingError = false }) { Text(strings.text(R.string.close)) } })
        }

        SettingsDrawer(visible = showSettings, onDismiss = { showSettings = false }) {
            SettingsPanel(
                userSettings = userSettings,
                scalaScale = scalaScale,
                scalaScales = scalaScales,
                visibleScalaIds = visibleScalaIds,
                midiOutput = midiOutput,
                midiConnected = midiConnected,
                midiLocalSound = midiLocalSound,
                onMidiLocalSoundChanged = {
                    midiLocalSound = it
                    userSettings.saveMidiLocalSound(it)
                },
                onScalaImported = { scale ->
                    scalaScales = ScalaLibrary.add(scalaScales, scale)
                    scalaScale = scale
                    tuningName = TuningSystem.SCALA.name
                    applyTuningVisibility(visibleTunings + TuningSystem.SCALA, visibleScalaIds + scale.libraryId)
                },
                onScalaDeleted = { scale ->
                    scalaScales = scalaScales.filterNot { it.libraryId == scale.libraryId }
                    if (scalaScale?.libraryId == scale.libraryId) {
                        scalaScale = scalaScales.firstOrNull()
                        tuningName = TuningSystem.EQUAL_TEMPERAMENT.name
                    }
                    applyTuningVisibility(visibleTunings, visibleScalaIds - scale.libraryId)
                },
                onVisibilityChanged = ::applyTuningVisibility,
                isVisible = showSettings,
                visibleTunings = visibleTunings,
                referenceFrequencyHz = referenceFrequencyHz,
                retriggerHeldNotesDuringPlayback = retriggerHeldNotesDuringPlayback,
                interfaceScale = interfaceScale,
                onInterfaceScaleChanged = { interfaceScale = it },
                latchGesturesEnabled = latchGesturesEnabled,
                onLatchGesturesChanged = { latchGesturesEnabled = it; userSettings.saveLatchGesturesEnabled(it) },
                onOpenTrash = { showSettings = false; showRecordingTrash = true },
                onReferenceFrequencyChanged = {
                    referenceFrequencyHz = it
                    userSettings.saveReferenceFrequencyHz(it)
                },
                onRetriggerChanged = {
                    retriggerHeldNotesDuringPlayback = it
                    userSettings.saveRetriggerHeldNotesDuringPlayback(it)
                },
                cutoutInsets = WindowInsets.safeDrawing.union(cornerInsets),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Область имеет постоянную высоту. Поэтому появление тоники или изменённого A4
 * никогда не сдвигает клавиатуру и не разрушает мышечную память исполнителя.
 */
@Composable
private fun FixedPerformanceControls(
    midiConnected: Boolean,
    tunings: List<TuningSystem>,
    selectedTuning: TuningSystem,
    scalaScales: List<ScalaScale>,
    selectedScale: ScalaScale?,
    onScalaSelected: (ScalaScale) -> Unit,
    tonicPitchClass: Int,
    tonicSpelling: TonicSpelling?,
    observedNote: TunedNote?,
    compact: Boolean,
    referenceFrequencyHz: Double,
    onTuningSelected: (TuningSystem) -> Unit,
    onTonicSelected: (Int, TonicSpelling) -> Unit,
    onSettingsClick: () -> Unit,
    onExit: () -> Unit,
    tuningHighlighted: Boolean = false,
    tonicHighlighted: Boolean = false,
    referenceHighlighted: Boolean = false,
) {
    val strings = LocalAppStrings.current
    // Reserve the settings hit area independently of the measured tuning labels.
    // The note field adapts to the safe width instead of always taking 184 dp.
    val rowPadding = if (compact) 8.dp else 16.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = rowPadding)) {
        val noteWidth = (maxWidth * .20f).coerceIn(112.dp, 160.dp)
        Row(Modifier.fillMaxSize().padding(end = 82.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TonicSelector(tonicPitchClass, tonicSpelling, selectedTuning,
                enabled = !midiConnected && selectedTuning != TuningSystem.EQUAL_TEMPERAMENT,
                onSelected = onTonicSelected, highlighted = tonicHighlighted && !midiConnected)
            TuningSelector(tunings, selectedTuning, scalaScales, selectedScale,
                onScalaSelected, onTuningSelected, tuningHighlighted && !midiConnected,
                enabled = !midiConnected, modifier = Modifier.weight(1f))
            if (midiConnected) Text("MIDI", style = MaterialTheme.typography.labelSmall,
                maxLines = 1, softWrap = false)
            if (!midiConnected && (referenceFrequencyHz != TuningEngine.DEFAULT_REFERENCE_FREQUENCY_HZ || referenceHighlighted)) {
                Text(strings.text(R.string.reference_display, formatFrequency(referenceFrequencyHz)),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            if (!compact) {
                ActiveNoteControl(observedNote, tonicPitchClass, tonicSpelling, Modifier.width(noteWidth))
            }
        }
        // Pedal and unlock are 48 dp each with an 8 dp gap in the row below.
        // Put the gear over their midpoint while keeping its own clear touch area.
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 30.dp).size(44.dp), contentAlignment = Alignment.Center) {
            SettingsControl(onSettingsClick, onExit)
        }
    }
}

/** Две вертикальные точки: сверху чистый тон, снизу фортепиано. */
@Composable
private fun TimbreControl(timbre: AudioTimbre, enabled: Boolean, dimmed: Boolean,
    highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    val scale by animateFloatAsState(if (highlighted) 1.06f else 1f, tween(140), label = "timbre-highlight")
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .30f)
    // Тембр всегда имеет серый кнопочный фон; выбор обозначает только точка.
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .66f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (dimmed) .28f else if (!enabled) .66f else 1f)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = strings.text(R.string.switch_timbre);
                stateDescription = strings.text(R.string.timbre_state, strings.timbreTitle(timbre), timbre.ordinal + 1, AudioTimbre.entries.size) }) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.timbreTitle(timbre), maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AudioTimbre.entries.forEach { option ->
                    Canvas(Modifier.size(4.dp)) { drawCircle(if (option == timbre) activeColor else inactiveColor) }
                }
            }
        }
    }
}

@Composable
private fun ActiveNoteControl(note: TunedNote?, tonicPitchClass: Int, spelling: TonicSpelling?,
    modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    // Match iOS: note and frequency first, a quieter equal-temperament offset below.
    val title = remember(note, tonicPitchClass, spelling, strings) {
        if (note == null) strings.text(R.string.note_empty) else {
            val name = if (spelling != null && Math.floorMod(note.midiNote, 12) == tonicPitchClass) {
                TonicAnchors.name(tonicPitchClass, spelling) + (Math.floorDiv(note.midiNote, 12) - 1)
            } else TuningEngine.midiNoteName(note.midiNote)
            strings.text(R.string.note_frequency, name, note.frequencyHz)
        }
    }
    Box(modifier.height(48.dp).testTag("active-note"), contentAlignment = Alignment.CenterStart) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (note == null) .52f else 1f))
            if (note != null) Text(strings.text(R.string.note_offset_equal,
                if (kotlin.math.abs(note.deviationCents) < .005) 0.0 else note.deviationCents),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
        }
    }
}

/** Педаль рядом с общим замком; палитра остаётся общей с кнопками строя. */
@Composable
private fun PedalControl(selected: Boolean, interactive: Boolean, highlighted: Boolean, onClick: () -> Unit) {
    val strings = LocalAppStrings.current
    val scale by animateFloatAsState(if (highlighted) 1.06f else 1f, tween(140), label = "pedal-highlight")
    Surface(color = selectionContainerColor(selected), contentColor = selectionContentColor(selected),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp).testTag("pedal-control")
            .graphicsLayer { scaleX = scale; scaleY = scale }.clickable(enabled = interactive, onClick = onClick)
            .semantics { contentDescription = strings.text(R.string.pedal)
                stateDescription = if (selected) strings.text(R.string.enabled) else strings.text(R.string.disabled) }) {
        Box(contentAlignment = Alignment.Center) {
            PedalSymbol(Modifier.size(25.dp, 22.dp), selectionContentColor(selected))
        }
    }
}

/** Общая палитра выбора строя и педали: включённое состояние без ослабления цвета. */
@Composable
private fun selectionContainerColor(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)

@Composable
private fun selectionContentColor(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

@Composable
private fun TuningSelector(
    tunings: List<TuningSystem>,
    selected: TuningSystem,
    scalaScales: List<ScalaScale>,
    selectedScale: ScalaScale?,
    onScalaSelected: (ScalaScale) -> Unit,
    onSelected: (TuningSystem) -> Unit,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        val options = tunings.filter { it != TuningSystem.SCALA }.map { it to null } +
            scalaScales.map { TuningSystem.SCALA to it }
        options.forEach { (tuning, scale) ->
            val isSelected = tuning == selected && (scale == null || scale == selectedScale)
            val itemHighlighted = isSelected && highlighted
            val selectionScale by animateFloatAsState(
                targetValue = if (itemHighlighted) 1.06f else 1f,
                animationSpec = tween(140),
                label = "tuning-highlight",
            )
            val itemShape = RoundedCornerShape(12.dp)
            Surface(
                color = selectionContainerColor(isSelected),
                contentColor = selectionContentColor(isSelected),
                shape = itemShape,
                modifier = Modifier
                    .height(40.dp)
                    .graphicsLayer {
                        scaleX = selectionScale
                        scaleY = selectionScale
                    }
                    .alpha(if (enabled || isSelected) 1f else 0.28f)
                    .clickable(enabled = enabled) { if (scale == null) onSelected(tuning) else onScalaSelected(scale) },
            ) {
                // Center inside the complete button; text padding is not a vertical anchor.
                Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    text = scale?.title?.take(24) ?: strings.tuningShortTitle(tuning),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, softWrap = false,
                )
                }
            }
        }
    }
}

@Composable
private fun TonicSelector(selectedPitchClass: Int, selectedSpelling: TonicSpelling?, system: TuningSystem,
    enabled: Boolean, onSelected: (Int, TonicSpelling) -> Unit, highlighted: Boolean = false) {
    val strings = LocalAppStrings.current
    val separatePairs = TonicAnchors.distinguishesSpellings(system) && selectedSpelling != null
    var expanded by remember { mutableStateOf(false) }
    val title = TonicAnchors.name(selectedPitchClass, selectedSpelling ?: TonicSpelling.SHARP)
    // Bound the anchor itself, not only its child: the popup cannot widen the toolbar.
    Box(Modifier.width(56.dp).height(40.dp)) {
        Surface(color = selectionContainerColor(true), contentColor = selectionContentColor(true),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxSize()
                .alpha(if (enabled) 1f else .4f)
                .clickable(enabled = enabled) { expanded = true }
                .semantics { contentDescription = strings.text(R.string.tonic_heading); stateDescription = title }) {
            Box(contentAlignment = Alignment.Center) {
                Text("$title ▾", style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
        // Compact schematic rows replace the tall default list. Historic sharp/flat roots
        // remain separate, because they can have different frequencies.
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(12.dp), containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp) {
            TonicChoices(selectedPitchClass, selectedSpelling, separatePairs) { pitch, spelling ->
                onSelected(pitch, spelling)
                expanded = false
            }
        }
    }
}

/** Compact schematic rows: altered notes sit halfway between natural-note columns. */
@Composable
private fun TonicChoices(selectedPitchClass: Int, selectedSpelling: TonicSpelling?, separate: Boolean,
    onSelected: (Int, TonicSpelling) -> Unit) {
    val naturalPitches = listOf(0, 2, 4, 5, 7, 9, 11)
    val alteredPitches = listOf(1 to 0, 3 to 1, 6 to 3, 8 to 4, 10 to 5)
    val spellings = if (separate) TonicSpelling.entries.toList()
        else listOf(selectedSpelling ?: TonicSpelling.SHARP)
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            naturalPitches.forEach { pitch ->
                TonicChoice(pitch, selectedSpelling ?: TonicSpelling.SHARP, selectedPitchClass == pitch) {
                    onSelected(pitch, selectedSpelling ?: TonicSpelling.SHARP)
                }
            }
        }
        spellings.forEach { spelling ->
            Box(Modifier.width(276.dp).height(36.dp)) {
                alteredPitches.forEach { (pitch, column) ->
                    TonicChoice(pitch, spelling,
                        selectedPitchClass == pitch && (!separate || selectedSpelling == spelling),
                        Modifier.offset(x = (20 + column * 40).dp)) { onSelected(pitch, spelling) }
                }
            }
        }
    }
}

/** Shared cell styling keeps natural, sharp and flat choices visually consistent. */
@Composable
private fun TonicChoice(pitch: Int, spelling: TonicSpelling, selected: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(36.dp).clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) {
            Text(TonicAnchors.name(pitch, spelling), maxLines = 1, softWrap = false,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

private fun formatFrequency(value: Double): String =
    if (value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }

@Composable
private fun ControlStrip(
    audioTimbre: AudioTimbre,
    pianoAvailable: Boolean,
    timbreEnabled: Boolean,
    onTimbreToggle: () -> Unit,
    timbreHighlighted: Boolean,
    holdUntilNextChord: Boolean,
    onPedalToggle: () -> Unit,
    pedalHighlighted: Boolean,
    pedalInteractive: Boolean,
    observedNote: TunedNote?,
    tonicPitchClass: Int,
    tonicSpelling: TonicSpelling?,
    compact: Boolean,
    mapActiveNotes: Set<Int>,
    latchedNoteCount: Int,
    onClearLatches: () -> Unit,
    recordingEnabled: Boolean,
    recorderState: RecorderState,
    recordingElapsedNanos: () -> Long,
    isPlaying: Boolean,
    playbackElapsedNanos: () -> Long,
    playbackDurationNanos: Long,
    onRecordToggle: () -> Unit,
    onRecordRestart: () -> Unit,
    onPlayToggle: () -> Unit,
    onOpenRecordings: () -> Unit,
    keyboardViewport: KeyboardViewport,
    onKeyboardViewportChange: (KeyboardViewport) -> Unit,
) {
    val rowPadding = if (compact) 8.dp else 16.dp
    // Keep the gap between the pedal and lock aligned with the settings gear in both layouts.
    Row(Modifier.fillMaxWidth().padding(horizontal = rowPadding), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TimbreControl(audioTimbre, enabled = pianoAvailable && !isPlaying && timbreEnabled,
            dimmed = !timbreEnabled, highlighted = timbreHighlighted && timbreEnabled,
            onClick = onTimbreToggle, modifier = Modifier.width(if (compact) 104.dp else 112.dp))
        RecordingTransport(
            recordingEnabled = recordingEnabled,
            recorderState = recorderState,
            recordingElapsedNanos = recordingElapsedNanos,
            isPlaying = isPlaying,
            playbackElapsedNanos = playbackElapsedNanos,
            playbackDurationNanos = playbackDurationNanos,
            onRecordToggle = onRecordToggle,
            onRecordRestart = onRecordRestart,
            onPlayToggle = onPlayToggle,
            onOpenRecordings = onOpenRecordings,
            compact = compact,
            modifier = Modifier.width(if (compact) 136.dp else 146.dp),
        )
        if (compact) {
            ActiveNoteControl(observedNote, tonicPitchClass, tonicSpelling, Modifier.weight(1f))
        } else {
            KeyboardNavigator(
                viewport = keyboardViewport,
                onViewportChange = onKeyboardViewportChange,
                activeNotes = mapActiveNotes,
                modifier = Modifier.weight(1f).height(48.dp),
            )
        }
        PedalControl(holdUntilNextChord, pedalInteractive, pedalHighlighted, onPedalToggle)
        UnlockAllControl(enabled = latchedNoteCount >= 2 && !isPlaying, onClick = onClearLatches)
    }
}

/** Постоянный открытый замок не меняет геометрию панели при закреплении нот. */
@Composable
private fun UnlockAllControl(enabled: Boolean, onClick: () -> Unit) {
    val strings = LocalAppStrings.current
    val color = MaterialTheme.colorScheme.onSurface
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        shape = RoundedCornerShape(11.dp),
        modifier = Modifier
            .size(48.dp).testTag("unlock-control")
            .alpha(if (enabled) 1f else 0.28f)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = if (enabled) {
                    strings.text(R.string.clear_latches)
                } else {
                    strings.text(R.string.clear_latches_unavailable)
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(23.dp)) {
                val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                val bodyTop = size.height * 0.44f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.18f, bodyTop),
                    size = androidx.compose.ui.geometry.Size(
                        size.width * 0.64f,
                        size.height * 0.47f,
                    ),
                    cornerRadius = CornerRadius(2.5.dp.toPx()),
                    style = stroke,
                )
                drawPath(
                    path = Path().apply {
                        moveTo(size.width * 0.32f, bodyTop)
                        lineTo(size.width * 0.32f, size.height * 0.27f)
                        cubicTo(
                            size.width * 0.32f,
                            size.height * 0.02f,
                            size.width * 0.71f,
                            size.height * 0.02f,
                            size.width * 0.71f,
                            size.height * 0.27f,
                        )
                    },
                    color = color,
                    style = stroke,
                )
                drawCircle(
                    color = color,
                    radius = 1.5.dp.toPx(),
                    center = Offset(size.width * 0.5f, size.height * 0.64f),
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.5f, size.height * 0.68f),
                    end = Offset(size.width * 0.5f, size.height * 0.78f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun RecordingTransport(
    recordingEnabled: Boolean,
    recorderState: RecorderState,
    recordingElapsedNanos: () -> Long,
    isPlaying: Boolean,
    playbackElapsedNanos: () -> Long,
    playbackDurationNanos: Long,
    onRecordToggle: () -> Unit,
    onRecordRestart: () -> Unit,
    onPlayToggle: () -> Unit,
    onOpenRecordings: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val hasRecording = recorderState.lastRecording != null
    Surface(
        color = when {
            recorderState.isRecording -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.62f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
        },
        shape = RoundedCornerShape(11.dp),
        modifier = modifier.height(48.dp),
    ) {
        Box {
        if (isPlaying) PlaybackProgress(playbackElapsedNanos, playbackDurationNanos,
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = .78f), Modifier.matchParentSize())
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TransportButton(
                kind = if (recorderState.isRecording) TransportIcon.RERECORD else TransportIcon.RECORD,
                // При Play точка остаётся видимой, но не принимает нажатия.
                enabled = !isPlaying && (recordingEnabled || recorderState.isRecording),
                description = if (isPlaying) {
                    strings.text(R.string.recording_unavailable_playback)
                } else if (!recordingEnabled && !recorderState.isRecording) {
                    strings.text(R.string.recording_unavailable_midi)
                } else if (recorderState.isRecording) {
                    strings.text(R.string.restart_recording_unsaved)
                } else {
                    strings.text(R.string.start_recording)
                },
                disabledAlpha = 0.45f,
                onClick = if (recorderState.isRecording) onRecordRestart else onRecordToggle,
            )
            TransportButton(
                kind = if (isPlaying || recorderState.isRecording) TransportIcon.STOP else TransportIcon.PLAY,
                enabled = isPlaying || recorderState.isRecording || hasRecording,
                description = if (recorderState.isRecording) {
                    strings.text(R.string.stop_save_recording)
                } else if (isPlaying) {
                    strings.text(R.string.stop_playback)
                } else {
                    strings.text(R.string.play_selected_recording)
                },
                onClick = onPlayToggle,
            )
            Column(Modifier.fillMaxHeight()
                .clickable(enabled = !recorderState.isRecording, onClick = onOpenRecordings)
                .semantics { contentDescription = strings.text(R.string.open_recordings) },
                verticalArrangement = Arrangement.Center) {
                Text(strings.text(R.string.recordings), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                TransportTime(recorderState, isPlaying, recordingElapsedNanos, playbackElapsedNanos)
            }
        }
        }
    }
}

private enum class TransportIcon { RECORD, PLAY, STOP, RERECORD }

@Composable
private fun TransportButton(
    kind: TransportIcon,
    enabled: Boolean,
    description: String,
    disabledAlpha: Float = 0.3f,
    onClick: () -> Unit,
) {
    val iconColor = if (kind == TransportIcon.RECORD) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .alpha(if (enabled) 1f else disabledAlpha)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        if (kind == TransportIcon.RERECORD) {
            RecordingActionIcon(RecordingActionKind.RERECORD, modifier = Modifier.size(21.dp))
        } else Canvas(Modifier.size(15.dp)) {
            when (kind) {
                TransportIcon.RERECORD -> Unit // Нарисован общим знаком перезаписи выше.
                TransportIcon.RECORD -> drawCircle(color = iconColor)
                TransportIcon.STOP -> drawRect(
                    color = iconColor,
                    topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.68f, size.height * 0.68f),
                )
                TransportIcon.PLAY -> drawPath(
                    path = Path().apply {
                        moveTo(size.width * 0.22f, size.height * 0.08f)
                        lineTo(size.width * 0.88f, size.height * 0.5f)
                        lineTo(size.width * 0.22f, size.height * 0.92f)
                        close()
                    },
                    color = iconColor,
                )
            }
        }
    }
}

private fun formatDuration(nanos: Long): String {
    val totalSeconds = (nanos.coerceAtLeast(0L) / 1_000_000_000L)
    return "%d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
}


/** Updating a smooth fill must not recompose the toolbar, keyboard or settings. */
@Composable
private fun TransportTime(state: RecorderState, isPlaying: Boolean,
    recordingPosition: () -> Long, playbackPosition: () -> Long) {
    val seconds by remember(state, isPlaying, recordingPosition, playbackPosition) {
        derivedStateOf {
            (when {
                state.isRecording -> recordingPosition()
                isPlaying -> playbackPosition()
                else -> state.lastRecording?.durationNanos ?: 0L
            }) / 1_000_000_000L
        }
    }
    Text(formatDuration(seconds * 1_000_000_000L), fontWeight = FontWeight.Medium,
        maxLines = 1, style = MaterialTheme.typography.bodySmall)
}
