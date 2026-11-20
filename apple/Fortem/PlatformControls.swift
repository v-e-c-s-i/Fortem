// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import CoreAudioKit
import QuartzCore

// Anchor the compact tonic grid below its button, independently of the tuning scroller.
struct TonicButtonAnchorKey: PreferenceKey {
    static var defaultValue: Anchor<CGRect>? = nil
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = nextValue() ?? value
    }
}

struct QuickTuningControls: View {
    @ObservedObject var instrument: Instrument
    @Binding var tonicExpanded: Bool
    private var configuration: InstrumentConfiguration { instrument.effectiveConfiguration }
    let scale: CGFloat
    var body: some View {
        HStack(spacing: 6 * scale) {
            // The tonic has a fixed position; visible tunings use the remaining width.
            Button { tonicExpanded.toggle() } label: {
                HStack(spacing: 5) {
                    Text(configuration.name(60 + configuration.tonic, octave: false))
                        .font(.system(size: 13 * scale, weight: .semibold))
                    Image(systemName: "chevron.down").font(.system(size: 9 * scale))
                }.frame(width: 64 * scale, height: 40 * scale)
                    .background(Color.teal.opacity(0.18), in: RoundedRectangle(cornerRadius: 10))
                    .contentShape(Rectangle())
            }.buttonStyle(.plain)
                .anchorPreference(key: TonicButtonAnchorKey.self, value: .bounds) { $0 }
                .accessibilityLabel(instrument.L("Тональность", "Tonic"))
                .accessibilityValue(configuration.name(60 + configuration.tonic, octave: false))
                .disabled(!instrument.tuningControlsEnabled || configuration.tuning == .equal)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6 * scale) {
                    ForEach(Tuning.builtIn.filter { instrument.visibleTunings.contains($0.rawValue) || configuration.tuning == $0 }) { tuning in
                        chip(instrument.tuningShortTitle(tuning), selected: configuration.tuning == tuning) { instrument.config.tuning = tuning }
                    }
                    ForEach(instrument.scales.filter { instrument.visibleTunings.contains(instrument.scaleID($0)) || configuration.scala?.id == $0.id }) { item in
                        chip(item.title, selected: configuration.tuning == .scala && configuration.scala?.id == item.id) { instrument.config.scala = item; instrument.config.tuning = .scala }
                    }
                }
            }.disabled(!instrument.tuningControlsEnabled)
        }
    }
    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 12 * scale)).padding(.horizontal, 10 * scale).frame(height: 36 * scale)
                .background(selected ? Color.teal.opacity(0.22) : Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 8))
        }.buttonStyle(.plain).accessibilityLabel(title)
            .accessibilityValue(Text(selected ? instrument.L("Выбрано", "Selected") : ""))
    }
}

// White-note roots occupy one row. Altered roots align with the piano's black keys;
// historical tunings keep separate sharp and flat choices without a tall system menu.
struct TonicMatrix: View {
    @ObservedObject var instrument: Instrument
    let scale: CGFloat
    let dismiss: () -> Void
    private var configuration: InstrumentConfiguration { instrument.effectiveConfiguration }
    private var separate: Bool { [.pythagorean, .just, .meantone].contains(configuration.tuning) }
    var body: some View {
        VStack(spacing: 4 * scale) {
            row([0, 2, 4, 5, 7, 9, 11], flat: configuration.flats)
            row([1, 3, nil, 6, 8, 10, nil], flat: separate ? false : configuration.flats, shifted: true)
            if separate { row([1, 3, nil, 6, 8, 10, nil], flat: true, shifted: true) }
        }.padding(8 * scale)
            .background(Color(red: 0.13, green: 0.16, blue: 0.15), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.35)))
            .shadow(color: .black.opacity(0.4), radius: 8, y: 4)
            .fixedSize().accessibilityIdentifier("tonic-grid")
    }
    private func row(_ pitches: [Int?], flat: Bool, shifted: Bool = false) -> some View {
        // Half a column puts C-sharp / D-flat between C and D, without drawing piano keys.
        ZStack(alignment: .topLeading) {
            ForEach(pitches.indices, id: \.self) { index in
                if let pitch = pitches[index] {
                    option(pitch, flat: flat)
                        .offset(x: (CGFloat(index) * 40 + (shifted ? 20 : 0)) * scale)
                }
            }
        }.frame(width: 276 * scale, height: 36 * scale, alignment: .topLeading)
    }
    private func option(_ pitch: Int, flat: Bool) -> some View {
        var spelling = configuration
        spelling.flats = flat
        let title = spelling.name(60 + pitch, octave: false)
        let selected = configuration.tonic == pitch && (!PianoGeometry.black(pitch) || configuration.flats == flat)
        return Button {
            var next = instrument.config; next.tonic = pitch; next.flats = flat
            instrument.config = next; dismiss()
        } label: {
            Text(title).font(.system(size: 12 * scale, weight: selected ? .bold : .medium))
                .frame(width: 36 * scale, height: 36 * scale)
                .background(selected ? Color.teal.opacity(0.32) : Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 6))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(title)
            .accessibilityValue(selected ? instrument.L("Выбрано", "Selected") : "")
    }
}

// Reuse the Android padlock geometry for the toolbar and for latched piano keys.
struct PadlockSymbol: View {
    var open = false
    var body: some View {
        Canvas { context, size in
            let paths = PadlockArtwork.paths(in: CGRect(origin: .zero, size: size), open: open)
            context.stroke(Path(paths.outline), with: .foreground,
                           style: StrokeStyle(lineWidth: 1.8 * size.width / 23, lineCap: .round))
            context.fill(Path(paths.keyhole), with: .foreground)
        }.allowsHitTesting(false)
    }
}

// Piano-pedal outline shared by the toolbar and symbol reference.
struct PedalSymbol: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.width * 0.15, y: rect.height * 0.78))
        p.addLine(to: CGPoint(x: rect.width * 0.85, y: rect.height * 0.78))
        p.addQuadCurve(to: CGPoint(x: rect.width * 0.7, y: rect.height * 0.28), control: CGPoint(x: rect.width * 0.98, y: rect.height * 0.35))
        p.addLine(to: CGPoint(x: rect.width * 0.35, y: rect.height * 0.43))
        p.closeSubpath()
        p.move(to: CGPoint(x: rect.width * 0.1, y: rect.height * 0.95))
        p.addLine(to: CGPoint(x: rect.width * 0.9, y: rect.height * 0.95))
        return p
    }
}

enum FortemPalette {
    static let background = Color(red: 17/255, green: 20/255, blue: 19/255)
}

// The instrument uses the safe size of its current window rather than the display size.
struct PerformanceWindowLayout {
    let width: CGFloat
    let height: CGFloat
    let compact: Bool
    let scale: CGFloat
    let mapHeight: CGFloat
    var tuningWidth: CGFloat { max(0, width - (compact ? 94 : 247) * scale) }
    // Side padding, instrument/transport, pedal/lock and their four gaps.
    var mapSlotWidth: CGFloat { max(0, width - 380 * scale) }
    var mapWidth: CGFloat { compact ? width : mapSlotWidth }
    var mapX: CGFloat { compact ? 0 : 268 * scale }
    var mapY: CGFloat { compact ? height - mapHeight : 46 * scale }
    var keyboardHeight: CGFloat { max(0, height - (compact ? 102 : 96) * scale - (compact ? mapHeight : 0)) }

    init(width: CGFloat, height: CGFloat, preferredScale: CGFloat) {
        self.width = width
        self.height = height
        let isCompact = width < 500 || width <= height
        compact = isCompact
        let bottomMap = min(64, height * 0.23)
        let natural = isCompact ? min(1.05, max(0.72, width / 440))
            : min(1.2, max(0.78, min(width / 730, height / 355)))
        let reservedKeys = min(128, height * 0.4)
        let heightLimit = (height - reservedKeys - (isCompact ? bottomMap : 0)) / (isCompact ? 102 : 96)
        let fittedScale = max(0, min(natural * preferredScale, width / (isCompact ? 440 : 510), heightLimit))
        scale = fittedScale
        mapHeight = isCompact ? bottomMap : 44 * fittedScale
    }

    static func drawerWidth(window: CGFloat, trailing: CGFloat) -> CGFloat {
        // A wider reading column keeps tuning descriptions comfortable in landscape.
        min(max(window * 0.45, 240 + trailing), max(0, window - 36))
    }
}

// The system picker copies a finished WAV to a location selected by the user,
// including local folders, iCloud Drive and installed document providers.
struct SaveWavePicker: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController { UIDocumentPickerViewController(forExporting: [url], asCopy: true) }
    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
}

struct BluetoothMIDIScreen: View {
    @ObservedObject var instrument: Instrument
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            BluetoothMIDIController().navigationTitle("Bluetooth MIDI")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(instrument.L("Готово", "Done")) { dismiss(); instrument.midi.refresh() } } }
        }
    }
}

struct BluetoothMIDIController: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> CABTMIDICentralViewController { CABTMIDICentralViewController() }
    func updateUIViewController(_ uiViewController: CABTMIDICentralViewController, context: Context) {}
}


// A clipped UIKit label uses a compositor animation rather than redrawing the whole list each frame.
struct MarqueeRecordingTitle: UIViewRepresentable {
    let text: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .body) private var pointSize: CGFloat = 17

    func makeUIView(context: Context) -> MarqueeRecordingLabel { MarqueeRecordingLabel() }
    func updateUIView(_ view: MarqueeRecordingLabel, context: Context) {
        view.configure(text: text, font: .systemFont(ofSize: pointSize), reduceMotion: reduceMotion)
    }
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: MarqueeRecordingLabel, context: Context) -> CGSize? {
        // A long title never asks the row to expand past its action buttons.
        let width = proposal.width.flatMap { $0.isFinite ? max(0, $0) : nil } ?? 0
        return CGSize(width: width, height: ceil(UIFont.systemFont(ofSize: pointSize).lineHeight))
    }
}

final class MarqueeRecordingLabel: UIView {
    private let title = UILabel()
    private let repeatTitle = UILabel()
    private var reduceMotion = false
    private var laidOutSize: CGSize?
    private static let animationKey = "recording-title-scroll"

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        title.numberOfLines = 1; title.textColor = .label
        title.isAccessibilityElement = false
        repeatTitle.numberOfLines = 1; repeatTitle.textColor = .label
        repeatTitle.isAccessibilityElement = false
        addSubview(title); addSubview(repeatTitle)
        isAccessibilityElement = true; accessibilityTraits = .staticText
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: ceil(title.font.lineHeight))
    }
    func configure(text: String, font: UIFont, reduceMotion: Bool) {
        // Playback updates elapsed time frequently; unchanged titles must keep their scroll position.
        guard title.text != text || !title.font.isEqual(font) || self.reduceMotion != reduceMotion else { return }
        title.text = text; title.font = font; self.reduceMotion = reduceMotion
        repeatTitle.text = text; repeatTitle.font = font
        accessibilityLabel = text
        laidOutSize = nil; invalidateIntrinsicContentSize(); setNeedsLayout()
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        laidOutSize = nil
        title.layer.removeAnimation(forKey: Self.animationKey)
        repeatTitle.layer.removeAnimation(forKey: Self.animationKey)
        if window != nil { setNeedsLayout() }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        guard laidOutSize != bounds.size else { return }
        laidOutSize = bounds.size
        title.layer.removeAnimation(forKey: Self.animationKey)
        repeatTitle.layer.removeAnimation(forKey: Self.animationKey)
        guard bounds.width > 0, bounds.height > 0 else { return }
        let fullWidth = ceil(title.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: bounds.height)).width)
        let overflow = max(0, fullWidth - bounds.width)
        let scrolling = overflow > 0.5 && !reduceMotion
        title.lineBreakMode = scrolling ? .byClipping : .byTruncatingTail
        title.frame = CGRect(x: 0, y: 0, width: scrolling ? fullWidth : bounds.width, height: bounds.height)
        repeatTitle.isHidden = !scrolling
        let distance = fullWidth + 24
        repeatTitle.frame = CGRect(x: distance, y: 0, width: fullWidth, height: bounds.height)
        guard scrolling, window != nil else { return }
        // A second copy follows after a short 24-point gap; there is no long empty return trip.
        let animation = CABasicAnimation(keyPath: "transform.translation.x")
        animation.fromValue = 0; animation.toValue = -distance
        animation.duration = Double(distance) / 36
        animation.beginTime = CACurrentMediaTime() + 0.9
        animation.fillMode = .backwards; animation.repeatCount = .infinity
        title.layer.add(animation, forKey: Self.animationKey)
        repeatTitle.layer.add(animation, forKey: Self.animationKey)
    }
}


// Light outline shared by the toolbar and its reference. iOS keeps system navigation for exit.
struct GearSymbol: View {
    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            var path = Path()
            let phases: [(Double, Double)] = [(0, 0.35), (0.18, 0.35), (0.28, 0.45), (0.62, 0.45), (0.72, 0.35)]
            for tooth in 0..<8 {
                for (index, pair) in phases.enumerated() {
                    let angle = ((Double(tooth) + pair.0) * 45 - 90) * .pi / 180
                    let radius = min(size.width, size.height) * CGFloat(pair.1)
                    let point = CGPoint(x: center.x + radius * CGFloat(cos(angle)), y: center.y + radius * CGFloat(sin(angle)))
                    if tooth == 0 && index == 0 { path.move(to: point) } else { path.addLine(to: point) }
                }
            }
            path.closeSubpath()
            let stroke = StrokeStyle(lineWidth: 1.35 * size.width / 22, lineCap: .round, lineJoin: .round)
            context.stroke(path, with: .foreground, style: stroke)
            let radius = min(size.width, size.height) * 0.13
            context.stroke(Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)), with: .foreground, style: stroke)
        }.allowsHitTesting(false)
    }
}
