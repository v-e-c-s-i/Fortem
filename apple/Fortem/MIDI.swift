// SPDX-License-Identifier: GPL-3.0-only
import CoreMIDI
import Foundation
import Combine

struct MIDIDestination: Identifiable, Equatable {
    let id: MIDIEndpointRef
    let title: String
}

// Port commands use one serial worker queue, never the audio render callback.
// Its ledger sends Note Off for every note owned by Fortem before changing ports.
final class MIDITransport {
    let client: MIDIClientRef
    let port: MIDIPortRef
    private let queue = DispatchQueue(label: "app.fortem.midi", qos: .userInteractive)
    private var endpoint: MIDIEndpointRef = 0
    private var notes: Set<Int> = []
    var onFailure: (() -> Void)?
    init(notify: @escaping () -> Void) throws {
        var nextClient: MIDIClientRef = 0, nextPort: MIDIPortRef = 0
        guard MIDIClientCreateWithBlock("Fortem" as CFString, &nextClient, { _ in notify() }) == noErr else { throw FortemError.midiUnavailable }
        guard MIDIOutputPortCreate(nextClient, "Fortem output" as CFString, &nextPort) == noErr else {
            MIDIClientDispose(nextClient); throw FortemError.midiUnavailable
        }
        client = nextClient; port = nextPort
    }
    deinit { MIDIPortDispose(port); MIDIClientDispose(client) }
    func connect(_ destination: MIDIEndpointRef) {
        queue.async { [self] in
            for note in notes { _ = FortemSendMIDI(port, endpoint, 0x80, UInt8(note), 0) }
            notes.removeAll(); endpoint = destination
        }
    }
    func on(_ note: Int, velocity: Float = 0.8) {
        guard (0...127).contains(note), velocity.isFinite else { return }
        queue.async { [self] in
            guard endpoint != 0 else { return }
            if notes.contains(note) { _ = FortemSendMIDI(port, endpoint, 0x80, UInt8(note), 0) }
            let strength = UInt8(max(1, min(127, Int((velocity * 127).rounded()))))
            if FortemSendMIDI(port, endpoint, 0x90, UInt8(note), strength) == noErr { notes.insert(note) }
            else { onFailure?() }
        }
    }
    func off(_ note: Int) {
        queue.async { [self] in
            guard endpoint != 0, notes.remove(note) != nil else { return }
            if FortemSendMIDI(port, endpoint, 0x80, UInt8(note), 0) != noErr { onFailure?() }
        }
    }
    func silence() {
        queue.async { [self] in
            for note in notes { _ = FortemSendMIDI(port, endpoint, 0x80, UInt8(note), 0) }
            notes.removeAll()
        }
    }
}

@MainActor final class MIDIOutput: ObservableObject {
    @Published private(set) var destinations: [MIDIDestination] = []
    @Published private(set) var selected: MIDIEndpointRef = 0
    @Published private(set) var available = false
    var onChanged: (() -> Void)?
    var onFailure: (() -> Void)?
    private var transport: MIDITransport?
    var connected: Bool { selected != 0 }
    init() {
        do {
            transport = try MIDITransport { [weak self] in Task { @MainActor [weak self] in self?.refresh() } }
            transport?.onFailure = { [weak self] in Task { @MainActor [weak self] in
                guard let self, self.connected else { return }
                self.select(0); self.onFailure?()
            } }
            available = true; refresh()
        } catch { available = false }
    }
    func refresh() {
        var next: [MIDIDestination] = []
        for index in 0..<MIDIGetNumberOfDestinations() {
            let endpoint = MIDIGetDestination(index)
            var name: Unmanaged<CFString>?
            let result = MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, &name)
            let title = result == noErr ? (name.map { $0.takeRetainedValue() as String } ?? "MIDI") : "MIDI"
            next.append(MIDIDestination(id: endpoint, title: title))
        }
        if next != destinations { destinations = next }
        if selected != 0 && !next.contains(where: { $0.id == selected }) { select(0) }
    }
    func select(_ endpoint: MIDIEndpointRef) {
        guard available, endpoint == 0 || destinations.contains(where: { $0.id == endpoint }), endpoint != selected else { return }
        transport?.connect(endpoint); selected = endpoint; onChanged?()
    }
    func on(_ note: Int) { transport?.on(note) }
    func off(_ note: Int) { transport?.off(note) }
    func silence() { transport?.silence() }
}
