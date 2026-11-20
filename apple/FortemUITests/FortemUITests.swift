// SPDX-License-Identifier: GPL-3.0-only
import XCTest

final class FortemUITests: XCTestCase {
    func testFirstLaunchSettingsAndRecordingControls() {
        let app = XCUIApplication()
        app.launchArguments = ["-language", "en", "-interfaceScale", "1", "-latchGesturesEnabled", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["Settings"].waitForExistence(timeout: 10))
        let landscape = expectation(for: NSPredicate { _, _ in app.frame.width > app.frame.height }, evaluatedWith: app)
        wait(for: [landscape], timeout: 10)
        XCTAssertFalse(app.buttons["Clear latched notes"].isEnabled)
        let piano = app.otherElements["Piano keyboard"]
        XCTAssertTrue(piano.exists)
        let window = app.windows.firstMatch.frame
        print("Layout metrics: application=\(app.frame), window=\(window), piano=\(piano.frame)")
        XCTAssertGreaterThan(piano.frame.height / window.height, 0.65, app.debugDescription)
        XCTAssertLessThan(app.buttons["Record"].frame.minX, app.buttons["Playback"].frame.minX)
        let map = app.otherElements["Keyboard range"]
        map.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.8)).tap()
        let lowRange = map.value as? String
        map.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.8)).tap()
        XCTAssertNotEqual(map.value as? String, lowRange)
        // Restore the middle range before the two independent latch gestures.
        map.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)).tap()
        app.buttons["Pythagorean"].tap()
        XCTAssertLessThan(app.buttons["Tonic"].frame.minX, app.buttons["Pythagorean"].frame.minX)
        app.buttons["Tonic"].tap()
        XCTAssertTrue(app.buttons["C"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.buttons["C"].frame.minY, app.buttons["D"].frame.minY, accuracy: 1)
        XCTAssertLessThan(app.buttons["B"].frame.maxX - app.buttons["C"].frame.minX, 320)
        app.buttons["C"].tap()
        XCTAssertEqual(app.buttons["Tonic"].value as? String, "C")
        // The pedal's empty margin must toggle too, not only the drawn contour.
        let pedal = app.buttons["Automatic pedal"]
        XCTAssertEqual(pedal.value as? String, "Off")
        pedal.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.12)).tap()
        XCTAssertEqual(pedal.value as? String, "On")
        pedal.coordinate(withNormalizedOffset: CGVector(dx: 0.88, dy: 0.12)).tap()
        XCTAssertEqual(pedal.value as? String, "Off")
        for x in [0.08, 0.22] {
            piano.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.9))
                .press(forDuration: 0.1, thenDragTo: piano.coordinate(withNormalizedOffset: CGVector(dx: x, dy: 0.25)))
        }
        XCTAssertTrue(app.buttons["Clear latched notes"].isEnabled)
        app.buttons["Clear latched notes"].tap()
        XCTAssertFalse(app.buttons["Clear latched notes"].isEnabled)
        attach("Keyboard landscape")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.buttons["Record"].exists)
        attach("Keyboard landscape")
        app.buttons["Record"].tap()
        XCTAssertTrue(app.buttons["Stop and save"].waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(app.buttons["Re-record"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Re-record"].tap()
        XCTAssertTrue(app.buttons["Stop and save"].exists)
        app.buttons["Stop and save"].tap()
        XCTAssertTrue(app.buttons["Record"].exists)
        XCUIDevice.shared.orientation = .portrait
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        app.buttons["Settings"].tap()
        XCTAssertTrue(app.buttons["settings-close"].waitForExistence(timeout: 5))
        attach("Settings")
        let settingsForm = app.collectionViews.firstMatch
        for _ in 0..<12 {
            if app.buttons["Pure tone"].firstMatch.exists && app.buttons["Pure tone"].firstMatch.isHittable { break }
            settingsForm.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                .press(forDuration: 0.1, thenDragTo: settingsForm.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.60)))
        }
        app.buttons["Pure tone"].firstMatch.tap()
        XCTAssertEqual(app.buttons["Pure tone"].firstMatch.value as? String, "Selected")
        app.buttons["Piano"].firstMatch.tap()
        XCTAssertEqual(app.buttons["Piano"].firstMatch.value as? String, "Selected")
        let sizing = app.sliders["Control size"]
        let formForSize = app.collectionViews.firstMatch
        for _ in 0..<8 {
            if sizing.exists && sizing.isHittable { break }
            formForSize.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
                .press(forDuration: 0.1, thenDragTo: formForSize.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55)))
        }
        XCTAssertTrue(sizing.isHittable)
        sizing.adjust(toNormalizedSliderPosition: 0)
        app.buttons["Reset control size"].tap()
        let tuning = app.buttons["Equal temperament (12-TET)"]
        let form = app.collectionViews.firstMatch
        for _ in 0..<12 {
            if tuning.exists && tuning.isHittable { break }
            // A full swipe skips several rows in a short landscape window.
            form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                .press(forDuration: 0.1, thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.60)))
        }
        XCTAssertTrue(tuning.isHittable, app.debugDescription)
        app.buttons["settings-close"].tap()
        XCTAssertTrue(app.buttons["Settings"].exists)
    }
    func testSettingsDrawerAndKeyboardFitBothLandscapeDirections() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-language", "en", "-interfaceScale", "1"]
        app.launch()
        let gear = app.buttons["Settings"]
        XCTAssertTrue(gear.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Reset control size"].exists)
        XCTAssertFalse(app.buttons["settings-close"].exists)
        let pedal = app.buttons["Automatic pedal"], unlock = app.buttons["Clear latched notes"]
        XCTAssertEqual(gear.frame.midX, (pedal.frame.midX + unlock.frame.midX) / 2, accuracy: 2)
        XCTAssertEqual(gear.frame.midY, app.buttons["Tonic"].frame.midY + 3, accuracy: 2)
        assertKeyboardFits(app)
        try savePreview("main")
        gear.tap()
        let close = app.buttons["settings-close"]
        let panel = app.descendants(matching: .any)["settings-panel"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        XCTAssertEqual(panel.frame.width, app.frame.width * 0.45, accuracy: 2)
        XCTAssertEqual(panel.frame.maxX, app.frame.maxX, accuracy: 2)
        XCTAssertEqual(panel.frame.height, app.frame.height, accuracy: 2)
        XCTAssertFalse(app.buttons["Done"].exists)
        try savePreview("settings-right")
        let slider = app.sliders["Control size"]
        let form = app.collectionViews.firstMatch
        for _ in 0..<5 { if slider.isHittable { break }; form.swipeUp() }
        XCTAssertTrue(slider.isHittable)
        slider.adjust(toNormalizedSliderPosition: 0.35)
        XCTAssertTrue(close.exists)
        app.buttons["Reset control size"].tap()
        let tone = app.buttons["Pure tone"].firstMatch
        for _ in 0..<12 {
            if tone.exists && tone.isHittable { break }
            // A full flick can skip the short Instrument section in a landscape drawer.
            form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                .press(forDuration: 0.1, thenDragTo: form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.60)))
        }
        XCTAssertTrue(tone.isHittable)
        tone.tap(); XCTAssertEqual(tone.value as? String, "Selected")
        let piano = app.buttons["Piano"].firstMatch
        piano.tap(); XCTAssertEqual(piano.value as? String, "Selected")
        close.tap()
        XCTAssertFalse(close.waitForExistence(timeout: 1))
        XCTAssertFalse(app.buttons["Reset control size"].exists)
        XCUIDevice.shared.orientation = .landscapeLeft
        assertKeyboardFits(app)
        gear.tap()
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        XCTAssertEqual(panel.frame.width, app.frame.width * 0.45, accuracy: 2)
        XCTAssertEqual(panel.frame.maxX, app.frame.maxX, accuracy: 2)
        XCTAssertEqual(panel.frame.height, app.frame.height, accuracy: 2)
        try savePreview("settings-left")
        app.buttons["settings-scrim"].coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.5)).tap()
        XCTAssertFalse(close.waitForExistence(timeout: 1))
        assertKeyboardFits(app)
    }

    private func assertKeyboardFits(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let keyboard = app.otherElements["Piano keyboard"].frame
        let map = app.otherElements["Keyboard range"].frame
        let pedal = app.buttons["Automatic pedal"].frame
        let unlock = app.buttons["Clear latched notes"].frame
        let window = app.frame
        XCTAssertGreaterThan(keyboard.width, window.width * 0.82, file: file, line: line)
        XCTAssertGreaterThan(keyboard.height, window.height * 0.58, file: file, line: line)
        XCTAssertGreaterThanOrEqual(keyboard.minX, window.minX, file: file, line: line)
        XCTAssertLessThanOrEqual(keyboard.maxX, window.maxX, file: file, line: line)
        XCTAssertLessThanOrEqual(keyboard.maxY, window.maxY, file: file, line: line)
        XCTAssertEqual(unlock.maxX, keyboard.maxX - 8 * pedal.width / 44, accuracy: 2, file: file, line: line)
        XCTAssertEqual(map.maxX, pedal.minX - 8 * pedal.width / 44, accuracy: 2, file: file, line: line)
        XCTAssertLessThanOrEqual(map.maxY, keyboard.minY, file: file, line: line)
    }
    func testVersion31FullScreenRecordingsMenusUndoAndTrashRetention() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-language", "en", "-recordingsNewestFirst", "YES"]
        app.launch()
        XCTAssertTrue(app.buttons["Recordings"].waitForExistence(timeout: 10))
        // Create a real take through the UI; normal installs ship no test recordings.
        app.buttons["Record"].tap()
        XCTAssertTrue(app.buttons["Stop and save"].waitForExistence(timeout: 5))
        app.otherElements["Piano keyboard"].coordinate(withNormalizedOffset: CGVector(dx: 0.4, dy: 0.85)).press(forDuration: 0.3)
        app.buttons["Stop and save"].tap()
        app.buttons["Recordings"].tap()
        let close = app.buttons["recordings-close"]
        let sort = app.descendants(matching: .any).matching(identifier: "recordings-sort").firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(close.frame.minX - sort.frame.maxX, 8)
        let list = app.descendants(matching: .any).matching(identifier: "recording-list").firstMatch
        XCTAssertGreaterThan(list.frame.width, app.frame.width * 0.78)
        XCTAssertGreaterThan(list.frame.height, app.frame.height * 0.60)
        XCTAssertFalse(app.staticTexts["Hold re-record to start a new take. The previous take moves to trash after the new one is saved."].exists)
        let created = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "recording-delete-")).firstMatch
        XCTAssertTrue(created.waitForExistence(timeout: 5))
        let id = String(created.identifier.dropFirst("recording-delete-".count))
        let delete = app.buttons["recording-delete-" + id]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["recording-play-" + id].exists)
        let rename = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Rename")).firstMatch
        XCTAssertFalse(rename.exists)
        try savePreview("recordings")
        let more = app.descendants(matching: .any).matching(identifier: "recording-more-" + id).firstMatch
        more.tap()
        XCTAssertTrue(rename.waitForExistence(timeout: 3))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Save WAV to Files")).firstMatch.exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Share WAV")).firstMatch.exists)
        try savePreview("recording-menu")
        rename.tap()
        XCTAssertTrue(app.alerts.textFields.firstMatch.waitForExistence(timeout: 3))
        app.alerts.buttons["Cancel"].tap()
        let before = list.frame
        delete.tap()
        let undo = app.buttons["recording-undo"]
        XCTAssertTrue(undo.waitForExistence(timeout: 5))
        XCTAssertTrue(close.isEnabled); XCTAssertTrue(sort.isEnabled)
        XCTAssertEqual(list.frame, before)
        undo.tap()
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()
        XCTAssertTrue(undo.waitForExistence(timeout: 5))
        app.buttons["recordings-trash"].tap()
        let retention = app.staticTexts["trash-retention-" + id]
        XCTAssertTrue(retention.waitForExistence(timeout: 3))
        XCTAssertTrue(retention.label.contains("30"))
        app.buttons["recording-restore-" + id].tap()
        app.buttons["recordings-trash"].tap()
        let last = app.buttons["recording-play-" + id]
        for _ in 0..<12 { if last.exists && last.isHittable { break }; list.swipeUp() }
        XCTAssertTrue(last.isHittable)
        close.tap()
        XCTAssertTrue(app.buttons["Recordings"].waitForExistence(timeout: 5))
        app.buttons["Recordings"].tap()
        XCTAssertTrue(app.buttons["recording-play-" + id].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["recording-play-" + id].isHittable)
    }
    private func savePreview(_ name: String) throws {
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: folder.appendingPathComponent("Fortem31-" + name + ".png"))
    }
    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}



