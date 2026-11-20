// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.midi

import app.fortem.R

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MidiDestination(val deviceId: Int, val portNumber: Int, val title: String)
data class MidiOutputState(
    val supported: Boolean,
    val destinations: List<MidiDestination> = emptyList(),
    val selected: MidiDestination? = null,
    val connecting: Boolean = false,
    val error: Int? = null,
    // Счётчик успешных подключений не теряется при слиянии обновлений StateFlow.
    val connectionGeneration: Long = 0,
)

/** Не блокирует UI/аудиопоток: открытие портов и передача работают в отдельной очереди. */
class MidiOutputEngine(context: Context) : Closeable, MidiNoteOutput {
    private val manager = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI))
        context.getSystemService(MidiManager::class.java) else null
    private val thread = HandlerThread("Fortem-MIDI").apply { start() }
    private val handler = Handler(thread.looper)
    private val mutableState = MutableStateFlow(MidiOutputState(manager != null))
    val state: StateFlow<MidiOutputState> = mutableState
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private var generation = 0
    private val notes = MidiNoteLedger()
    @Volatile private var closed = false
    @Volatile private var acceptingNotes = false

    override val connected: Boolean get() = acceptingNotes && !closed

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { refresh() }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (mutableState.value.selected?.deviceId == info.id) disconnectOnWorker()
            refresh()
        }
    }

    init {
        runCatching { manager?.registerDeviceCallback(callback, handler) }.onFailure {
            mutableState.value = mutableState.value.copy(error = R.string.midi_service_unavailable)
        }
        handler.post { refresh() }
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        if (closed) return
        val available = runCatching { manager?.devices.orEmpty() }.getOrElse {
            mutableState.value = mutableState.value.copy(error = R.string.midi_ports_failed)
            return
        }
        val destinations = available.flatMap { info ->
            val title = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI ${info.id}"
            info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }.map {
                MidiDestination(info.id, it.portNumber,
                    if (it.name.isNullOrBlank()) title else "$title · ${it.name}")
            }
        }.sortedBy { it.title }
        mutableState.value = mutableState.value.copy(destinations = destinations)
    }

    @Suppress("DEPRECATION")
    fun connect(destination: MidiDestination) {
        if (closed) return
        acceptingNotes = false
        handler.post {
            disconnectOnWorker()
            val info = runCatching { manager?.devices?.firstOrNull { it.id == destination.deviceId } }.getOrNull()
            if (info == null) {
                mutableState.value = mutableState.value.copy(error = R.string.midi_device_disconnected)
                return@post
            }
            val request = generation
            mutableState.value = mutableState.value.copy(selected = destination, connecting = true, error = null)
            try {
                requireNotNull(manager).openDevice(info, { opened ->
                    if (closed || request != generation) { runCatching { opened?.close() }; return@openDevice }
                    val input = runCatching { opened?.openInputPort(destination.portNumber) }.getOrNull()
                    if (opened == null || input == null) {
                        runCatching { opened?.close() }
                        mutableState.value = mutableState.value.copy(selected = null, connecting = false,
                            error = R.string.midi_port_unavailable)
                    } else {
                        device = opened
                        port = input
                        acceptingNotes = true
                        mutableState.value = mutableState.value.copy(connecting = false,
                            connectionGeneration = mutableState.value.connectionGeneration + 1)
                    }
                }, handler)
            } catch (_: Exception) {
                disconnectOnWorker()
                mutableState.value = mutableState.value.copy(error = R.string.midi_open_failed)
            }
        }
    }

    override fun noteOn(note: Int, velocity: Float) {
        if (!acceptingNotes || closed) return
        handler.post { if (acceptingNotes) send(notes.noteOn(note, velocity)) }
    }

    override fun noteOff(note: Int) {
        if (!acceptingNotes || closed) return
        handler.post { send(notes.noteOff(note)) }
    }

    fun releaseAll() { if (!closed) handler.post { send(notes.releaseAll()) } }

    fun disconnect() {
        acceptingNotes = false
        if (!closed) handler.post { disconnectOnWorker() }
    }

    private fun send(messages: List<ByteArray>) {
        val input = port ?: return
        try { messages.forEach { input.send(it, 0, it.size) } }
        catch (_: Exception) {
            // Не повторяем send при сбое: локальное фортепиано продолжает работать.
            acceptingNotes = false
            notes.releaseAll()
            runCatching { port?.close() }; runCatching { device?.close() }
            port = null; device = null; generation += 1
            mutableState.value = mutableState.value.copy(selected = null, connecting = false,
                error = R.string.midi_disconnected)
        }
    }

    private fun disconnectOnWorker() {
        acceptingNotes = false
        generation += 1
        send(notes.releaseAll())
        runCatching { port?.close() }; runCatching { device?.close() }
        port = null; device = null
        mutableState.value = mutableState.value.copy(selected = null, connecting = false, error = null)
    }

    override fun close() {
        if (closed) return
        closed = true; acceptingNotes = false
        runCatching { manager?.unregisterDeviceCallback(callback) }
        handler.post { disconnectOnWorker(); thread.quitSafely() }
    }
}
