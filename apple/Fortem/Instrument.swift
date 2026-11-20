// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import QuartzCore
import Combine

struct ChordPedal {
    struct Contact { var note: Int?; var eligible = true }
    var enabled = false
    var contacts: [UInt64: Contact] = [:]
    var held: Set<Int> = []
    var transitioning: [Int: Double] = [:]
    var notes: Set<Int> { held.union(transitioning.keys) }
    mutating func press(_ pointer: UInt64, note: Int, now: Double) {
        if enabled && contacts.isEmpty {
            for old in held { transitioning[old] = now + 0.15 }; held.removeAll()
        }
        held.remove(note); transitioning.removeValue(forKey: note)
        contacts[pointer] = Contact(note: note)
    }
    mutating func move(_ pointer: UInt64, note: Int?) {
        guard contacts[pointer] != nil else { return }; contacts[pointer]?.note = note
        if let note { held.remove(note); transitioning.removeValue(forKey: note) }
    }
    mutating func release(_ pointer: UInt64) {
        if let contact = contacts.removeValue(forKey: pointer), enabled, contact.eligible, let note = contact.note { held.insert(note) }
    }
    mutating func expire(_ now: Double) {
        for (note, deadline) in transitioning where deadline <= now { transitioning.removeValue(forKey: note) }
    }
    mutating func clear() { contacts.removeAll(); held.removeAll(); transitioning.removeAll() }
}

@MainActor final class Instrument: ObservableObject {
    @Published var config: InstrumentConfiguration {
        didSet {
            guard config.valid else { config = oldValue; return }
            audio.setPiano(config.piano)
            for note in sounding { audio.retune(note, frequency: effectiveConfiguration.frequency(note)) }
            event(.configuration, configuration: config)
            if !playing, let data = try? JSONEncoder().encode(config) { UserDefaults.standard.set(data, forKey: "instrument") }
        }
    }
    // Scale only the control panels; keyboard range and musical gestures stay independent.
    @Published var interfaceScale: Double = 1 { didSet {
        UserDefaults.standard.set(interfaceScale, forKey: "interfaceScale")
    } }
    @Published var latchGesturesEnabled = false { didSet {
        UserDefaults.standard.set(latchGesturesEnabled, forKey: "latchGesturesEnabled")
        if !latchGesturesEnabled && !playing {
            for pointer in Array(pedal.contacts.keys) { pedal.contacts[pointer]?.eligible = true }
            clearLatches()
        }
    } }
    @Published var language: String { didSet { UserDefaults.standard.set(language, forKey: "language") } }
    @Published private(set) var sounding: Set<Int> = []
    @Published private(set) var observedNote: Int?
    @Published private(set) var latched: Set<Int> = []
    let keyboard = KeyboardViewport()
    @Published private(set) var pedalEnabled = false
    @Published private(set) var recording: Performance?
    @Published private(set) var playing = false
    @Published private(set) var elapsed = 0.0
    @Published var performances: [Performance] = []
    @Published var scales: [ScalaScale] = []
    @Published private(set) var visibleTunings: Set<String> = Set(Tuning.builtIn.map(\.rawValue))
    @Published var midiLocalSound = false { didSet {
        UserDefaults.standard.set(midiLocalSound, forKey: "midiLocalSound"); updateOutput()
    } }
    @Published var retriggerPlayback = false { didSet { UserDefaults.standard.set(retriggerPlayback, forKey: "retriggerPlayback") } }
    @Published var selected: UUID?
    @Published var error: String?
    @Published private(set) var recordingMutationBusy = false
    @Published private(set) var undoTrash: Performance?
    @Published private(set) var undoDeadline = 0.0
    private let recordingQueue = DispatchQueue(label: "app.fortem.recording-changes", qos: .userInitiated)
    private var undoTask: Task<Void, Never>?
    @Published var keyboardGeneration = 0
    let audio: AudioOutput
    let midi: MIDIOutput
    private var midiObservation: AnyCancellable?
    private var pointers: [UInt64: Int] = [:]
    private var pedal = ChordPedal()
    private var timer: Timer?
    private var storage: PerformanceStorage?
    private var replacement: Performance?
    private var recordingStart = 0.0
    private var lastDraftSave = 0.0
    private var replay: Performance?
    private var replayIndex = 0
    private var replayNotes: Set<Int> = []
    private var savedConfiguration: InstrumentConfiguration?
    private var savedPlaybackPedal = false
    private var playbackStart = 0.0
    var russian: Bool { language == "ru" || (language == "system" && Locale.preferredLanguages.first?.hasPrefix("ru") == true) }
    var visiblePerformances: [Performance] { performances.filter { $0.deletedAt == nil } }
    var currentNote: Int? { observedNote }
    // Every recording row observes the actual replay, including after reopening the library.
    var playbackID: UUID? { playing ? replay?.id : nil }
    // Use the actual playing take, including previews from the trash.
    var playbackProgress: Double {
        guard playing, let item = replay, item.duration > 0 else { return 0 }
        return min(1, max(0, elapsed / item.duration))
    }
    // The display samples this monotonic position on every frame; text clocks can update less often.
    var smoothPlaybackProgress: Double {
        guard playing, let replay, replay.duration > 0 else { return 0 }
        return min(1, max(0, (CACurrentMediaTime() - playbackStart) / replay.duration))
    }
    var effectiveConfiguration: InstrumentConfiguration {
        guard midi.connected else { return config }
        var next = config
        next.tuning = .equal; next.tonic = 0; next.flats = false; next.reference = 440; next.scala = nil
        return next
    }
    var tuningControlsEnabled: Bool { !playing && !midi.connected }
    var instrumentControlsEnabled: Bool { !playing && (!midi.connected || midiLocalSound) }

    init(storage suppliedStorage: PerformanceStorage? = nil) {
        let saved = UserDefaults.standard.data(forKey: "instrument").flatMap { try? JSONDecoder().decode(InstrumentConfiguration.self, from: $0) }
        config = saved?.valid == true ? saved! : InstrumentConfiguration()
        let savedScale = UserDefaults.standard.double(forKey: "interfaceScale")
        interfaceScale = savedScale.isFinite && (0.8...1.2).contains(savedScale) ? savedScale : 1
        let savedLanguage = UserDefaults.standard.string(forKey: "language")
        language = ["ru", "en"].contains(savedLanguage ?? "") ? savedLanguage! : "ru"
        audio = AudioOutput()
        midi = MIDIOutput()
        if !audio.pianoAvailable { config.piano = false }
        if let data = UserDefaults.standard.data(forKey: "scales"), let decoded = try? JSONDecoder().decode([ScalaScale].self, from: data) { scales = decoded.filter(\.valid) }
        if let ids = UserDefaults.standard.stringArray(forKey: "visibleTunings") { visibleTunings = Set(ids) }
        midiLocalSound = UserDefaults.standard.bool(forKey: "midiLocalSound")
        retriggerPlayback = UserDefaults.standard.bool(forKey: "retriggerPlayback")
        latchGesturesEnabled = UserDefaults.standard.object(forKey: "latchGesturesEnabled") as? Bool ?? false
        resolveVisibility()
        do {
            storage = try suppliedStorage ?? PerformanceStorage()
            try storage!.removeRetiredDemos()
            performances = try storage!.load()
        }
        catch { self.error = L("Не удалось открыть записи", "Could not open recordings") }
        audio.onInterruption = { [weak self] in self?.interrupted() }
        audio.onFailure = { [weak self] _ in self?.error = self?.L("Не удалось включить звук", "Could not start audio") }
        midi.onChanged = { [weak self] in self?.midiChanged() }
        midi.onFailure = { [weak self] in self?.error = self?.L("Соединение MIDI потеряно", "MIDI connection lost") }
        midiObservation = midi.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        timer = Timer(timeInterval: 0.01, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.tick() }
        }
        RunLoop.main.add(timer!, forMode: .common)
    }
    func L(_ ru: String, _ en: String) -> String { russian ? ru : en }
    func activate(_ foreground: Bool) {
        audio.foreground = foreground
        if foreground {
            do { try audio.start(); audio.setPiano(config.piano); updateOutput() }
            catch { self.error = L("Не удалось включить звук", "Could not start audio") }
        } else { interrupted(); audio.stop() }
    }
    func press(_ pointer: UInt64, note: Int) {
        guard !playing else { return }
        observedNote = note
        if pointers[pointer] != nil { release(pointer) }
        let reattack = pedal.notes.contains(note) && !latched.contains(note) && !pointers.values.contains(note)
        pedal.press(pointer, note: note, now: CACurrentMediaTime())
        pointers[pointer] = note; reconcile()
        if reattack { soundOn(note); event(.noteOn, note: note) }
    }
    func move(_ pointer: UInt64, note: Int?) {
        guard !playing, pointers[pointer] != note else { return }
        if let note { observedNote = note }
        pointers[pointer] = note; pedal.move(pointer, note: note); reconcile()
    }
    func release(_ pointer: UInt64) {
        pedal.release(pointer); pointers.removeValue(forKey: pointer); reconcile()
    }
    // A cancelled system gesture cannot create a new latch or a pedal-held note.
    // Latches which predate this touch remain intact.
    func cancel(_ pointer: UInt64, createdLatch: Int?) {
        pointers.removeValue(forKey: pointer); pedal.contacts.removeValue(forKey: pointer)
        if let note = createdLatch { latched.remove(note); event(.latch, notes: latched) }
        reconcile()
    }
    func latch(_ pointer: UInt64) {
        guard latchGesturesEnabled, !playing, let note = pointers[pointer] else { return }
        pedal.contacts[pointer]?.eligible = false
        latched.insert(note)
        event(.latch, notes: latched)
        reconcile()
    }
    var canClearLatches: Bool { !playing && latched.count >= 2 }
    func clearLatches() {
        latched.removeAll(); event(.latch, notes: []); reconcile()
    }
    func unlatch(_ note: Int) {
        latched.remove(note); event(.latch, notes: latched); reconcile()
    }
    func setPedal(_ value: Bool) {
        pedalEnabled = value; pedal.enabled = value
        event(.pedal, enabled: value)
        if !value { pedal.held.removeAll(); pedal.transitioning.removeAll(); reconcile() }
    }
    func releaseAll() {
        if playing { stopPlayback() }
        pointers.removeAll(); latched.removeAll(); pedal.clear(); keyboardGeneration += 1; reconcile()
        event(.latch, notes: [])
    }
    private func reconcile() {
        let next = Set(pointers.values).union(latched).union(pedal.notes)
        for note in sounding.subtracting(next) { soundOff(note); event(.noteOff, note: note) }
        for note in next.subtracting(sounding) { soundOn(note); event(.noteOn, note: note) }
        sounding = next
    }
    private func event(_ kind: PerformanceEvent.Kind, note: Int? = nil, configuration: InstrumentConfiguration? = nil, notes: Set<Int>? = nil, enabled: Bool? = nil) {
        guard recording != nil else { return }
        let time = max(0, CACurrentMediaTime() - recordingStart)
        recording!.duration = time
        recording!.events.append(PerformanceEvent(time: time, kind: kind, note: note, configuration: configuration, notes: notes, enabled: enabled))
    }
    func startRecording(replacing previous: Performance? = nil) {
        guard !midi.connected else { return }
        stopPlayback()
        guard storage != nil else { self.error = L("Хранилище записей недоступно", "Recording storage is unavailable"); return }
        replacement = previous
        recording = Performance(title: previous?.title ?? Date().formatted(date: .numeric, time: .shortened), initial: config)
        recordingStart = CACurrentMediaTime(); lastDraftSave = 0; elapsed = 0
        // A new take includes the notes already held when recording begins.
        for note in sounding.sorted() { event(.noteOn, note: note) }
        event(.latch, notes: latched); event(.pedal, enabled: pedalEnabled)
    }
    // Begin a replacement without touching the original saved take.
    @discardableResult func rerecord(_ item: Performance) -> Bool {
        guard recording == nil, !recordingMutationBusy, !midi.connected, storage != nil, item.deletedAt == nil else { return false }
        stopPlayback(); releaseAll()
        startRecording(replacing: item)
        return recording != nil
    }
    func restartRecording() {
        do { try storage?.discardDraft() }
        catch { self.error = L("Не удалось удалить черновик", "Could not discard draft"); return }
        let previous = replacement
        recording = nil
        startRecording(replacing: previous)
    }
    func stopRecording() {
        guard recording != nil else { return }
        recording!.duration = max(0, CACurrentMediaTime() - recordingStart)
        var item = recording!; item.closeHeldNotes()
        let hasNotes = item.events.contains(where: { $0.kind == .noteOn })
        do {
            if hasNotes {
                // Save the complete new take before moving its predecessor to recoverable trash.
                if let previous = try storage?.saveReplacement(item, replacing: replacement) {
                    if let index = performances.firstIndex(where: { $0.id == previous.id }) { performances[index] = previous }
                }
            }
            try storage?.discardDraft()
            if hasNotes {
                performances.removeAll { $0.id == item.id }
                performances.insert(item, at: 0); selected = item.id
            }
            recording = nil; replacement = nil
        } catch { self.error = L("Не удалось сохранить запись. Черновик сохранён для повторной попытки.", "Could not save recording. The draft is kept for retry.") }
    }
    func play(_ item: Performance) {
        guard recording == nil else { return }
        stopPlayback(); releaseAll()
        savedConfiguration = config; savedPlaybackPedal = pedalEnabled
        playing = true; config = item.initial
        selected = item.id
        replay = item; replayIndex = 0; playbackStart = CACurrentMediaTime(); elapsed = 0
    }
    func stopPlayback() {
        guard playing else { return }
        for note in replayNotes { soundOff(note) }
        replayNotes.removeAll(); sounding.removeAll(); latched.removeAll(); replay = nil; playing = false
        if let old = savedConfiguration { config = old }; savedConfiguration = nil
        pedalEnabled = savedPlaybackPedal
        resolveVisibility()
    }
    private func tick() {
        let now = CACurrentMediaTime()
        if !playing {
            let before = pedal.notes; pedal.expire(now)
            if before != pedal.notes { reconcile() }
        }
        if recording != nil {
            if now - recordingStart - elapsed > 0.08 { elapsed = now - recordingStart }
            if now - lastDraftSave >= 1 {
                do { recording!.duration = now - recordingStart; try storage?.save(recording!, draft: true); lastDraftSave = now }
                catch { recording = nil; releaseAll(); self.error = L("Запись остановлена: не удалось сохранить черновик", "Recording stopped: could not save draft") }
            }
        }
        guard let item = replay else { return }
        let playbackTime = now - playbackStart
        if playbackTime - elapsed > 0.08 { elapsed = playbackTime }
        while replayIndex < item.events.count && item.events[replayIndex].time <= playbackTime {
            let event = item.events[replayIndex]; replayIndex += 1
            switch event.kind {
            case .noteOn: let note = event.note!; replayNotes.insert(note); soundOn(note)
            case .noteOff: let note = event.note!; replayNotes.remove(note); soundOff(note)
            case .configuration:
                let old = config; config = event.configuration!
                let tuningChanged = old.tuning != config.tuning || old.tonic != config.tonic || old.flats != config.flats || old.scala != config.scala
                if retriggerPlayback && !midi.connected && old.piano == config.piano && tuningChanged {
                    for note in replayNotes { soundOn(note) }
                }
            case .latch: latched = event.notes ?? []
            case .pedal: pedalEnabled = event.enabled ?? false
            }
            sounding = replayNotes
        }
        if playbackTime >= item.duration { stopPlayback() }
    }
    private func interrupted() { if recording != nil { stopRecording() }; stopPlayback(); releaseAll(); midi.silence() }
    private func soundOn(_ note: Int) { observedNote = note; audio.on(note, frequency: effectiveConfiguration.frequency(note)); midi.on(note) }
    private func soundOff(_ note: Int) { audio.off(note); midi.off(note) }
    private func updateOutput() { FortemSetOutputEnabled(audio.kernel.pointer, !midi.connected || midiLocalSound) }
    private func midiChanged() {
        if midi.connected && recording != nil { stopRecording() }
        updateOutput()
        for note in sounding {
            audio.retune(note, frequency: effectiveConfiguration.frequency(note))
            if midi.connected { midi.on(note) }
        }
    }
    // Row operations never reload the list or block its header on disk I/O.
    // The command guard runs immediately, before SwiftUI disables any row controls.
    private func changeRecording(_ item: Performance, remove: Bool = false,
                                 completion: @escaping @MainActor @Sendable () -> Void = {}) {
        guard !recordingMutationBusy else { return }
        guard let storage else { error = L("Не удалось открыть записи", "Could not open recordings"); return }
        recordingMutationBusy = true
        recordingQueue.async { [self] in
            let result = Result { if remove { try storage.remove(item) } else { try storage.save(item) } }
            DispatchQueue.main.async { [self] in
                self.recordingMutationBusy = false
                switch result {
                case .success:
                    if remove { self.performances.removeAll { $0.id == item.id } }
                    else if let index = self.performances.firstIndex(where: { $0.id == item.id }) { self.performances[index] = item }
                    completion()
                case .failure: self.error = self.L("Не удалось сохранить изменения записи", "Could not save recording changes")
                }
            }
        }
    }
    func save(_ item: Performance) { changeRecording(item) }
    func moveToTrash(_ item: Performance) {
        var changed = item; changed.deletedAt = Date()
        changeRecording(changed) { [weak self] in
            guard let self else { return }
            if self.playbackID == item.id { self.stopPlayback() }
            self.undoTask?.cancel()
            self.undoTrash = item; self.undoDeadline = CACurrentMediaTime() + 5
            self.undoTask = Task { @MainActor [weak self] in
                do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
                guard !Task.isCancelled, self?.undoTrash?.id == item.id else { return }
                self?.undoTrash = nil
            }
        }
    }
    func undoDeletion() {
        guard !recordingMutationBusy, let item = undoTrash else { return }
        guard CACurrentMediaTime() < undoDeadline else { undoTrash = nil; return }
        changeRecording(item) { [weak self] in self?.undoTask?.cancel(); self?.undoTrash = nil }
    }
    func restore(_ item: Performance) {
        var changed = item; changed.deletedAt = nil; changeRecording(changed)
    }
    func deletePermanently(_ item: Performance) { changeRecording(item, remove: true) }
    func toggleVisibility(_ id: String) {
        if visibleTunings.contains(id) {
            guard visibleTunings.count > 1 else { return }; visibleTunings.remove(id)
        } else { visibleTunings.insert(id) }
        resolveVisibility(); UserDefaults.standard.set(Array(visibleTunings), forKey: "visibleTunings")
    }
    func scaleID(_ scale: ScalaScale) -> String { "scala." + scale.id.uuidString }
    func removeScale(_ scale: ScalaScale) {
        visibleTunings.remove(scaleID(scale)); scales.removeAll { $0.id == scale.id }
        resolveVisibility(); persistScales()
    }
    private func resolveVisibility() {
        let allowed = Set(Tuning.builtIn.map(\.rawValue) + scales.map(scaleID))
        visibleTunings.formIntersection(allowed)
        if visibleTunings.isEmpty { visibleTunings.insert(Tuning.equal.rawValue) }
        guard !playing else { return }
        let selectedID = config.tuning == .scala ? config.scala.map(scaleID) : config.tuning.rawValue
        guard selectedID == nil || !visibleTunings.contains(selectedID!) else { return }
        if let builtIn = Tuning.builtIn.first(where: { visibleTunings.contains($0.rawValue) }) { config.tuning = builtIn }
        else if let scale = scales.first(where: { visibleTunings.contains(scaleID($0)) }) { config.scala = scale; config.tuning = .scala }
    }
    private func persistScales() {
        if let data = try? JSONEncoder().encode(scales) { UserDefaults.standard.set(data, forKey: "scales") }
        UserDefaults.standard.set(Array(visibleTunings), forKey: "visibleTunings")
    }
    func importScale(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= 65536, scales.count < 100 else { throw FortemError.invalidScala }
            let scale = try ScalaScale.parse(String(contentsOf: url, encoding: .utf8))
            if let existing = scales.first(where: { $0.title == scale.title && $0.cents == scale.cents }) {
                visibleTunings.insert(scaleID(existing)); config.scala = existing; config.tuning = .scala; persistScales(); return
            }
            scales.append(scale)
            visibleTunings.insert(scaleID(scale)); persistScales()
            config.scala = scale; config.tuning = .scala
        } catch { self.error = L("Нужен корректный .scl: 12 ступеней, октава 2/1", "Use a valid .scl: 12 steps, octave 2/1") }
    }
}


