// SPDX-License-Identifier: GPL-3.0-only
package app.fortem

import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.fortem.audio.NativeAudioEngine
import app.fortem.midi.MidiOutputEngine
import app.fortem.midi.MidiMirroringAudioEngine
import app.fortem.recording.AndroidMonotonicClock
import app.fortem.recording.AndroidPerformanceStorage
import app.fortem.recording.PerformanceRecorder
import app.fortem.settings.UserSettings
import app.fortem.ui.AppLanguageProvider
import app.fortem.ui.FortemScreen
import app.fortem.ui.displayCornerInsets
import app.fortem.ui.theme.FortemTheme

class MainActivity : ComponentActivity() {
    private val audioEngine = NativeAudioEngine()
    private var audioSessionGeneration by mutableIntStateOf(0)
    private lateinit var midiOutput: MidiOutputEngine
    private lateinit var mirroredAudio: MidiMirroringAudioEngine
    private lateinit var audioManager: AudioManager
    private lateinit var audioRouteState: AudioRouteState
    private lateinit var performanceRecorder: PerformanceRecorder
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioDeviceCallbackRegistered = false
    private var activityStarted = false
    private var inMultiWindow by mutableStateOf(false)
    private var cornerInsets by mutableStateOf(WindowInsets(0, 0, 0, 0))
    private val readDisplayCorners = ViewTreeObserver.OnGlobalLayoutListener {
        cornerInsets = window.decorView.displayCornerInsets(inMultiWindow)
    }

    private val windowSizeListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
        oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            refreshWindowLayout()
        }
    }

    private fun refreshWindowLayout() {
        val decor = window.decorView
        decor.post {
            if (!isDestroyed) {
                inMultiWindow = isInMultiWindowMode
                applyPerformanceOrientation(inMultiWindow)
                cornerInsets = decor.displayCornerInsets(inMultiWindow)
                ViewCompat.requestApplyInsets(decor)
                // A divider drag can grow the window without changing orientation.
                // Re-measure the Compose host with the new parent bounds in both directions.
                findViewById<View>(android.R.id.content)?.requestLayout()
            }
        }
    }

    // Bluetooth может сообщить о нескольких промежуточных устройствах подряд.
    // Короткая пауза даёт маршруту установиться перед созданием нового потока Oboe.
    private val restartAudioForRouteChange = Runnable {
        if (!activityStarted) return@Runnable

        val routeChanged = audioRouteState.update(currentOutputDeviceIds())
        if (!routeChanged && audioEngine.isRunning()) return@Runnable

        audioEngine.stop()
        midiOutput.releaseAll()
        audioSessionGeneration += 1
        audioEngine.start()
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { it.isSink }) scheduleAudioRouteRestart()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink }) scheduleAudioRouteRestart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Кнопки громкости управляют музыкальным, а не системным каналом уведомлений.
        volumeControlStream = AudioManager.STREAM_MUSIC
        midiOutput = MidiOutputEngine(applicationContext)
        mirroredAudio = MidiMirroringAudioEngine(audioEngine, midiOutput)
        audioManager = getSystemService(AudioManager::class.java)
        audioRouteState = AudioRouteState(currentOutputDeviceIds())
        performanceRecorder = PerformanceRecorder(
            storage = AndroidPerformanceStorage(applicationContext),
            clock = AndroidMonotonicClock,
        )
        enableEdgeToEdge()
        inMultiWindow = isInMultiWindowMode
        applyPerformanceOrientation(inMultiWindow)
        updateSystemBars()
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(readDisplayCorners)
        window.decorView.addOnLayoutChangeListener(windowSizeListener)

        // WAV открываются один раз до запуска звукового потока. Они остаются
        // внутри APK и не занимают дополнительное место во внутреннем хранилище.
        val pianoAvailable = audioEngine.loadPianoSamples(assets)

        setContent {
            val userSettings = remember { UserSettings(applicationContext) }

            var appLanguage by remember { mutableStateOf(userSettings.loadAppLanguage()) }

            // Переводы обновляются без перезапуска Activity и без сброса исполнения.
            AppLanguageProvider(appLanguage, onChanged = { language ->
                userSettings.saveAppLanguage(language)
                appLanguage = language
            }) {
                // Fortem использует одну спокойную тёмную тему без переключателя оформления.
                FortemTheme {
                    FortemScreen(
                        audioEngine = mirroredAudio,
                        onLocalOutputEnabled = audioEngine::setOutputEnabled,
                        midiOutput = midiOutput,
                        userSettings = userSettings,
                        audioSessionGeneration = audioSessionGeneration,
                        pianoAvailable = pianoAvailable,
                        performanceRecorder = performanceRecorder,
                        // Normal Activity shutdown runs onStop: save the take and release audio/MIDI.
                        onExit = { finishAndRemoveTask() },
                        inMultiWindow = inMultiWindow,
                        cornerInsets = cornerInsets,
                    )
                }
            }
        }
    }

    private fun updateSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (inMultiWindow) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Возврат из выбора файлов тоже возвращает полный игровой экран.
        if (hasFocus) updateSystemBars()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        inMultiWindow = isInMultiWindowMode
        applyPerformanceOrientation(inMultiWindow)
        updateSystemBars()
        refreshWindowLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        inMultiWindow = isInMultiWindowMode
        applyPerformanceOrientation(inMultiWindow)
        refreshWindowLayout()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        audioRouteState.reset(currentOutputDeviceIds())
        // Поток создаётся заранее, чтобы первое касание не платило цену «холодного» запуска.
        audioEngine.start()

        if (!audioDeviceCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            audioDeviceCallbackRegistered = true
        }
    }

    override fun onStop() {
        activityStarted = false
        mainHandler.removeCallbacks(restartAudioForRouteChange)
        if (audioDeviceCallbackRegistered) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallbackRegistered = false
        }
        // Входящий звонок или сворачивание завершают сессию, но не теряют её.
        runCatching { performanceRecorder.stop(completeReplacement = false) }
        // В фоне приложение не должно продолжать звучать и удерживать аудиоустройство.
        audioEngine.stop()
        midiOutput.disconnect()
        // Закреплённые ноты не должны остаться беззвучно отмеченными после возврата.
        audioSessionGeneration += 1
        super.onStop()
    }

    override fun onDestroy() {
        window.decorView.removeOnLayoutChangeListener(windowSizeListener)
        window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(readDisplayCorners)
        midiOutput.close()
        super.onDestroy()
    }

    private fun scheduleAudioRouteRestart() {
        if (!activityStarted) return
        mainHandler.removeCallbacks(restartAudioForRouteChange)
        mainHandler.postDelayed(restartAudioForRouteChange, AUDIO_ROUTE_SETTLE_DELAY_MS)
    }

    private fun currentOutputDeviceIds(): Set<Int> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .asSequence()
            .filter { it.isSink }
            .map { it.id }
            .toSet()

    private companion object {
        const val AUDIO_ROUTE_SETTLE_DELAY_MS = 400L
    }
}
