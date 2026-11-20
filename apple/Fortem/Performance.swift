// SPDX-License-Identifier: GPL-3.0-only
import Foundation

struct PerformanceEvent: Codable {
    enum Kind: String, Codable { case noteOn, noteOff, configuration, latch, pedal }
    var time: Double
    var kind: Kind
    var note: Int?
    var configuration: InstrumentConfiguration?
    var notes: Set<Int>?
    var enabled: Bool?
    var valid: Bool {
        guard time.isFinite && time >= 0 else { return false }
        switch kind {
        case .noteOn, .noteOff: return note.map { (21...108).contains($0) } == true
        case .configuration: return configuration?.valid == true
        case .latch: return notes?.allSatisfy { (21...108).contains($0) } == true
        case .pedal: return enabled != nil
        }
    }
}

struct Performance: Codable, Identifiable {
    var formatVersion = 1
    var id = UUID()
    var title: String
    var date = Date()
    var duration: Double = 0
    var initial: InstrumentConfiguration
    var events: [PerformanceEvent] = []
    var deletedAt: Date?
    var valid: Bool {
        formatVersion == 1 && title.count <= 240 && initial.valid && duration.isFinite && duration >= 0 &&
        events.count <= 500_000 && events.allSatisfy { $0.valid && $0.time <= duration } &&
        zip(events, events.dropFirst()).allSatisfy { $0.time <= $1.time }
    }
    func trashDaysRemaining(at now: Date = Date()) -> Int? {
        guard let deletedAt else { return nil }
        let remaining = max(0, 30 * 86400 - max(0, now.timeIntervalSince(deletedAt)))
        return Int(ceil(remaining / 86400))
    }
    mutating func closeHeldNotes() {
        var held: Set<Int> = []
        for event in events {
            if event.kind == .noteOn, let note = event.note { held.insert(note) }
            if event.kind == .noteOff, let note = event.note { held.remove(note) }
        }
        for note in held.sorted() { events.append(PerformanceEvent(time: duration, kind: .noteOff, note: note)) }
    }
}

final class PerformanceStorage: Sendable {
    private let directory: URL
    let draftURL: URL
    init(directory providedDirectory: URL? = nil) throws {
        // Tests use an isolated folder; the app keeps its existing Application Support location.
        if let providedDirectory { directory = providedDirectory }
        else {
            let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            directory = root.appendingPathComponent("Performances", isDirectory: true)
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        draftURL = directory.appendingPathComponent("draft.json")
    }
    func removeRetiredDemos() throws {
        // Reserved IDs identify only the shipped test takes, including their trashed copies.
        // Personal recordings, titles and dates are never used to decide what to remove.
        let slots = Array(1...6) + Array(101...106) + Array(201...210) + Array(301...315)
        for slot in slots {
            let name = String(format: "00000000-0000-4000-8000-%012d.json", slot)
            let url = directory.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        }
    }
    func load() throws -> [Performance] {
        var result: [Performance] = []
        for url in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) where url.pathExtension == "json" {
            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size <= 64 * 1024 * 1024 else { continue }
            // Leave damaged files on disk for recovery; one damaged file must not hide the library.
            guard var item = try? JSONDecoder().decode(Performance.self, from: Data(contentsOf: url)), item.valid else { continue }
            // Directory enumeration may return an equivalent URL with a different base URL.
            // Compare the reserved filename so a draft is recovered once, never listed as a second take.
            if url.lastPathComponent == draftURL.lastPathComponent {
                guard item.events.contains(where: { $0.kind == .noteOn }) else { try FileManager.default.removeItem(at: url); continue }
                let savedURL = directory.appendingPathComponent(item.id.uuidString + ".json")
                if FileManager.default.fileExists(atPath: savedURL.path) {
                    try FileManager.default.removeItem(at: url); continue
                }
                item.closeHeldNotes(); try save(item); try FileManager.default.removeItem(at: url)
            }
            if let deleted = item.deletedAt, Date().timeIntervalSince(deleted) >= 30 * 86400 {
                try remove(item); continue
            }
            result.append(item)
        }
        return result.sorted { $0.date > $1.date }
    }
    func save(_ item: Performance, draft: Bool = false) throws {
        guard item.valid else { throw FortemError.invalidRecording }
        let url = draft ? draftURL : directory.appendingPathComponent(item.id.uuidString + ".json")
        try JSONEncoder().encode(item).write(to: url, options: .atomic)
    }
    // Save the replacement first. Any later failure leaves both complete takes recoverable.
    func saveReplacement(_ item: Performance, replacing previous: Performance?) throws -> Performance? {
        try save(item)
        guard var previous, previous.id != item.id else { return nil }
        previous.deletedAt = Date()
        try save(previous)
        return previous
    }
    func discardDraft() throws {
        if FileManager.default.fileExists(atPath: draftURL.path) { try FileManager.default.removeItem(at: draftURL) }
    }
    func remove(_ item: Performance) throws {
        let url = directory.appendingPathComponent(item.id.uuidString + ".json")
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}

// Offline export has a separate voice pool and shares only the immutable piano bank.
// Runs on a worker queue; the live audio callback keeps its own render state.
enum WaveExporter {
    static func export(_ item: Performance, bank: RenderKernel) throws -> URL {
        guard item.valid, item.duration <= 4 * 3600 else { throw FortemError.invalidRecording }
        let synth = FortemCreate()!
        defer { FortemDestroy(synth) }
        FortemSharePiano(synth, bank.pointer)
        let rate = 48000
        FortemConfigure(synth, Double(rate))
        var config = item.initial
        FortemSetTimbre(synth, config.piano ? 1 : 0)
        var held: Set<Int> = []
        var index = 0, position = 0
        let frames = Int(ceil((item.duration + 1.1) * Double(rate)))
        let dataBytes = UInt32(frames * 4)
        // A completed export stays in Files > Fortem > WAV after sharing closes.
        let root = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let directory = root.appendingPathComponent("WAV", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let safeTitle = String(item.title.map { "/\\:\n\r".contains($0) ? "_" : $0 }.prefix(100))
        let url = directory.appendingPathComponent((safeTitle.isEmpty ? "Fortem" : safeTitle) + "-" + item.id.uuidString.prefix(8) + ".wav")
        // Publish only a complete file; interrupted exports leave no broken WAV.
        let temporary = directory.appendingPathComponent(UUID().uuidString + ".part")
        defer { try? FileManager.default.removeItem(at: temporary) }
        var header = Data()
        func text(_ value: String) { header.append(contentsOf: value.utf8) }
        func u16(_ value: UInt16) { var n = value.littleEndian; withUnsafeBytes(of: &n) { header.append(contentsOf: $0) } }
        func u32(_ value: UInt32) { var n = value.littleEndian; withUnsafeBytes(of: &n) { header.append(contentsOf: $0) } }
        text("RIFF"); u32(dataBytes + 36); text("WAVEfmt "); u32(16); u16(1); u16(2)
        u32(UInt32(rate)); u32(UInt32(rate * 4)); u16(4); u16(16); text("data"); u32(dataBytes)
        try header.write(to: temporary)
        let file = try FileHandle(forWritingTo: temporary)
        defer { try? file.close() }
        try file.seekToEnd()
        var samples = Array(repeating: Float(0), count: 1024)
        while position < frames {
            while index < item.events.count && Int((item.events[index].time * Double(rate)).rounded()) <= position {
                let event = item.events[index]; index += 1
                switch event.kind {
                case .noteOn:
                    let note = event.note!; held.insert(note)
                    FortemNoteOn(synth, Int32(note), Float(config.frequency(note)), 0.8)
                case .noteOff: held.remove(event.note!); FortemNoteOff(synth, Int32(event.note!))
                case .configuration:
                    config = event.configuration!; FortemSetTimbre(synth, config.piano ? 1 : 0)
                    for note in held { FortemRetune(synth, Int32(note), Float(config.frequency(note))) }
                case .latch, .pedal: break // Effective note events already contain the exact sustain duration.
                }
            }
            let next = index < item.events.count ? Int((item.events[index].time * Double(rate)).rounded()) : frames
            let count = min(512, frames - position, max(1, next - position))
            samples.withUnsafeMutableBufferPointer { FortemRender(synth, $0.baseAddress!, Int32(count)) }
            var bytes = Data(capacity: count * 4)
            for sample in samples.prefix(count * 2) {
                var value = Int16(max(-32768, min(32767, Int((sample * 32767).rounded())))).littleEndian
                withUnsafeBytes(of: &value) { bytes.append(contentsOf: $0) }
            }
            try file.write(contentsOf: bytes); position += count
        }
        try file.close()
        if FileManager.default.fileExists(atPath: url.path) {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: temporary)
        } else { try FileManager.default.moveItem(at: temporary, to: url) }
        return url
    }
}


