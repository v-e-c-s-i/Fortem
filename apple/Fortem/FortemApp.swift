// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI
import UniformTypeIdentifiers
import UIKit
import QuartzCore

@main struct FortemApp: App {
    @StateObject private var instrument = Instrument()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            FortemScreen(instrument: instrument)
                .preferredColorScheme(.dark)
                .onAppear { instrument.activate(true) }
                .onChange(of: phase) { instrument.activate($0 == .active) }
        }
    }
}

struct FortemScreen: View {
    @ObservedObject var instrument: Instrument
    @State private var settings = false
    @State private var library = false
    @State private var tonicExpanded = false
    var body: some View {
        GeometryReader { window in
            let safeSize = window.size
            let layout = PerformanceWindowLayout(width: max(0, safeSize.width - 4),
                                                 height: max(0, safeSize.height - 4),
                                                 preferredScale: CGFloat(instrument.interfaceScale))
            performanceSurface(layout)
                .padding(2)
                .frame(width: safeSize.width, height: safeSize.height)
                .allowsHitTesting(!settings).accessibilityHidden(settings)
                .overlay(alignment: .bottomTrailing) {
                    if !settings && !library && instrument.undoTrash != nil {
                        RecordingUndoNotice(instrument: instrument).padding(.trailing, 12).padding(.bottom, 8)
                    }
                }
        }
        // Keep edge-to-edge decoration separate from the measured playing surface.
        .background(FortemPalette.background.ignoresSafeArea(.container))
        .overlay {
            GeometryReader { window in
                if settings {
                    let insets = window.safeAreaInsets
                    let size = CGSize(width: window.size.width + insets.leading + insets.trailing,
                                      height: window.size.height + insets.top + insets.bottom)
                    let width = PerformanceWindowLayout.drawerWidth(window: size.width, trailing: insets.trailing)
                    ZStack(alignment: .trailing) {
                        Button { settings = false } label: {
                            Color.black.opacity(0.38)
                                .frame(width: size.width, height: size.height)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(instrument.L("Закрыть настройки", "Close settings"))
                        .accessibilityIdentifier("settings-scrim")
                        .transition(.opacity)

                        SettingsScreen(instrument: instrument, onClose: { settings = false })
                            .padding(.top, insets.top)
                            .padding(.bottom, insets.bottom)
                            .padding(.trailing, insets.trailing)
                            .frame(width: width, height: size.height)
                            .background(FortemPalette.background)
                            .contentShape(Rectangle())
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("settings-panel")
                            .accessibilityAction(.escape) { settings = false }
                            .transition(.move(edge: .trailing))
                    }
                    .frame(width: size.width, height: size.height, alignment: .trailing)
                    .offset(x: -insets.leading, y: -insets.top)
                }
            }
            .animation(.easeInOut(duration: 0.22), value: settings)
        }
        .preferredColorScheme(.dark)
        .statusBarHidden().persistentSystemOverlays(.hidden)
        .tint(Color(red: 130/255, green: 213/255, blue: 195/255))
        .onChange(of: settings) { shown in
            if shown { tonicExpanded = false }
            else { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
        }
        .fullScreenCover(isPresented: $library) {
            RecordingsScreen(instrument: instrument, onClose: { library = false })
                .preferredColorScheme(.dark).statusBarHidden().persistentSystemOverlays(.hidden)
        }
        .onChange(of: library) { shown in if shown { tonicExpanded = false } }
        .alert("Fortem", isPresented: Binding(get: { instrument.error != nil && !settings && !library }, set: { if !$0 { instrument.error = nil } })) {
            Button("OK") { instrument.error = nil }
        } message: { Text(instrument.error ?? "") }
    }
    private func performanceSurface(_ layout: PerformanceWindowLayout) -> some View {
        let scale = layout.scale
        return VStack(spacing: 6 * scale) {
            HStack(spacing: 8 * scale) {
                QuickTuningControls(instrument: instrument, tonicExpanded: $tonicExpanded, scale: scale)
                    .frame(width: layout.tuningWidth)
                    .clipped()
                if !layout.compact { noteReadout(scale).frame(width: 145 * scale, alignment: .leading) }
                Button { settings = true } label: {
                    GearSymbol().frame(width: 22 * scale, height: 22 * scale)
                        .frame(width: 44 * scale, height: 44 * scale)
                        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 11))
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel(instrument.L("Настройки", "Settings"))
                    .offset(y: 3 * scale).padding(.trailing, 26 * scale)
            }.frame(height: 40 * scale).padding(.horizontal, 8 * scale)
            HStack(spacing: 8 * scale) {
                Button { instrument.config.piano.toggle() } label: {
                    HStack(spacing: 6 * scale) {
                        Text(instrument.config.piano ? instrument.L("Рояль", "Piano") : instrument.L("Чистый тон", "Pure tone"))
                            .font(.system(size: 13 * scale, weight: .semibold))
                            .lineLimit(1).minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        VStack(spacing: 4 * scale) {
                            Circle().fill(instrument.config.piano ? Color.secondary.opacity(0.4) : Color.teal).frame(width: 4 * scale, height: 4 * scale)
                            Circle().fill(instrument.config.piano ? Color.teal : Color.secondary.opacity(0.4)).frame(width: 4 * scale, height: 4 * scale)
                        }
                    }.padding(.horizontal, 8 * scale)
                        .frame(width: 112 * scale, height: 44 * scale)
                        .background(Color(uiColor: .darkGray).opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel(instrument.L("Переключить инструмент", "Switch instrument"))
                    .accessibilityValue(instrument.config.piano ? instrument.L("Рояль", "Piano") : instrument.L("Чистый тон", "Pure tone"))
                    .disabled(!instrument.audio.pianoAvailable || !instrument.instrumentControlsEnabled)
                HStack(spacing: 0) {
                    if instrument.recording != nil {
                        transportIcon("arrow.counterclockwise.circle", scale: scale, label: instrument.L("Перезаписать", "Re-record"), red: true) { instrument.restartRecording() }
                        transportIcon("stop.fill", scale: scale, label: instrument.L("Остановить и сохранить", "Stop and save")) { instrument.stopRecording() }
                    } else {
                        transportIcon("record.circle", scale: scale, label: instrument.L("Запись", "Record"), red: true) { instrument.startRecording() }.disabled(instrument.midi.connected || instrument.playing)
                        transportIcon(instrument.playing ? "stop.fill" : "play.fill", scale: scale, label: instrument.L("Воспроизведение", "Playback")) {
                            if instrument.playing { instrument.stopPlayback() }
                            else if let item = instrument.visiblePerformances.first(where: { $0.id == instrument.selected }) ?? instrument.visiblePerformances.first { instrument.play(item) }
                        }.disabled(!instrument.playing && instrument.visiblePerformances.isEmpty)
                    }
                    transportIcon("music.note.list", scale: scale, label: instrument.L("Записи", "Recordings")) { library = true }
                }
                .frame(width: 132 * scale, height: 44 * scale)
                .background {
                    TimelineView(.animation(minimumInterval: 1.0 / 60, paused: !instrument.playing)) { _ in
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Color.white.opacity(0.06)
                            if instrument.playing {
                                Color.teal.opacity(0.26)
                                    .frame(width: geometry.size.width * CGFloat(instrument.smoothPlaybackProgress))
                            }
                        }.clipShape(RoundedRectangle(cornerRadius: 11))
                    }.allowsHitTesting(false)
                    }
                }
                .overlay(alignment: .bottom) {
                    if instrument.recording != nil || instrument.playing {
                        Text(time(instrument.elapsed)).font(.system(size: 8 * scale).monospacedDigit())
                            .lineLimit(1).padding(.bottom, 1 * scale).allowsHitTesting(false)
                    }
                }
                // Reserve the same slot for the map in landscape, and the note in narrow windows.
                if layout.compact {
                    noteReadout(scale).frame(width: layout.mapSlotWidth, alignment: .leading)
                } else {
                    Color.clear.frame(width: layout.mapSlotWidth).allowsHitTesting(false)
                }
                Button { instrument.setPedal(!instrument.pedalEnabled) } label: {
                    PedalSymbol().stroke(instrument.pedalEnabled ? Color.teal : Color.primary, style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
                        .frame(width: 27 * scale, height: 24 * scale).frame(width: 44 * scale, height: 44 * scale)
                        .background(instrument.pedalEnabled ? Color.teal.opacity(0.2) : Color.clear, in: RoundedRectangle(cornerRadius: 10))
                        // The whole button responds, including empty space around the outline.
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityLabel( instrument.L("Автоматическая педаль", "Automatic pedal")).accessibilityValue(instrument.pedalEnabled ? instrument.L("Включена", "On") : instrument.L("Выключена", "Off")).disabled(instrument.playing)
                Button { instrument.clearLatches() } label: {
                    PadlockSymbol(open: true).frame(width: 23 * scale, height: 23 * scale)
                        .frame(width: 44 * scale, height: 44 * scale).contentShape(Rectangle())
                }.buttonStyle(.plain)
                    .accessibilityLabel(instrument.L("Снять фиксацию всех нот", "Clear latched notes"))
                    .disabled(!instrument.canClearLatches)
            }.frame(height: 44 * scale).padding(.horizontal, 8 * scale)
            KeyboardSurface(instrument: instrument)
                .frame(width: layout.width, height: layout.keyboardHeight)
            if layout.compact {
                Color.clear.frame(height: layout.mapHeight).allowsHitTesting(false)
            }
        }
        .frame(width: layout.width, height: layout.height, alignment: .top)
        .overlay(alignment: .topLeading) {
            // One map view survives resizing; its anchors and size share the keyboard's bounds.
            KeyboardMapSurface(instrument: instrument)
                .frame(width: layout.mapWidth, height: layout.mapHeight)
                .offset(x: layout.mapX, y: layout.mapY)
        }
        .animation(.easeOut(duration: 0.12), value: instrument.interfaceScale)
        .overlayPreferenceValue(TonicButtonAnchorKey.self) { anchor in
            GeometryReader { geometry in
                if tonicExpanded, let anchor {
                    // A local overlay stays compact on iPhone and closes on an outside tap.
                    ZStack(alignment: .topLeading) {
                        Color.black.opacity(0.001).contentShape(Rectangle())
                            .onTapGesture { tonicExpanded = false }
                        let x = max(0, geometry[anchor].minX)
                        let y = geometry[anchor].maxY + 4
                        let gridScale = min(scale, max(0, (geometry.size.width - x - 4) / 292),
                                            max(0, (geometry.size.height - y - 4) / 132))
                        TonicMatrix(instrument: instrument, scale: gridScale) { tonicExpanded = false }
                            .offset(x: x, y: y)
                    }
                }
            }
        }
        .onChange(of: instrument.config.tuning) { _ in tonicExpanded = false }
        .onChange(of: instrument.tuningControlsEnabled) { _ in tonicExpanded = false }
    }
    private func noteReadout(_ scale: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 2 * scale) {
            if let note = instrument.currentNote {
                let configuration = instrument.effectiveConfiguration
                let hz = configuration.frequency(note)
                Text("\(configuration.name(note)) · \(hz, specifier: "%.2f") Hz")
                let deviation = 1200 * log2(hz / (configuration.reference * pow(2, Double(note - 69) / 12)))
                Text(String(format: "%+.2f ¢", deviation)).foregroundStyle(.secondary)
            } else { Text(instrument.L("Коснись клавиши", "Touch a key")) }
        }
        .font(.system(size: 11 * scale).monospacedDigit())
        .lineLimit(1).minimumScaleFactor(0.7)
        .accessibilityIdentifier("current-note")
    }
    private func icon(_ symbol: String, scale: CGFloat, label: String, active: Bool = false, red: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 22 * scale, weight: .regular))
                .foregroundStyle(red ? Color.red : (active ? Color.teal : Color.primary))
                .frame(width: 44 * scale, height: 44 * scale).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(label)
    }
    private func transportIcon(_ symbol: String, scale: CGFloat, label: String, red: Bool = false, action: @escaping () -> Void) -> some View {
        // Every transport glyph stays centered in the same hit target in every state.
        icon(symbol, scale: scale, label: label, red: red, action: action)
    }
    private func time(_ value: Double) -> String { String(format: "%02d:%02d", Int(value) / 60, Int(value) % 60) }
}

struct SettingsScreen: View {
    @ObservedObject var instrument: Instrument
    let onClose: () -> Void
    @State private var importScala = false
    @State private var bluetooth = false
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Text(instrument.L("Настройки", "Settings"))
                    .font(.title2.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.body.weight(.semibold))
                        .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(instrument.L("Закрыть настройки", "Close settings"))
                .accessibilityIdentifier("settings-close")
            }
            .padding(.leading, 20).padding(.trailing, 8)
            Form {
                Section(instrument.L("Интерфейс", "Interface")) {
                    HStack {
                        Text(instrument.L("Язык интерфейса", "Interface language"))
                        Spacer()
                        Menu {
                            Button("Русский") { instrument.language = "ru" }
                            Button("English") { instrument.language = "en" }
                        } label: { Text(instrument.russian ? "Русский" : "English") }
                        .accessibilityLabel(instrument.L("Язык интерфейса", "Interface language"))
                    }
                    HStack {
                        Text(instrument.L("Верхняя панель", "Control panels"))
                        Spacer()
                        Text("\(Int((instrument.interfaceScale * 100).rounded()))%").monospacedDigit()
                    }
                    Slider(value: $instrument.interfaceScale, in: 0.8...1.2)
                        .accessibilityLabel(instrument.L("Размер элементов", "Control size"))
                    Button(instrument.L("Стандартный размер", "Reset control size")) { instrument.interfaceScale = 1 }
                }
                Section(instrument.L("Инструмент", "Instrument")) {
                    choice(title: instrument.L("Фортепиано", "Piano"), detail: "", selected: instrument.config.piano) { instrument.config.piano = true }
                        .disabled(!instrument.audio.pianoAvailable || !instrument.instrumentControlsEnabled)
                    choice(title: instrument.L("Чистый тон", "Pure tone"), detail: instrument.L("Синусоидальный сигнал для сравнения строев", "Sine wave for comparing tunings"), selected: !instrument.config.piano) { instrument.config.piano = false }.disabled(!instrument.instrumentControlsEnabled)
                    if !instrument.audio.pianoAvailable { Text(instrument.L("Звуковой банк недоступен в этой сборке", "The piano bank is unavailable in this build")).foregroundStyle(.red) }
                }
                Section(instrument.L("Показывать строи", "Visible tunings")) {
                    ForEach(Tuning.builtIn) { tuning in
                        choice(title: instrument.tuningTitle(tuning), detail: instrument.tuningDetail(tuning), selected: instrument.visibleTunings.contains(tuning.rawValue)) { instrument.toggleVisibility(tuning.rawValue) }
                    }
                    ForEach(instrument.scales) { scale in
                        HStack {
                            choice(title: scale.title, detail: "Scala · 12", selected: instrument.visibleTunings.contains(instrument.scaleID(scale))) {
                                instrument.toggleVisibility(instrument.scaleID(scale))
                            }
                            Button(role: .destructive) { instrument.removeScale(scale) } label: { Image(systemName: "trash") }
                                .accessibilityLabel(instrument.L("Удалить строй", "Delete tuning"))
                        }
                    }
                }
                Section("Scala") {
                    Button { importScala = true } label: { Label(instrument.L("Импортировать .scl", "Import .scl"), systemImage: "square.and.arrow.down") }
                }
                Section(instrument.L("Опорная частота", "Reference pitch")) {
                    Toggle(instrument.L("Названия с бемолями ♭", "Flat note names ♭"), isOn: $instrument.config.flats).disabled(!instrument.tuningControlsEnabled)
                    HStack {
                        Text("A4")
                        TextField("440", value: $instrument.config.reference, format: .number.precision(.fractionLength(1)))
                            .keyboardType(.decimalPad).multilineTextAlignment(.trailing).disabled(!instrument.tuningControlsEnabled)
                        Text("Hz").foregroundStyle(.secondary)
                    }
                    Text(instrument.L("300–500 Гц. В неравномерных строях A4 задаёт опорную шкалу; тоника определяет интервалы.", "300–500 Hz. In unequal tunings A4 sets the reference scale; the tonic defines the intervals.")).font(.caption).foregroundStyle(.secondary)
                }
                Section("MIDI") {
                    choice(title: instrument.L("Звук устройства при MIDI", "Device sound with MIDI"), detail: instrument.L("Одновременно играть на внешнем инструменте и через динамик устройства", "Play the external instrument and this device together"), selected: instrument.midiLocalSound) { instrument.midiLocalSound.toggle() }
                    choice(title: instrument.L("MIDI выключен", "MIDI off"), detail: "", selected: !instrument.midi.connected) { instrument.midi.select(0) }
                    ForEach(instrument.midi.destinations) { port in
                        choice(title: port.title, detail: "MIDI", selected: instrument.midi.selected == port.id) { instrument.midi.select(port.id) }
                    }
                    if !instrument.midi.available { Text(instrument.L("Служба MIDI недоступна", "MIDI service is unavailable")) }
                    else if instrument.midi.destinations.isEmpty { Text(instrument.L("Подключи USB-MIDI или выбери Bluetooth MIDI", "Connect USB MIDI or choose Bluetooth MIDI")).font(.caption).foregroundStyle(.secondary) }
                    Button { bluetooth = true } label: { Label("Bluetooth MIDI", systemImage: "antenna.radiowaves.left.and.right") }
                    Text(instrument.L("При MIDI используется равномерный строй A4 = 440 Гц. Внутренняя запись отключена; сохранённые исполнения можно проигрывать через MIDI.", "MIDI uses equal temperament at A4 = 440 Hz. New internal recordings are disabled; saved performances can play through MIDI.")).font(.caption).foregroundStyle(.secondary)
                }
                Section(instrument.L("Удерживаемые ноты", "Held notes")) {
                    choice(title: instrument.L("Закреплять ноты жестом вверх", "Latch notes with an upward swipe"),
                           detail: instrument.L("Выключите для свободного движения к чёрным клавишам. Ручные закрепления снимаются; педаль и сохранённые записи продолжают работать.", "Turn off to slide freely onto black keys. Manual latches are released; the pedal and saved recordings keep working."),
                           selected: instrument.latchGesturesEnabled) { instrument.latchGesturesEnabled.toggle() }
                    choice(title: instrument.L("Повторять атаку при воспроизведении", "Repeat attack during playback"), detail: instrument.L("При смене строя или тоники в записи удерживаемые ноты звучат заново", "When a recording changes tuning or tonic, held notes sound again"), selected: instrument.retriggerPlayback) { instrument.retriggerPlayback.toggle() }
                }
                Section(instrument.L("Обозначения интерфейса", "Interface symbols")) {
                    InterfaceSymbolsReference(instrument: instrument)
                }
                Section("WAV") {
                    Text(instrument.L("В меню записи выбери «Сохранить WAV в Файлы» или «Поделиться WAV». Копия также остаётся в Файлы → На моём iPhone/iPad → Fortem → WAV.", "In a recording's menu choose Save WAV to Files or Share WAV. A copy also stays in Files → On My iPhone/iPad → Fortem → WAV.")).font(.caption)
                }
                Section {
                    Text("Salamander Grand Piano · Alexander Holm · CC BY 3.0").font(.caption).foregroundStyle(.secondary)
                    Text("Fortem \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · iOS/iPadOS")
                    Text(instrument.L("Без рекламы, аналитики и доступа к микрофону. Записи хранятся на устройстве.", "No ads, analytics or microphone access. Recordings stay on your device.")).font(.caption)
                    DisclosureGroup(instrument.L("Лицензии и благодарности", "Licenses and credits")) {
                        Text("Fortem — GPL-3.0-only\nSalamander Grand Piano — Alexander Holm, CC BY 3.0\nAccurate-Salamander — piano key balance")
                        Text(license("SALAMANDER_GRAND_PIANO", extension: "txt")).font(.caption)
                        Text(license("LICENSE", extension: nil)).font(.caption)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(FortemPalette.background)
            .onAppear { instrument.midi.refresh() }
            .sheet(isPresented: $bluetooth) { BluetoothMIDIScreen(instrument: instrument) }
            .fileImporter(isPresented: $importScala, allowedContentTypes: [.data, .plainText], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls): if let url = urls.first { instrument.importScale(url) }
                case .failure: instrument.error = instrument.L("Не удалось открыть файл", "Could not open file")
                }
            }
            .alert("Fortem", isPresented: Binding(get: { instrument.error != nil }, set: { if !$0 { instrument.error = nil } })) {
                Button("OK") { instrument.error = nil }
            } message: { Text(instrument.error ?? "") }
        }.tint(.teal)
    }
    private func choice(title: String, detail: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? Color.teal : Color.secondary).padding(.top, 2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.primary)
                    if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
                }
                Spacer(minLength: 0)
            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(title).accessibilityHint(detail)
            .accessibilityValue(Text(selected ? instrument.L("Выбрано", "Selected") : ""))
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
    private func license(_ name: String, extension ext: String?) -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: ext) else { return "" }
        return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }
}

// A passive reference reuses the same symbols as the playing screen.
struct InterfaceSymbolsReference: View {
    @ObservedObject var instrument: Instrument
    var body: some View {
        Group {
            item(instrument.L("Педаль", "Pedal"), instrument.L("Связывает последовательные аккорды; бирюзовый цвет означает, что педаль включена.", "Connects successive chords; teal means the pedal is on.")) {
                PedalSymbol().stroke(style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
                    .frame(width: 27, height: 24)
        }
        item(instrument.L("Закреплённая нота", "Latched note"), instrument.L("Жест вверх закрепляет ноту. Коснись замочка на клавише, чтобы её отпустить. Жест можно выключить в разделе «Удерживаемые ноты».", "Swipe up to latch a note; tap its lock to release it. Disable the gesture under Held notes if needed.")) {
            PadlockSymbol().frame(width: 23, height: 23)
        }
        item(instrument.L("Снять закрепления", "Clear latches"), instrument.L("Открытый замок снимает ручные закрепления, сохраняя педальные ноты. Доступен при двух и более закреплённых нотах.", "The open lock releases manual latches while preserving pedal notes. Available with two or more latched notes.")) {
            PadlockSymbol(open: true).frame(width: 23, height: 23)
        }
        item(instrument.L("Тоника", "Tonic"), instrument.L("Открывает выбор тоники; диезы и бемоли расположены между основными нотами.", "Opens tonic choices; sharp and flat roots sit between natural notes.")) { Text("C ▾").font(.caption.bold()) }
        item(instrument.L("Рояль / чистый тон", "Piano / pure tone"), instrument.L("Верхняя точка — чистый тон; нижняя — рояль.", "Upper dot: pure tone. Lower dot: piano.")) {
            VStack(spacing: 5) { Circle().frame(width: 5, height: 5); Circle().opacity(0.35).frame(width: 5, height: 5) }
        }
        system("record.circle", instrument.L("Запись", "Record"), instrument.L("Начать запись исполнения; красная кнопка.", "Start recording a performance; the red button."), red: true)
        system("arrow.counterclockwise.circle", instrument.L("Перезапись", "Re-record"), instrument.L("Начать текущий дубль заново, не сохраняя ошибочный.", "Restart the current take without keeping the mistaken draft."), red: true)
        system("stop.fill", instrument.L("Остановка", "Stop"), instrument.L("При записи — остановить и сохранить. При воспроизведении — остановить проигрывание.", "While recording: stop and save. During playback: stop playing."))
        }
        Group {
            system("play.fill", instrument.L("Воспроизведение", "Playback"), instrument.L("Проиграть выбранную запись. Заливка блока показывает текущую позицию.", "Play the selected recording. The block fill shows playback position."))
            system("music.note.list", instrument.L("Записи", "Recordings"), instrument.L("Открыть сохранённые исполнения.", "Open saved performances."))
            system("folder", instrument.L("Сохранить WAV", "Save WAV"), instrument.L("Экспортировать звук записи в «Файлы».", "Export the recording audio to Files."))
            system("square.and.arrow.up", instrument.L("Поделиться", "Share"), instrument.L("Открыть системное меню отправки WAV.", "Open the system WAV share sheet."))
            system("square.and.arrow.down", instrument.L("Импорт строя", "Import tuning"), instrument.L("Загрузить интервалы из файла Scala .scl.", "Load tuning intervals from a Scala .scl file."))
            system("antenna.radiowaves.left.and.right", "Bluetooth MIDI", instrument.L("Открыть подключение к Bluetooth MIDI-инструменту.", "Open Bluetooth MIDI instrument connections."))
            system("play.circle.fill", instrument.L("Проиграть запись", "Play recording"), instrument.L("Выбрать и воспроизвести исполнение из списка.", "Select and play a performance from the list."))
        }
        Group {
            system("pencil", instrument.L("Переименовать", "Rename"), instrument.L("Изменить название записи.", "Change a recording title."))
            system("trash", instrument.L("Корзина", "Trash"), instrument.L("Переместить запись в корзину; её можно восстановить в течение 30 дней.", "Move a recording to trash; it can be restored for 30 days."))
            system("arrow.uturn.backward", instrument.L("Вернуть / восстановить", "Undo / restore"), instrument.L("Отменить удаление или вернуть запись из корзины.", "Undo deletion or restore a recording from trash."))
            system("checkmark.circle.fill", instrument.L("Включено / выбрано", "Enabled / selected"), instrument.L("Галочка отмечает выбранный пункт или включённую настройку.", "A checkmark marks a selected option or an enabled setting."))
            item(instrument.L("Настройки", "Settings"), instrument.L("Открыть параметры приложения и этот справочник. Возврат на главный экран iPhone — системным жестом.", "Open app settings and this reference. Return to the iPhone Home screen with the system gesture.")) { GearSymbol() }
        }
    }
    private func system(_ symbol: String, _ title: String, _ detail: String, red: Bool = false) -> some View {
        item(title, detail) {
            Image(systemName: symbol).font(.system(size: 21))
                .foregroundStyle(red ? Color.red : Color.teal)
        }
    }
    private func item<Symbol: View>(_ title: String, _ detail: String, @ViewBuilder symbol: () -> Symbol) -> some View {
        HStack(alignment: .top, spacing: 12) {
            symbol().foregroundStyle(.teal).frame(width: 32, height: 30).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }.accessibilityElement(children: .combine)
    }
}

struct SharedWave: Identifiable { let id = UUID(); let url: URL }

struct RecordingsScreen: View {
    @ObservedObject var instrument: Instrument
    let onClose: () -> Void
    @AppStorage("recordingsNewestFirst") private var newestFirst = true
    @State private var trash = false
    @State private var rename: Performance?
    @State private var title = ""
    @State private var shared: SharedWave?
    @State private var exporting = false
    @State private var saving: SharedWave?
    @State private var deleting: Performance?
    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollViewReader { proxy in
                List {
                    ForEach(items) { item in
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: 12) {
                                recordingIdentity(item).frame(minWidth: 110, maxWidth: .infinity, alignment: .leading).clipped()
                                recordingActions(item).layoutPriority(1)
                            }
                            VStack(alignment: .leading, spacing: 6) {
                                recordingIdentity(item)
                                recordingActions(item).frame(maxWidth: .infinity, alignment: .trailing)
                            }
                        }
                        .id(item.id)
                        .listRowBackground(RecordingPlaybackBackground(instrument: instrument, id: item.id))
                    }
                    if exporting { ProgressView(instrument.L("Экспорт WAV…", "Exporting WAV…")) }
                }
                .listStyle(.plain).scrollContentBackground(.hidden)
                .scrollIndicators(.visible).accessibilityIdentifier("recording-list")
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: 12).allowsHitTesting(false) }
                .onAppear { reveal(proxy) }
                .onChange(of: trash) { _ in reveal(proxy) }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(FortemPalette.background.ignoresSafeArea())
        .tint(.teal)
        .overlay(alignment: .bottomTrailing) {
            if instrument.undoTrash != nil { RecordingUndoNotice(instrument: instrument).padding(.trailing, 12).padding(.bottom, 8) }
        }
            .alert(instrument.L("Название записи", "Recording title"), isPresented: Binding(get: { rename != nil }, set: { if !$0 { rename = nil } })) {
                TextField(instrument.L("Название", "Title"), text: $title)
                Button(instrument.L("Сохранить", "Save")) {
                    if var item = rename, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        item.title = String(title.prefix(240)); instrument.save(item)
                    }; rename = nil
                }
                Button(instrument.L("Отмена", "Cancel"), role: .cancel) { rename = nil }
            }
            .sheet(item: $shared) { ShareSheet(url: $0.url) }
            .sheet(item: $saving) { SaveWavePicker(url: $0.url) }
            .confirmationDialog(instrument.L("Удалить запись навсегда?", "Delete recording permanently?"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
                Button(instrument.L("Удалить", "Delete"), role: .destructive) { if let item = deleting { instrument.deletePermanently(item) }; deleting = nil }
            }
            .alert("Fortem", isPresented: Binding(get: { instrument.error != nil }, set: { if !$0 { instrument.error = nil } })) {
                Button("OK") { instrument.error = nil }
            } message: { Text(instrument.error ?? "") }
    }
    private var header: some View {
        HStack(spacing: 16) {
            Text(trash ? instrument.L("Корзина", "Trash") : instrument.L("Записи", "Recordings"))
                .font(.title2.weight(.semibold)).lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Menu {
                Button { newestFirst = true } label: {
                    Label(instrument.L("Сначала новые", "Newest first"), systemImage: newestFirst ? "checkmark" : "arrow.down")
                }
                Button { newestFirst = false } label: {
                    Label(instrument.L("Сначала старые", "Oldest first"), systemImage: newestFirst ? "arrow.up" : "checkmark")
                }
            } label: {
                HStack(spacing: 6) {
                    Text(instrument.L(newestFirst ? "Сначала новые" : "Сначала старые", newestFirst ? "Newest first" : "Oldest first"))
                    Image(systemName: "chevron.down").font(.caption2)
                }.font(.subheadline).padding(.horizontal, 10).frame(minHeight: 44)
            }.accessibilityIdentifier("recordings-sort")
            action(trash ? "music.note.list" : "trash", instrument.L("Корзина / записи", "Trash / recordings")) { trash.toggle() }
                .accessibilityIdentifier("recordings-trash")
            Button(action: onClose) {
                Image(systemName: "xmark").font(.system(size: 18, weight: .medium))
                    .frame(width: 44, height: 44)
                    .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 10))
                    .contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(instrument.L("Закрыть записи", "Close recordings"))
                .accessibilityIdentifier("recordings-close")
        }.padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 4)
    }
    private func recordingIdentity(_ item: Performance) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            MarqueeRecordingTitle(text: item.title)
                .frame(maxWidth: .infinity, alignment: .leading).clipped()
            Text(instrument.playbackID == item.id
                 ? "\(recordingTime(instrument.elapsed)) / \(recordingTime(item.duration))"
                 : "\(item.date.formatted(date: .abbreviated, time: .shortened)) · \(Int(item.duration)) s")
                .font(.caption.monospacedDigit()).foregroundStyle(Color.secondary)
                .lineLimit(1).truncationMode(.tail)
            if trash, let days = item.trashDaysRemaining() {
                Text(instrument.L("В корзине ещё \(days) дн.", "\(days) \(days == 1 ? "day" : "days") left in trash"))
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("trash-retention-\(item.id.uuidString)")
            }
        }.frame(maxWidth: .infinity, alignment: .leading).clipped()
    }
    private func action(_ symbol: String, _ label: String, destructive: Bool = false,
                        enabled: Bool = true, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            Image(systemName: symbol).font(.system(size: 20))
                .foregroundStyle(destructive ? Color.red : Color.teal)
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).disabled(!enabled).accessibilityLabel(label).help(label)
    }
    private func beginRerecording(_ item: Performance) {
        if instrument.rerecord(item) { onClose() }
    }
    @ViewBuilder private func recordingActions(_ item: Performance) -> some View {
        HStack(spacing: 2) {
            if !trash {
                let isCurrent = instrument.playbackID == item.id
                action(isCurrent ? "stop.fill" : "play.fill", isCurrent ? instrument.L("Остановить", "Stop") : instrument.L("Проиграть", "Play"),
                       enabled: instrument.recording == nil) {
                    if isCurrent { instrument.stopPlayback() } else { instrument.play(item) }
                }.accessibilityIdentifier("recording-play-\(item.id.uuidString)")
                // A long press replaces the take; the passive symbol reference explains the gesture.
                Button {} label: {
                    Image(systemName: "arrow.counterclockwise.circle").font(.system(size: 23)).foregroundStyle(.teal)
                        .overlay { Circle().fill(.red).frame(width: 5, height: 5) }
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }.buttonStyle(.plain)
                    .simultaneousGesture(LongPressGesture(minimumDuration: 0.7).onEnded { _ in beginRerecording(item) })
                    .disabled(instrument.recording != nil || instrument.midi.connected)
                    .accessibilityLabel(instrument.L("Перезаписать: удерживай", "Re-record: hold"))
                    .accessibilityAction(named: Text(instrument.L("Перезаписать", "Re-record"))) { beginRerecording(item) }
                action("trash", instrument.L("В корзину", "Move to trash"), destructive: true) { instrument.moveToTrash(item) }
                    .accessibilityIdentifier("recording-delete-\(item.id.uuidString)")
                Menu {
                    Button { rename = item; title = item.title } label: { Label(instrument.L("Переименовать", "Rename"), systemImage: "pencil") }
                    Button { export(item, saveToFiles: true) } label: { Label(instrument.L("Сохранить WAV в Файлы", "Save WAV to Files"), systemImage: "square.and.arrow.down") }
                        .disabled(exporting)
                    Button { export(item, saveToFiles: false) } label: { Label(instrument.L("Поделиться WAV", "Share WAV"), systemImage: "square.and.arrow.up") }
                        .disabled(exporting)
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 21)).frame(width: 44, height: 44).contentShape(Rectangle())
                }.accessibilityLabel(instrument.L("Другие действия", "More actions"))
                    .accessibilityIdentifier("recording-more-\(item.id.uuidString)")
            } else {
                action("arrow.uturn.backward", instrument.L("Восстановить", "Restore")) { instrument.restore(item) }
                    .accessibilityIdentifier("recording-restore-\(item.id.uuidString)")
                action("trash", instrument.L("Удалить навсегда", "Delete permanently"), destructive: true) { deleting = item }
            }
        }.fixedSize(horizontal: true, vertical: false).disabled(instrument.recordingMutationBusy)
    }
    private var items: [Performance] {
        instrument.performances.filter { trash ? $0.deletedAt != nil : $0.deletedAt == nil }.sorted {
            if $0.date == $1.date { return $0.id.uuidString < $1.id.uuidString }
            return newestFirst ? $0.date > $1.date : $0.date < $1.date
        }
    }
    private func reveal(_ proxy: ScrollViewProxy) {
        // Reopening reveals the actual playing row once. Clock updates never move the list.
        let playing = instrument.playbackID.flatMap { id in items.contains { $0.id == id } ? id : nil }
        guard let id = playing ?? items.first?.id else { return }
        DispatchQueue.main.async { proxy.scrollTo(id, anchor: playing == nil ? .top : .center) }
    }
    private func recordingTime(_ value: Double) -> String {
        let seconds = Int(max(0, value))
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }
    private func export(_ item: Performance, saveToFiles: Bool) {
        exporting = true
        let bank = instrument.audio.kernel
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Result { try WaveExporter.export(item, bank: bank) }
            DispatchQueue.main.async {
                exporting = false
                switch result {
                case .success(let url):
                    if saveToFiles { saving = SharedWave(url: url) } else { shared = SharedWave(url: url) }
                case .failure: instrument.error = instrument.L("Не удалось экспортировать WAV", "Could not export WAV")
                }
            }
        }
    }
}

private struct RecordingPlaybackBackground: View {
    @ObservedObject var instrument: Instrument
    let id: UUID
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60, paused: instrument.playbackID != id)) { _ in
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Color.clear
                    if instrument.playbackID == id {
                        Color.teal.opacity(0.22).frame(width: geometry.size.width * CGFloat(instrument.smoothPlaybackProgress))
                    }
                }
            }
        }.allowsHitTesting(false)
    }
}

// The countdown draws in a screen corner, independently of the recording window's layout.
private struct RecordingUndoNotice: View {
    @ObservedObject var instrument: Instrument
    var body: some View {
        Button { instrument.undoDeletion() } label: {
            HStack(spacing: 8) {
                Text(instrument.L("Отменить", "Undo")).font(.subheadline.weight(.semibold))
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { _ in
                    let remaining = max(0, min(5, instrument.undoDeadline - CACurrentMediaTime()))
                    ZStack {
                        Circle().stroke(Color.teal.opacity(0.2), lineWidth: 2)
                        Circle().trim(from: 0, to: CGFloat(remaining / 5)).stroke(Color.teal, style: StrokeStyle(lineWidth: 2, lineCap: .round)).rotationEffect(.degrees(-90))
                        Text("\(Int(ceil(remaining)))").font(.caption.monospacedDigit())
                    }.frame(width: 24, height: 24)
                }
            }.padding(.horizontal, 12).frame(height: 40)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
                .shadow(color: .black.opacity(0.3), radius: 4, y: 2)
        }.buttonStyle(.plain).disabled(instrument.recordingMutationBusy)
            .accessibilityIdentifier("recording-undo").accessibilityLabel(instrument.L("Отменить удаление", "Undo deletion"))
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [url], applicationActivities: nil) }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

@MainActor extension Instrument {
    func tuningTitle(_ tuning: Tuning) -> String {
        switch tuning {
        case .equal: return L("Равномерный (12-TET)", "Equal temperament (12-TET)")
        case .pythagorean: return L("Пифагорейский", "Pythagorean")
        case .just: return L("Натуральный", "Just intonation")
        case .meantone: return L("Среднетоновый ¼-коммы", "Quarter-comma meantone")
        case .werckmeister: return "Werckmeister III"
        case .kirnberger: return "Kirnberger III"
        case .scala: return "Scala"
        }
    }
    func tuningShortTitle(_ tuning: Tuning) -> String {
        switch tuning {
        case .equal: return "12-TET"
        case .meantone: return L("¼-коммы", "¼-comma")
        case .werckmeister: return L("Веркм. III", "Werck. III")
        case .kirnberger: return L("Кирнб. III", "Kirnb. III")
        default: return tuningTitle(tuning)
        }
    }
    func tuningDetail(_ tuning: Tuning) -> String {
        switch tuning {
        case .equal: return L("12 равных полутонов; одинаковые интервалы во всех тональностях.", "12 equal semitones; identical intervals in every key.")
        case .pythagorean: return L("Чистые квинты 3/2; большие терции звучат шире чистых.", "Pure 3/2 fifths; major thirds are wider than pure thirds.")
        case .just: return L("Простые отношения частот; чистые терции относительно выбранной тоники.", "Simple frequency ratios; pure thirds relative to the selected tonic.")
        case .meantone: return L("Чистые большие терции; квинты сужены на четверть синтонической коммы. Дальние тональности ограничены волчьей квинтой.", "Pure major thirds; fifths narrowed by a quarter syntonic comma. Distant keys are limited by the wolf fifth.")
        case .werckmeister: return L("Четыре суженные квинты замыкают круг. Все тональности доступны и имеют разную окраску.", "Four narrowed fifths close the circle. All keys are usable with different tonal colors.")
        case .kirnberger: return L("Четыре квинты делят синтоническую комму; ещё одна компенсирует схизму. Разная окраска тональностей.", "Four fifths share the syntonic comma; another compensates for the schisma. Keys have different tonal colors.")
        case .scala: return "Scala"
        }
    }
}


