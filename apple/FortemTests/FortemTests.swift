// SPDX-License-Identifier: GPL-3.0-only
import XCTest
import CoreMIDI
import UIKit
@testable import Fortem

final class FortemTests: XCTestCase {
    func testPerformanceWindowKeepsTheMapUsableWhenResized() {
        let wide = PerformanceWindowLayout(width: 760, height: 365, preferredScale: 1)
        XCTAssertFalse(wide.compact)
        XCTAssertGreaterThan(wide.mapWidth, 300)
        XCTAssertGreaterThan(wide.keyboardHeight, 240)

        let split = PerformanceWindowLayout(width: 360, height: 240, preferredScale: 1.2)
        XCTAssertTrue(split.compact)
        XCTAssertEqual(split.mapWidth, 360)
        XCTAssertEqual(split.mapY + split.mapHeight, 240)
        XCTAssertGreaterThanOrEqual(split.mapHeight, 50)
        XCTAssertGreaterThanOrEqual(split.keyboardHeight, 95)

        let portrait = PerformanceWindowLayout(width: 740, height: 1000, preferredScale: 1)
        XCTAssertTrue(portrait.compact)
        XCTAssertEqual(portrait.mapHeight, 64)
    }


    func testWindowProfilesKeepControlsInsideTheSafeArea() {
        // Point-sized windows cover compact, notched, home-button and tablet layouts.
        let profiles: [(CGFloat, CGFloat)] = [
            (568, 320), (667, 375), (736, 414), (724, 354),
            (746, 365), (808, 393), (906, 419), (360, 300), (1024, 748)
        ]
        for (width, height) in profiles {
            for preferred in [CGFloat(0.8), 1.0, 1.2] {
                let layout = PerformanceWindowLayout(width: width, height: height, preferredScale: preferred)
                XCTAssertGreaterThanOrEqual(layout.keyboardHeight, min(128, height * 0.4) - 0.01)
                XCTAssertGreaterThan(layout.mapWidth, 0)
                XCTAssertLessThanOrEqual(layout.mapX + layout.mapWidth, width)
                XCTAssertLessThanOrEqual(layout.mapY + layout.mapHeight, height)
                XCTAssertGreaterThanOrEqual(layout.tuningWidth, 64 * layout.scale)
            }
        }
        for width in [CGFloat(667), 812, 844, 932] {
            XCTAssertEqual(PerformanceWindowLayout.drawerWidth(window: width, trailing: 44),
                           width * 0.45, accuracy: 0.01)
        }
    }

    @MainActor func testPlaybackIdentityUsesThePlayingTakeAfterLibrarySelectionChanges() throws {
        let instrument = Instrument()
        defer { instrument.stopPlayback() }
        let demos = [testTake("First"), testTake("Second")] 
        instrument.play(demos[0])
        XCTAssertEqual(instrument.playbackID, demos[0].id)
        XCTAssertEqual(instrument.selected, demos[0].id)
        // Reopening the list or selecting a different item must not relabel the sound being played.
        instrument.selected = demos[1].id
        XCTAssertEqual(instrument.playbackID, demos[0].id)
        instrument.stopPlayback()
        XCTAssertNil(instrument.playbackID)
        instrument.play(demos[1])
        XCTAssertEqual(instrument.playbackID, demos[1].id)
    }
    @MainActor func testLongRecordingTitleStaysInsideItsAllocatedViewport() throws {
        let viewport = CGRect(x: 0, y: 0, width: 140, height: 28)
        let view = MarqueeRecordingLabel(frame: viewport)
        let longName = "Demo 10 · Mozart · Ah! vous dirai-je, maman · Piano"
        let font = UIFont.systemFont(ofSize: 17)
        view.configure(text: longName, font: font, reduceMotion: false)
        view.layoutIfNeeded()
        let label = try XCTUnwrap(view.subviews.compactMap { $0 as? UILabel }.first)
        XCTAssertEqual(view.frame, viewport)
        XCTAssertTrue(view.clipsToBounds)
        XCTAssertGreaterThan(label.bounds.width, viewport.width)
        XCTAssertEqual(view.accessibilityLabel, longName)
        // A normal playback redraw and a shorter title must not resize the row or its controls.
        view.configure(text: longName, font: font, reduceMotion: false)
        view.layoutIfNeeded()
        XCTAssertEqual(view.frame, viewport)
        view.configure(text: "Mario", font: font, reduceMotion: false)
        view.layoutIfNeeded()
        XCTAssertEqual(view.frame, viewport)
        XCTAssertLessThanOrEqual(label.bounds.width, viewport.width)
        // With reduced motion, text remains clipped to the same area and accessible in full.
        view.configure(text: longName, font: UIFont.systemFont(ofSize: 24), reduceMotion: true)
        view.layoutIfNeeded()
        XCTAssertEqual(view.frame, viewport)
        XCTAssertLessThanOrEqual(label.bounds.width, viewport.width)
        XCTAssertEqual(view.accessibilityLabel, longName)
    }
    @MainActor func testCancelledNavigationRollsBackOnlyItsOwnLatch() {
        let instrument = Instrument()
        let previous = instrument.latchGesturesEnabled
        defer { instrument.releaseAll(); instrument.setPedal(false); instrument.latchGesturesEnabled = previous }
        instrument.latchGesturesEnabled = true
        instrument.press(1, note: 60); instrument.latch(1); instrument.release(1)
        instrument.press(2, note: 64); instrument.latch(2)
        instrument.cancel(2, createdLatch: 64)
        XCTAssertEqual(instrument.latched, [60])
        XCTAssertEqual(instrument.sounding, [60])
        instrument.setPedal(true)
        instrument.press(3, note: 67); instrument.cancel(3, createdLatch: nil)
        XCTAssertFalse(instrument.sounding.contains(67))
        instrument.releaseAll(); instrument.setPedal(false)
    }
    @MainActor func testRecordingDeleteUndoAndRapidRequestsPreserveOtherTakes() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let storage = try PerformanceStorage(directory: folder)
        let first = Performance(title: "First", duration: 1, initial: InstrumentConfiguration())
        let second = Performance(title: "Second", duration: 1, initial: InstrumentConfiguration())
        try storage.save(first); try storage.save(second)
        let instrument = Instrument(storage: storage)
        instrument.moveToTrash(first); instrument.moveToTrash(second)
        for _ in 0..<200 {
            if !instrument.recordingMutationBusy { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertFalse(instrument.recordingMutationBusy)
        XCTAssertEqual(instrument.undoTrash?.id, first.id)
        XCTAssertEqual(instrument.visiblePerformances.map(\.id), [second.id])
        instrument.undoDeletion()
        for _ in 0..<200 {
            if !instrument.recordingMutationBusy { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertFalse(instrument.recordingMutationBusy)
        XCTAssertNil(instrument.undoTrash)
        XCTAssertEqual(Set(instrument.visiblePerformances.map(\.id)), [first.id, second.id])
        XCTAssertEqual(try storage.load().filter { $0.deletedAt == nil }.count, 2)
    }
    func testTrashRetentionShowsRemainingDaysAndNeverGoesNegative() {
        let deleted = Date(timeIntervalSince1970: 1_000_000)
        var item = Performance(title: "Retention", initial: InstrumentConfiguration())
        XCTAssertNil(item.trashDaysRemaining(at: deleted))
        item.deletedAt = deleted
        XCTAssertEqual(item.trashDaysRemaining(at: deleted), 30)
        XCTAssertEqual(item.trashDaysRemaining(at: deleted.addingTimeInterval(29 * 86400 + 1)), 1)
        XCTAssertEqual(item.trashDaysRemaining(at: deleted.addingTimeInterval(31 * 86400)), 0)
        XCTAssertEqual(item.trashDaysRemaining(at: deleted.addingTimeInterval(-86400)), 30)
    }
    private func testTake(_ title: String) -> Performance {
        Performance(title: title, duration: 5, initial: InstrumentConfiguration(), events: [
            PerformanceEvent(time: 0, kind: .noteOn, note: 60),
            PerformanceEvent(time: 5, kind: .noteOff, note: 60)
        ])
    }
    @MainActor func testFreshDefaultsAndSavedChoicesSurviveReload() throws {
        let defaults = UserDefaults.standard
        let keys = ["instrument", "latchGesturesEnabled"]
        let saved = keys.map { defaults.object(forKey: $0) }
        defer { for (key, value) in zip(keys, saved) {
            if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
        } }
        keys.forEach { defaults.removeObject(forKey: $0) }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let storage = try PerformanceStorage(directory: folder)
        let instrument = Instrument(storage: storage)
        XCTAssertEqual(instrument.config.tuning, .equal)
        XCTAssertEqual(instrument.config.tonic, 0)
        XCTAssertTrue(instrument.config.piano)
        XCTAssertFalse(instrument.latchGesturesEnabled)
        XCTAssertTrue(instrument.performances.isEmpty)
        XCTAssertNil(Bundle.main.url(forResource: "fortem-demos", withExtension: "json"))
        instrument.config.tuning = .pythagorean
        instrument.config.tonic = 7
        instrument.config.piano = false
        instrument.latchGesturesEnabled = true
        let reloaded = Instrument(storage: storage)
        XCTAssertEqual(reloaded.config.tuning, .pythagorean)
        XCTAssertEqual(reloaded.config.tonic, 7)
        XCTAssertFalse(reloaded.config.piano)
        XCTAssertTrue(reloaded.latchGesturesEnabled)
    }
    @MainActor func testDisabledGestureLatchingStillAllowsThePedalToSustain() {
        let instrument = Instrument()
        let previous = instrument.latchGesturesEnabled
        defer { instrument.releaseAll(); instrument.setPedal(false); instrument.latchGesturesEnabled = previous }
        instrument.latchGesturesEnabled = false
        instrument.setPedal(true)
        instrument.press(1, note: 60); instrument.latch(1)
        XCTAssertTrue(instrument.latched.isEmpty)
        instrument.move(1, note: 61); instrument.release(1)
        XCTAssertEqual(instrument.sounding, [61])
        XCTAssertTrue(instrument.pedalEnabled)
    }
    @MainActor func testDisablingLatchesPreservesPedalNotesAndTheHeldFinger() {
        let instrument = Instrument()
        let previous = instrument.latchGesturesEnabled
        defer { instrument.releaseAll(); instrument.setPedal(false); instrument.latchGesturesEnabled = previous }
        instrument.latchGesturesEnabled = true
        instrument.press(1, note: 60); instrument.latch(1); instrument.release(1)
        instrument.setPedal(true)
        instrument.press(2, note: 64); instrument.release(2)
        instrument.press(3, note: 67); instrument.latch(3)
        instrument.latchGesturesEnabled = false
        XCTAssertTrue(instrument.latched.isEmpty)
        XCTAssertEqual(instrument.sounding, [64, 67])
        instrument.release(3)
        XCTAssertTrue(instrument.sounding.contains(67))
    }
    @MainActor func testPedalCanBeEnabledDuringANoteAndDisabledWithAnotherFingerHeld() {
        let instrument = Instrument()
        instrument.setPedal(false)
        instrument.press(1, note: 60)
        instrument.setPedal(true)
        instrument.release(1)
        XCTAssertEqual(instrument.sounding, [60])
        instrument.press(2, note: 64)
        instrument.setPedal(false)
        XCTAssertEqual(instrument.sounding, [64])
        instrument.release(2)
        XCTAssertTrue(instrument.sounding.isEmpty)
    }
    func testMapResizeMatchesAndroidAndKeepsItsCenter() {
        let original = PianoViewport(start: 20, count: 8)
        let wider = original.resizedAroundCenter(2)
        XCTAssertEqual(wider.count, 16)
        XCTAssertEqual(wider.start + wider.count / 2, 24)
        XCTAssertEqual(original.resizedAroundCenter(0.1).count, 4)
        XCTAssertEqual(original.resizedAroundCenter(10).count, 24)
        XCTAssertEqual(PianoViewport(start: 48, count: 8).clamped().start, 44)
        XCTAssertEqual(PianoViewport(start: -5, count: 8).clamped().start, 0)
        XCTAssertEqual(original.resizedAroundCenter(.nan).count, 8)
    }
    @MainActor func testClearLatchesPreservesHeldFingersAndPedal() {
        let instrument = Instrument()
        let previous = instrument.latchGesturesEnabled
        defer { instrument.latchGesturesEnabled = previous }
        instrument.latchGesturesEnabled = true
        XCTAssertFalse(instrument.canClearLatches)
        instrument.press(1, note: 60); instrument.latch(1); instrument.latch(1)
        instrument.release(1)
        XCTAssertEqual(instrument.latched, [60])
        XCTAssertEqual(instrument.sounding, [60])
        XCTAssertFalse(instrument.canClearLatches)
        instrument.press(2, note: 64); instrument.latch(2); instrument.release(2)
        XCTAssertTrue(instrument.canClearLatches)
        instrument.setPedal(true)
        instrument.press(3, note: 67); instrument.release(3)
        instrument.press(4, note: 69)
        instrument.clearLatches()
        XCTAssertTrue(instrument.latched.isEmpty)
        XCTAssertEqual(instrument.sounding, [67, 69])
        instrument.releaseAll(); instrument.setPedal(false)
    }
    func testAppRequiresLandscapeAndDarkAppearance() {
        // Bundle resolves device-qualified Info.plist keys for the current device.
        for key in ["UISupportedInterfaceOrientations"] {
            let orientations = Bundle.main.object(forInfoDictionaryKey: key) as? [String]
            XCTAssertEqual(Set(orientations ?? []), ["UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"])
        }
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "UIUserInterfaceStyle") as? String, "Dark")
    }
    func testEqualTemperamentAnchorsAndKeyboardRange() {
        let config = InstrumentConfiguration()
        XCTAssertEqual(config.frequency(21), 27.5, accuracy: 1e-10)
        XCTAssertEqual(config.frequency(69), 440, accuracy: 1e-10)
        XCTAssertEqual(config.frequency(108), 4186.009044809578, accuracy: 1e-9)
        XCTAssertEqual(PianoGeometry.whites.count, 52)
        XCTAssertEqual(PianoGeometry.whites.first, 21)
        XCTAssertEqual(PianoGeometry.whites.last, 108)
    }
    func testHistoricalRatiosAndEnharmonicDistinctions() {
        var config = InstrumentConfiguration()
        config.tuning = .just
        XCTAssertEqual(config.frequency(64) / config.frequency(60), 5.0/4, accuracy: 1e-12)
        config.tuning = .pythagorean
        XCTAssertEqual(config.frequency(67) / config.frequency(60), 3.0/2, accuracy: 1e-12)
        config.tonic = 1
        let sharp = config.frequency(61)
        config.flats = true
        XCTAssertEqual(config.frequency(61) / sharp, 524288.0/531441, accuracy: 1e-12)
        config.tuning = .meantone
        XCTAssertEqual(config.frequency(65) / config.frequency(61), 5.0/4, accuracy: 1e-12)
        for tuning in Tuning.builtIn {
            config.tuning = tuning
            for tonic in 0..<12 {
                config.tonic = tonic
                for note in 21...96 {
                    XCTAssertTrue(config.frequency(note).isFinite)
                    XCTAssertEqual(config.frequency(note + 12) / config.frequency(note), 2, accuracy: 1e-11)
                }
            }
        }
    }
    func testClosedFifthTemperamentsMatchKnownC4Intervals() {
        var config = InstrumentConfiguration()
        config.tuning = .werckmeister
        // Four quarter-Pythagorean-comma fifths: C-G and G-D are narrowed.
        XCTAssertEqual(1200 * log2(config.frequency(67) / config.frequency(60)), 696.089998269225, accuracy: 1e-8)
        XCTAssertEqual(1200 * log2(config.frequency(62) / config.frequency(60)), 192.17999653845, accuracy: 1e-8)
        config.tuning = .kirnberger
        XCTAssertEqual(config.frequency(64) / config.frequency(60), 5.0/4, accuracy: 1e-12)
    }
    func testScalaRatiosAreNotConfusedWithCents() throws {
        let source = "Just\n12\n16/15\n9/8\n6/5\n5/4\n4/3\n45/32\n3/2\n8/5\n5/3\n9/5\n15/8\n2\n"
        let scale = try ScalaScale.parse(source)
        XCTAssertEqual(scale.cents[4], 386.313713864835, accuracy: 1e-9)
        XCTAssertThrowsError(try ScalaScale.parse(source.replacingOccurrences(of: "\n2\n", with: "\n2.0\n")))
        XCTAssertThrowsError(try ScalaScale.parse(source.replacingOccurrences(of: "16/15", with: "-16/15")))
        XCTAssertThrowsError(try ScalaScale.parse(source.replacingOccurrences(of: "9/8", with: "1/1")))
    }
    func testPedalDeadlineIsNotPostponedByFastChords() {
        var pedal = ChordPedal(); pedal.enabled = true
        pedal.press(1, note: 60, now: 0); pedal.release(1)
        pedal.press(2, note: 64, now: 1); pedal.release(2)
        pedal.press(3, note: 67, now: 1.05); pedal.release(3)
        pedal.expire(1.151)
        XCTAssertFalse(pedal.notes.contains(60)); XCTAssertTrue(pedal.notes.contains(64))
        pedal.expire(1.201)
        XCTAssertEqual(pedal.notes, [67])
    }
    @MainActor func testHeldTouchRetargetsAndSharedKeyKeepsItsLastOwner() {
        let instrument = Instrument(); instrument.config = InstrumentConfiguration()
        instrument.press(1, note: 60); instrument.press(2, note: 60)
        instrument.release(1); XCTAssertEqual(instrument.sounding, [60])
        let bounds = CGRect(x: 0, y: 0, width: 900, height: 250)
        let point = CGPoint(x: 450, y: 200)
        let before = PianoGeometry.hit(point, start: 14, count: 18, bounds: bounds)!
        let after = PianoGeometry.hit(point, start: 20, count: 18, bounds: bounds)!
        XCTAssertNotEqual(before, after)
        instrument.move(2, note: before); instrument.move(2, note: after)
        XCTAssertEqual(instrument.sounding, [after])
        instrument.release(2); XCTAssertTrue(instrument.sounding.isEmpty)
    }
    @MainActor func testBothInstrumentsRemainAvailable() {
        let instrument = Instrument()
        XCTAssertTrue(instrument.audio.pianoAvailable, "The shared piano folder must be bundled intact")
        instrument.config.piano = false
        XCTAssertFalse(instrument.config.piano)
        instrument.config.piano = true
        XCTAssertTrue(instrument.config.piano)
    }
    func testRecoveryClosesHeldNotesAndRejectsUnorderedEvents() throws {
        var item = Performance(title: "Recovery", initial: InstrumentConfiguration())
        item.duration = 1
        item.events = [PerformanceEvent(time: 0, kind: .noteOn, note: 60)]
        item.closeHeldNotes()
        XCTAssertEqual(item.events.last?.kind, .noteOff)
        XCTAssertEqual(item.events.last?.time, 1)
        XCTAssertTrue(item.valid)
        let roundtrip = try JSONDecoder().decode(Performance.self, from: JSONEncoder().encode(item))
        XCTAssertTrue(roundtrip.valid)
        item.events.swapAt(0, 1); XCTAssertFalse(item.valid)
    }
    func testVerticalLatchDoesNotStealDiagonalGlissando() {
        XCTAssertEqual(KeyGesture.classify(.undecided, dx: 3, dy: -25, crossed: false, inside: true), .latch)
        XCTAssertEqual(KeyGesture.classify(.undecided, dx: 35, dy: -25, crossed: true, inside: false), .glissando)
        XCTAssertEqual(KeyGesture.classify(.latch, dx: 30, dy: -25, crossed: true, inside: false), .glissando)
    }
    @MainActor func testRecordingStartsWithHeldNotesAndRetakeKeepsThem() throws {
        let instrument = Instrument()
        instrument.press(1, note: 60); instrument.startRecording()
        let first = try XCTUnwrap(instrument.recording?.id)
        XCTAssertEqual(instrument.recording?.events.first?.note, 60)
        instrument.restartRecording()
        XCTAssertNotEqual(instrument.recording?.id, first)
        XCTAssertEqual(instrument.sounding, [60])
        XCTAssertEqual(instrument.recording?.events.first?.kind, .noteOn)
        instrument.release(1); instrument.stopRecording()
        if let item = instrument.performances.first(where: { $0.id == instrument.selected }) { instrument.deletePermanently(item) }
    }
    @MainActor func testVisibleTuningsAlwaysHaveOneChoice() {
        let instrument = Instrument()
        let original = instrument.visibleTunings
        for id in original { instrument.toggleVisibility(id) }
        XCTAssertEqual(instrument.visibleTunings.count, 1)
        for id in original.subtracting(instrument.visibleTunings) { instrument.toggleVisibility(id) }
        var config = InstrumentConfiguration(); config.reference = 300; XCTAssertTrue(config.valid)
        config.reference = 500; XCTAssertTrue(config.valid)
        config.reference = 501; XCTAssertFalse(config.valid)
    }
    @MainActor func testWAVExportLeavesFinishedFileInDocuments() throws {
        var config = InstrumentConfiguration(); config.piano = false
        var item = Performance(title: "Export test", initial: config)
        item.duration = 0.05
        item.events = [PerformanceEvent(time: 0, kind: .noteOn, note: 69), PerformanceEvent(time: 0.05, kind: .noteOff, note: 69)]
        let instrument = Instrument()
        let url = try WaveExporter.export(item, bank: instrument.audio.kernel)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertEqual(url.deletingLastPathComponent().lastPathComponent, "WAV")
        XCTAssertEqual(url.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent, "Documents")
        XCTAssertGreaterThan(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0, 44)
    }
    @MainActor func testMIDIOutputDeliversNotesAndRestoresOriginalTuning() async throws {
        var client: MIDIClientRef = 0, destination: MIDIEndpointRef = 0
        XCTAssertEqual(MIDIClientCreateWithBlock("Fortem test" as CFString, &client, nil), noErr)
        defer { MIDIClientDispose(client) }
        let on = expectation(description: "MIDI Note On")
        let off = expectation(description: "MIDI Note Off on disconnect")
        let result = MIDIDestinationCreateWithProtocol(client, "Fortem test destination" as CFString, ._1_0, &destination, { events, _ in
            let word = withUnsafeBytes(of: events.pointee.packet.words) { $0.load(as: UInt32.self) }
            if (word >> 8) & 0x7f == 60 {
                if (word >> 16) & 0xf0 == 0x90 { on.fulfill() }
                if (word >> 16) & 0xf0 == 0x80 { off.fulfill() }
            }
        })
        // iOS forbids creating virtual receivers without background-audio mode.
        // Fortem sends to existing destinations and stops on losing focus, so it
        // does not request an unrelated background capability just for a test.
        if result == -10844 { throw XCTSkip("Virtual receiver creation is restricted here; native MIDI delivery is checked on the macOS runner") }
        XCTAssertEqual(result, noErr)
        guard result == noErr else { return }
        defer { MIDIEndpointDispose(destination) }
        let instrument = Instrument()
        let original = instrument.config
        defer { instrument.releaseAll(); instrument.stopRecording(); instrument.config = original }
        instrument.config.tuning = .just; instrument.config.reference = 432
        instrument.midi.refresh(); instrument.midi.select(destination)
        XCTAssertTrue(instrument.midi.connected)
        XCTAssertEqual(instrument.effectiveConfiguration.frequency(69), 440, accuracy: 1e-10)
        instrument.startRecording(); XCTAssertNil(instrument.recording)
        instrument.press(1, note: 60)
        await fulfillment(of: [on], timeout: 3)
        instrument.midi.select(0)
        await fulfillment(of: [off], timeout: 3)
        XCTAssertEqual(instrument.config.tuning, .just)
        XCTAssertEqual(instrument.config.reference, 432)
    }
}



