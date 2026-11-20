// SPDX-License-Identifier: GPL-3.0-only
import AVFoundation

// The render closure retains this owner until AVAudioEngine releases its source.
// All synthesis state is held in C++; rendering never allocates Swift collections.
final class RenderKernel {
    let pointer: OpaquePointer
    init() { pointer = FortemCreate()! }
    deinit { FortemDestroy(pointer) }
}

@MainActor final class AudioOutput {
    let kernel = RenderKernel()
    private var engine: AVAudioEngine?
    private var observations: [NSObjectProtocol] = []
    private(set) var pianoAvailable = false
    var onInterruption: (() -> Void)?
    var onFailure: ((Error) -> Void)?
    var foreground = false
    init() {
        if let path = Bundle.main.path(forResource: "piano", ofType: nil) {
            pianoAvailable = path.withCString { FortemLoadPiano(kernel.pointer, $0) }
        }
        let center = NotificationCenter.default
        for name in [AVAudioSession.interruptionNotification, AVAudioSession.routeChangeNotification,
                     AVAudioSession.mediaServicesWereResetNotification] {
            observations.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] notification in
                Task { @MainActor [weak self] in self?.sessionChanged(notification) }
            })
        }
    }
    deinit { observations.forEach { NotificationCenter.default.removeObserver($0) } }
    func start() throws {
        guard engine == nil else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .default, options: [])
        try session.setPreferredSampleRate(48000)
        // Give the device render callback 10 ms of headroom during multitouch and drawing.
        try session.setPreferredIOBufferDuration(0.01)
        try session.setActive(true)
        let rate = session.sampleRate
        guard rate.isFinite && rate > 0, let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2) else { throw FortemError.audioUnavailable }
        FortemConfigure(kernel.pointer, rate)
        let renderer = kernel
        let source = AVAudioSourceNode(format: format) { _, _, count, list in
            let buffers = UnsafeMutableAudioBufferListPointer(list)
            guard buffers.count == 2,
                  let left = buffers[0].mData?.assumingMemoryBound(to: Float.self),
                  let right = buffers[1].mData?.assumingMemoryBound(to: Float.self) else { return kAudio_ParamError }
            FortemRenderPlanar(renderer.pointer, left, right, Int32(count))
            return noErr
        }
        let next = AVAudioEngine()
        next.attach(source)
        next.connect(source, to: next.mainMixerNode, format: format)
        next.prepare()
        do { try next.start(); engine = next }
        catch { next.stop(); throw error }
    }
    func stop() {
        engine?.stop(); engine = nil
        FortemReset(kernel.pointer)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    func setPiano(_ value: Bool) { FortemSetTimbre(kernel.pointer, value ? 1 : 0) }
    func on(_ note: Int, frequency: Double, velocity: Float = 0.8) { FortemNoteOn(kernel.pointer, Int32(note), Float(frequency), velocity) }
    func off(_ note: Int) { FortemNoteOff(kernel.pointer, Int32(note)) }
    func retune(_ note: Int, frequency: Double) { FortemRetune(kernel.pointer, Int32(note), Float(frequency)) }
    private func sessionChanged(_ notification: Notification) {
        if notification.name == AVAudioSession.routeChangeNotification {
            let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            // Audio-session category changes do not indicate a hardware disconnect.
            if raw == AVAudioSession.RouteChangeReason.categoryChange.rawValue { return }
        }
        if notification.name == AVAudioSession.interruptionNotification {
            let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if raw == AVAudioSession.InterruptionType.began.rawValue {
                onInterruption?(); stop(); return
            }
            let flags = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            guard flags & AVAudioSession.InterruptionOptions.shouldResume.rawValue != 0 else { return }
        } else {
            // Rebuild against the new hardware rate and never leave a held note stuck.
            onInterruption?(); engine?.stop(); engine = nil; FortemReset(kernel.pointer)
        }
        if foreground { do { try start() } catch { onFailure?(error) } }
    }
}
