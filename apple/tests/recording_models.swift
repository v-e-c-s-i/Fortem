// SPDX-License-Identifier: GPL-3.0-only
import Foundation

// Exercise release migration using isolated fixtures, without starting audio.
@main struct CheckRecordingModels {
    static func main() throws {
        let config = InstrumentConfiguration()
        precondition(config.tuning == .equal && config.tonic == 0 && config.piano)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let storage = try PerformanceStorage(directory: folder)
        try storage.removeRetiredDemos()
        let initial = try storage.load()
        precondition(initial.isEmpty)
        let own = Performance(title: "My recording", duration: 1, initial: config, events: [
            PerformanceEvent(time: 0, kind: .noteOn, note: 60),
            PerformanceEvent(time: 1, kind: .noteOff, note: 60)
        ])
        var ownTrash = own; ownTrash.id = UUID(); ownTrash.deletedAt = Date()
        try storage.save(own); try storage.save(ownTrash)
        let slots = Array(1...6) + Array(101...106) + Array(201...210) + Array(301...315)
        for slot in slots {
            var demo = own
            demo.id = UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", slot))!
            if slot % 2 == 0 { demo.deletedAt = Date() }
            try storage.save(demo)
        }
        try Data("1".utf8).write(to: folder.appendingPathComponent("demos-v4.installed"))
        for _ in 0..<2 {
            try storage.removeRetiredDemos()
            let library = try storage.load()
            precondition(Set(library.map(\.id)) == Set([own.id, ownTrash.id]))
            precondition(library.first { $0.id == ownTrash.id }?.deletedAt == ownTrash.deletedAt)
        }
        var replacement = own; replacement.id = UUID(); replacement.title = "Replacement"
        try storage.save(replacement, draft: true)
        let recovered = try storage.load()
        precondition(recovered.filter { $0.id == replacement.id }.count == 1)
        precondition(recovered.first { $0.id == own.id }?.deletedAt == nil)
        let retired = try storage.saveReplacement(replacement, replacing: own)
        precondition(retired?.id == own.id && retired?.deletedAt != nil)
        print("Recording models OK: fresh defaults, empty storage, all four demo generations removed, personal takes and trash preserved, recovery and replacement work.")
    }
}
