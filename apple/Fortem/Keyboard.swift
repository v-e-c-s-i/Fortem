// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import UIKit

// Shared vector outlines reproduce Android's two padlock drawings without SF Symbols.
enum PadlockArtwork {
    static func paths(in rect: CGRect, open: Bool) -> (outline: CGPath, keyhole: CGPath) {
        let outline = CGMutablePath(), keyhole = CGMutablePath()
        let unit = rect.width / 23
        if open {
            let bodyTop = rect.minY + rect.height * 0.44
            outline.addRoundedRect(in: CGRect(x: rect.minX + rect.width * 0.18, y: bodyTop,
                                             width: rect.width * 0.64, height: rect.height * 0.47),
                                   cornerWidth: 2.5 * unit, cornerHeight: 2.5 * unit)
            outline.move(to: CGPoint(x: rect.minX + rect.width * 0.32, y: bodyTop))
            outline.addLine(to: CGPoint(x: rect.minX + rect.width * 0.32, y: rect.minY + rect.height * 0.27))
            outline.addCurve(to: CGPoint(x: rect.minX + rect.width * 0.71, y: rect.minY + rect.height * 0.27),
                             control1: CGPoint(x: rect.minX + rect.width * 0.32, y: rect.minY + rect.height * 0.02),
                             control2: CGPoint(x: rect.minX + rect.width * 0.71, y: rect.minY + rect.height * 0.02))
            keyhole.addEllipse(in: CGRect(x: rect.midX - 1.5 * unit, y: rect.minY + rect.height * 0.64 - 1.5 * unit,
                                         width: 3 * unit, height: 3 * unit))
            keyhole.addRoundedRect(in: CGRect(x: rect.midX - 0.75 * unit, y: rect.minY + rect.height * 0.68,
                                             width: 1.5 * unit, height: rect.height * 0.10),
                                   cornerWidth: 0.75 * unit, cornerHeight: 0.75 * unit)
        } else {
            let bodyTop = rect.midY - unit
            outline.addRoundedRect(in: CGRect(x: rect.midX - 7 * unit, y: bodyTop, width: 14 * unit, height: 11 * unit),
                                   cornerWidth: 2 * unit, cornerHeight: 2 * unit)
            outline.move(to: CGPoint(x: rect.midX - 4 * unit, y: bodyTop))
            outline.addLine(to: CGPoint(x: rect.midX - 4 * unit, y: bodyTop - 4 * unit))
            outline.addCurve(to: CGPoint(x: rect.midX + 4 * unit, y: bodyTop - 4 * unit),
                             control1: CGPoint(x: rect.midX - 4 * unit, y: bodyTop - 9 * unit),
                             control2: CGPoint(x: rect.midX + 4 * unit, y: bodyTop - 9 * unit))
            outline.addLine(to: CGPoint(x: rect.midX + 4 * unit, y: bodyTop))
            keyhole.addEllipse(in: CGRect(x: rect.midX - 1.3 * unit, y: bodyTop + 2.9 * unit,
                                         width: 2.6 * unit, height: 2.6 * unit))
            keyhole.addRoundedRect(in: CGRect(x: rect.midX - 0.75 * unit, y: bodyTop + 5.2 * unit,
                                             width: 1.5 * unit, height: 2.4 * unit),
                                   cornerWidth: 0.75 * unit, cornerHeight: 0.75 * unit)
        }
        return (outline, keyhole)
    }
}

enum PianoGeometry {
    static let whites = (21...108).filter { !black($0) }
    private static let whiteIndices = (21...108).map { note in CGFloat(whites.filter { $0 < note }.count) }
    static func black(_ note: Int) -> Bool { [1,3,6,8,10].contains(note % 12) }
    static func rect(_ note: Int, start: CGFloat, count: CGFloat, bounds: CGRect) -> CGRect {
        let width = bounds.width / count
        let index = whiteIndices[note - 21]
        if black(note) {
            return CGRect(x: (index - start - 0.32) * width, y: 0, width: width * 0.64, height: bounds.height * 0.62)
        }
        return CGRect(x: (index - start) * width, y: 0, width: width, height: bounds.height)
    }
    static func hit(_ point: CGPoint, start: CGFloat, count: CGFloat, bounds: CGRect) -> Int? {
        guard bounds.contains(point) else { return nil }
        if point.y < bounds.height * 0.62 {
            for note in 21...108 where black(note) {
                if rect(note, start: start, count: count, bounds: bounds).contains(point) { return note }
            }
        }
        let index = Int(floor(start + point.x / bounds.width * count))
        return whites.indices.contains(index) ? whites[index] : nil
    }
}

struct PianoViewport {
    var start: CGFloat = CGFloat(PianoGeometry.whites.firstIndex(of: 60)!)
    var count: CGFloat = 8
    func clamped() -> PianoViewport {
        let span = max(4, min(24, count))
        return PianoViewport(start: max(0, min(52 - span, start)), count: span)
    }
    func resizedAroundCenter(_ scale: CGFloat) -> PianoViewport {
        guard scale.isFinite, scale > 0 else { return clamped() }
        let span = max(4, min(24, count * scale))
        return PianoViewport(start: start + (count - span) / 2, count: span).clamped()
    }
}

@MainActor final class KeyboardViewport {
    var range = PianoViewport()
    weak var piano: PianoView?
    weak var map: KeyboardMap?
    func apply(_ next: PianoViewport) {
        range = next.clamped()
        piano?.start = range.start; piano?.count = range.count; piano?.retarget()
        map?.start = range.start; map?.count = range.count; map?.setNeedsDisplay()
    }
}

// A mostly upward gesture latches; horizontal motion remains glissando. After
// a glissando, a new upward turn can latch without reusing the old displacement.
enum KeyGestureIntent { case undecided, latch, glissando }
enum KeyGesture {
    static func classify(_ current: KeyGestureIntent, dx: CGFloat, dy: CGFloat, crossed: Bool, inside: Bool) -> KeyGestureIntent {
        if current == .glissando { return current }
        let vertical = -dy > 8 && abs(dx) <= -dy * 0.58
        if current == .latch { return !inside || (abs(dx) > 8 && !vertical) ? .glissando : .latch }
        if !inside { return .glissando }
        if vertical { return .latch }
        if abs(dx) > 8 || (crossed && dy >= 0) { return .glissando }
        return .undecided
    }
}

// UIKit owns every touch independently. Map scrolling and keyboard playing can
// occur simultaneously; inertial frames retarget held touches synchronously.
@MainActor final class PianoView: UIView {
    struct Contact {
        var touch: UITouch; var id: UInt64; var origin: CGPoint; var note: Int
        var intent: KeyGestureIntent = .undecided; var latched = false; var createdLatch: Int?
    }
    var contacts: [ObjectIdentifier: Contact] = [:]
    var nextID: UInt64 = 0
    weak var instrument: Instrument?
    var start: CGFloat = CGFloat(PianoGeometry.whites.firstIndex(of: 60)!)
    var count: CGFloat = 8
    var generation = 0
    override init(frame: CGRect) {
        super.init(frame: frame); isMultipleTouchEnabled = true; isOpaque = true
        isAccessibilityElement = true
        clipsToBounds = true; layer.cornerRadius = 18
        accessibilityLabel = "Fortem piano"; accessibilityTraits = .allowsDirectInteraction
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func retarget() {
        guard let instrument else { return }
        for (key, var contact) in contacts where !contact.latched {
            let point = contact.touch.location(in: self)
            let note = PianoGeometry.hit(point, start: start, count: count, bounds: bounds)
            instrument.move(contact.id, note: note)
            if let note, note != contact.note {
                contact.note = note; contact.origin = point; contact.intent = .undecided
                contacts[key] = contact
            }
        }
        setNeedsDisplay()
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let instrument, !instrument.playing else { return }
        for touch in touches {
            // A swipe beginning in the Home-indicator strip belongs to system navigation.
            if let window, window.safeAreaInsets.bottom > 0,
               touch.location(in: window).y >= window.bounds.maxY - window.safeAreaInsets.bottom { continue }
            let point = touch.location(in: self)
            guard let note = PianoGeometry.hit(point, start: start, count: count, bounds: bounds) else { continue }
            if instrument.latched.contains(note) && lockRect(note).contains(point) {
                instrument.unlatch(note); continue
            }
            nextID += 1
            contacts[ObjectIdentifier(touch)] = Contact(touch: touch, id: nextID, origin: point, note: note)
            instrument.press(nextID, note: note)
        }
        setNeedsDisplay()
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let instrument else { return }
        for touch in touches {
            let key = ObjectIdentifier(touch)
            guard var contact = contacts[key] else { continue }
            let point = touch.location(in: self)
            if !instrument.latchGesturesEnabled { contact.latched = false }
            guard !contact.latched else { continue }
            let note = PianoGeometry.hit(point, start: start, count: count, bounds: bounds)
            let dx = point.x - contact.origin.x, dy = point.y - contact.origin.y
            let lane = PianoGeometry.rect(contact.note, start: start, count: count, bounds: bounds)
            let inside = point.x >= lane.minX && point.x <= lane.maxX
            if !instrument.latchGesturesEnabled {
                // A vertical slide remains ordinary playing when gesture latching is disabled.
                contact.intent = .glissando
                contact.origin = point
            } else if contact.intent == .glissando {
                if note == contact.note && inside && -dy > 8 && abs(dx) <= -dy * 0.42 {
                    contact.intent = .latch
                } else if note != contact.note || !inside || abs(dx) > 8 || abs(dy) > 8 {
                    contact.origin = point
                }
            } else {
                contact.intent = KeyGesture.classify(contact.intent, dx: dx, dy: dy, crossed: note != contact.note, inside: inside)
            }
            if contact.intent == .latch {
                if contact.origin.y - point.y > 40 {
                    let existed = instrument.latched.contains(contact.note)
                    instrument.latch(contact.id); contact.latched = true
                    contact.createdLatch = existed ? nil : contact.note
                }
            } else {
                instrument.move(contact.id, note: note)
                if let note { contact.note = note }
            }
            contacts[key] = contact
        }
        setNeedsDisplay()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { finish(touches) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            if let contact = contacts.removeValue(forKey: ObjectIdentifier(touch)) {
                instrument?.cancel(contact.id, createdLatch: contact.createdLatch)
            }
        }
        setNeedsDisplay()
    }
    private func finish(_ touches: Set<UITouch>) {
        for touch in touches {
            if let contact = contacts.removeValue(forKey: ObjectIdentifier(touch)) { instrument?.release(contact.id) }
        }
        setNeedsDisplay()
    }
    private func lockRect(_ note: Int) -> CGRect {
        let key = PianoGeometry.rect(note, start: start, count: count, bounds: bounds)
        let size = min(24, key.width * 0.8)
        return CGRect(x: key.midX - size / 2, y: key.maxY - size - 35, width: size, height: size)
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        UIColor.black.setFill(); context.fill(bounds)
        let sounding = instrument?.sounding ?? []
        let latched = instrument?.latched ?? []
        for isBlack in [false, true] {
            for note in 21...108 where PianoGeometry.black(note) == isBlack {
                let key = PianoGeometry.rect(note, start: start, count: count, bounds: bounds)
                guard key.intersects(bounds) else { continue }
                let color: UIColor = sounding.contains(note) ? (isBlack ? UIColor(red: 130/255, green: 213/255, blue: 195/255, alpha: 1) : UIColor(red: 0, green: 80/255, blue: 70/255, alpha: 1)) : (isBlack ? UIColor(red: 32/255, green: 37/255, blue: 35/255, alpha: 1) : UIColor(red: 217/255, green: 221/255, blue: 219/255, alpha: 1))
                color.setFill()
                UIBezierPath(roundedRect: key.insetBy(dx: isBlack ? 0 : 0.5, dy: 0), byRoundingCorners: [.bottomLeft, .bottomRight], cornerRadii: CGSize(width: isBlack ? 5 : 3, height: isBlack ? 5 : 3)).fill()
                // Match Android: black keys have no text labels.
                if !isBlack {
                    let title = instrument?.effectiveConfiguration.name(note) ?? ""
                    let font = UIFont.systemFont(ofSize: min(12, key.width * 0.25), weight: .medium)
                    let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: sounding.contains(note) ? (isBlack ? UIColor.black : UIColor(red: 158/255, green: 242/255, blue: 223/255, alpha: 1)) : (isBlack ? UIColor.white : UIColor.darkGray)]
                    let size = title.size(withAttributes: attrs)
                    title.draw(at: CGPoint(x: key.midX - size.width / 2, y: key.maxY - size.height - 12), withAttributes: attrs)
                }
                if latched.contains(note) {
                    // Match Android's outlined shackle, body and visible keyhole.
                    context.saveGState()
                    let icon = lockRect(note)
                    let paths = PadlockArtwork.paths(in: icon, open: false)
                    // Android uses dark ink on mint black keys and bright mint on dark white keys.
                    let color = isBlack ? UIColor(red: 0, green: 56/255, blue: 47/255, alpha: 1)
                        : UIColor(red: 158/255, green: 242/255, blue: 223/255, alpha: 1)
                    context.setStrokeColor(color.cgColor); context.setFillColor(color.cgColor)
                    context.setLineWidth(1.8 * icon.width / 23); context.setLineCap(.round)
                    context.addPath(paths.outline); context.strokePath()
                    context.addPath(paths.keyhole); context.fillPath()
                    context.restoreGState()
                }
            }
        }
    }
}

@MainActor final class KeyboardMap: UIView {
    weak var piano: PianoView?
    weak var viewport: KeyboardViewport?
    var start: CGFloat = CGFloat(PianoGeometry.whites.firstIndex(of: 60)!)
    var count: CGFloat = 8
    private var initialStart: CGFloat = 0
    private var initialCount: CGFloat = 0
    private var velocity: CGFloat = 0
    private var lastTime: CFTimeInterval = 0
    private var link: CADisplayLink?
    private var proxy: DisplayLinkProxy?
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .secondarySystemBackground
        clipsToBounds = true; layer.cornerRadius = 11
        let pan = UIPanGestureRecognizer(target: self, action: #selector(pan(_:)))
        pan.maximumNumberOfTouches = 1; addGestureRecognizer(pan)
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:)))
        addGestureRecognizer(pinch)
        let tap = UITapGestureRecognizer(target: self, action: #selector(tap(_:)))
        tap.require(toFail: pan); tap.require(toFail: pinch); addGestureRecognizer(tap)
        isAccessibilityElement = true; accessibilityTraits = .adjustable
        accessibilityLabel = "Keyboard range"
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func didMoveToWindow() { if window == nil { stopInertia() } }
    override func accessibilityIncrement() { start += 2; apply() }
    override func accessibilityDecrement() { start -= 2; apply() }
    func stopInertia() { link?.invalidate(); link = nil; proxy = nil; velocity = 0 }
    private func apply() {
        let next = PianoViewport(start: start, count: count).clamped()
        start = next.start; count = next.count
        viewport?.apply(next)
        if let piano { accessibilityValue = "\(piano.instrument?.effectiveConfiguration.name(PianoGeometry.whites[Int(start)]) ?? "")" }
    }
    @objc private func tap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended, bounds.width > 0 else { return }
        stopInertia()
        start = gesture.location(in: self).x / bounds.width * 52 - count / 2
        apply()
    }
    @objc private func pinch(_ gesture: UIPinchGestureRecognizer) {
        switch gesture.state {
        case .began:
            stopInertia(); initialStart = start; initialCount = count
        case .changed:
            // Like Android: spreading fingers expands the frame on the map.
            let next = PianoViewport(start: initialStart, count: initialCount).resizedAroundCenter(gesture.scale)
            start = next.start; count = next.count; apply()
        case .ended, .cancelled, .failed: stopInertia()
        default: break
        }
    }
    @objc private func pan(_ gesture: UIPanGestureRecognizer) {
        let scale = 52 / max(1, bounds.width)
        switch gesture.state {
        case .began:
            stopInertia()
            let x = gesture.location(in: self).x
            let left = start / 52 * bounds.width, right = (start + count) / 52 * bounds.width
            if x < left || x > right { start = x * scale - count / 2; apply() }
            initialStart = start
        case .changed:
            start = initialStart + gesture.translation(in: self).x * scale; apply()
        case .ended:
            velocity = gesture.velocity(in: self).x * scale
            if abs(velocity) > 0.5 {
                let proxy = DisplayLinkProxy(owner: self); self.proxy = proxy
                let link = CADisplayLink(target: proxy, selector: #selector(DisplayLinkProxy.frame(_:)))
                let rate = Float(window?.screen.maximumFramesPerSecond ?? 60)
                link.preferredFrameRateRange = CAFrameRateRange(minimum: min(60, rate), maximum: rate, preferred: rate)
                lastTime = 0; link.add(to: .main, forMode: .common); self.link = link
            }
        case .cancelled, .failed: stopInertia()
        default: break
        }
    }
    func frame(_ link: CADisplayLink) {
        if lastTime == 0 { lastTime = link.timestamp; return }
        let dt = min(0.05, link.timestamp - lastTime); lastTime = link.timestamp
        start += velocity * CGFloat(dt); velocity *= CGFloat(exp(-5 * dt))
        let boundary = start <= 0 || start >= 52 - count
        apply() // Also moves notes under every held keyboard finger during inertia.
        if boundary || abs(velocity) < 0.15 { stopInertia() }
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        let keyBounds = CGRect(x: 0, y: 0, width: bounds.width, height: bounds.height)
        for isBlack in [false, true] {
            for note in 21...108 where PianoGeometry.black(note) == isBlack {
                let key = PianoGeometry.rect(note, start: 0, count: 52, bounds: keyBounds)
                let color: UIColor = piano?.instrument?.sounding.contains(note) == true ? .systemTeal : (isBlack ? UIColor(white: 0.15, alpha: 1) : UIColor(white: 0.75, alpha: 1))
                color.setFill(); context.fill(key)
                if !isBlack {
                    // A subtle separator replaces the former exposed black gap between overview keys.
                    context.setStrokeColor(UIColor.black.withAlphaComponent(0.16).cgColor)
                    context.setLineWidth(0.45)
                    context.move(to: CGPoint(x: key.maxX, y: key.minY))
                    context.addLine(to: CGPoint(x: key.maxX, y: key.maxY)); context.strokePath()
                }
            }
        }
        let viewport = CGRect(x: start / 52 * bounds.width, y: 1, width: count / 52 * bounds.width, height: bounds.height - 2)
        let outline = UIBezierPath(roundedRect: viewport, cornerRadius: 9)
        UIColor.systemTeal.withAlphaComponent(0.18).setFill(); outline.fill()
        UIColor.systemTeal.setStroke(); outline.lineWidth = 2; outline.stroke()
        UIColor.systemTeal.setFill()
        for x in [viewport.minX, viewport.maxX] { context.fill(CGRect(x: x - 2, y: viewport.midY - 10, width: 4, height: 20)) }
        // Fit octave labels inside narrow overview keys, with a small bottom inset.
        let labelSize = min(5.5, bounds.width / 52 * 0.48)
        let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: labelSize, weight: .medium), .foregroundColor: UIColor.darkGray]
        for (index, note) in PianoGeometry.whites.enumerated() where note % 12 == 0 {
            let title = "C\(note / 12 - 1)"
            let textSize = title.size(withAttributes: attrs)
            title.draw(at: CGPoint(x: (CGFloat(index) + 0.5) / 52 * bounds.width - textSize.width / 2, y: bounds.height - textSize.height - 1.5), withAttributes: attrs)
        }
    }
}

@MainActor final class DisplayLinkProxy: NSObject {
    weak var owner: KeyboardMap?
    init(owner: KeyboardMap) { self.owner = owner }
    @objc func frame(_ link: CADisplayLink) { owner?.frame(link) }
}

struct KeyboardMapSurface: UIViewRepresentable {
    @ObservedObject var instrument: Instrument
    func makeUIView(context: Context) -> KeyboardMap {
        let view = KeyboardMap(frame: .zero)
        view.viewport = instrument.keyboard; view.piano = instrument.keyboard.piano
        instrument.keyboard.map = view; instrument.keyboard.apply(instrument.keyboard.range)
        return view
    }
    func updateUIView(_ view: KeyboardMap, context: Context) {
        view.piano = instrument.keyboard.piano
        view.accessibilityLabel = instrument.L("Диапазон клавиатуры", "Keyboard range")
        view.accessibilityHint = instrument.L("Один палец перемещает рамку, два меняют её ширину", "Drag with one finger; spread or pinch two fingers to resize the frame")
        view.setNeedsDisplay()
        // Playback highlights notes on the map but never changes the user-selected range.
    }
    static func dismantleUIView(_ view: KeyboardMap, coordinator: ()) { view.stopInertia() }
}

struct KeyboardSurface: UIViewRepresentable {
    @ObservedObject var instrument: Instrument
    func makeUIView(context: Context) -> PianoView {
        let view = PianoView(frame: .zero); view.instrument = instrument
        instrument.keyboard.piano = view; instrument.keyboard.map?.piano = view
        instrument.keyboard.apply(instrument.keyboard.range)
        return view
    }
    func updateUIView(_ view: PianoView, context: Context) {
        view.instrument = instrument
        if view.generation != instrument.keyboardGeneration {
            view.contacts.removeAll(); view.generation = instrument.keyboardGeneration; instrument.keyboard.map?.stopInertia()
        }
        view.setNeedsDisplay()
        view.accessibilityLabel = instrument.L("Клавиатура фортепиано", "Piano keyboard")
        view.accessibilityHint = instrument.latchGesturesEnabled
            ? instrument.L("Проведи вверх, чтобы удержать ноту; коснись её замка, чтобы отпустить", "Swipe up to latch a note; tap its lock to release it")
            : instrument.L("Закрепление жестом выключено; перемещай палец между клавишами", "Gesture latching is off; slide between keys")
    }
}


