// SPDX-License-Identifier: GPL-3.0-only
import Foundation

enum Tuning: String, CaseIterable, Codable, Identifiable {
    case equal, pythagorean, just, meantone, werckmeister, kirnberger, scala
    var id: String { rawValue }
    static var builtIn: [Tuning] { allCases.filter { $0 != .scala } }
    var ratios: [Double] {
        switch self {
        case .equal, .scala: return (0..<12).map { pow(2, Double($0) / 12) }
        case .pythagorean: return [1,256.0/243,9.0/8,32.0/27,81.0/64,4.0/3,729.0/512,3.0/2,128.0/81,27.0/16,16.0/9,243.0/128]
        case .just: return [1,16.0/15,9.0/8,6.0/5,5.0/4,4.0/3,45.0/32,3.0/2,8.0/5,5.0/3,9.0/5,15.0/8]
        case .meantone:
            return [0,7,2,-3,4,-1,6,1,8,3,-2,5].map { Self.normalize(pow(pow(5, 0.25), Double($0))) }
        case .werckmeister, .kirnberger:
            let chain = [0,7,2,9,4,11,6,1,8,3,10,5]
            var result = Array(repeating: 1.0, count: 12), value = 1.0
            for (index, pitch) in chain.enumerated() {
                result[pitch] = Self.normalize(value)
                let divisor: Double
                if self == .werckmeister {
                    divisor = [0,1,2,5].contains(index) ? pow(531441.0/524288, 0.25) : 1
                } else {
                    divisor = index < 4 ? pow(81.0/80, 0.25) : (index == 8 ? 32805.0/32768 : 1)
                }
                value *= 1.5 / divisor
            }
            return result
        }
    }
    private static func normalize(_ ratio: Double) -> Double { ratio / pow(2, floor(log2(ratio))) }
}

struct ScalaScale: Codable, Equatable, Identifiable {
    var id: UUID = UUID()
    var title: String
    var cents: [Double]
    var valid: Bool {
        !title.isEmpty && title.count <= 240 && !title.contains("\n") && cents.count == 12 &&
        cents[0] == 0 && cents.allSatisfy { $0.isFinite } &&
        zip(cents, cents.dropFirst()).allSatisfy { $0 < $1 } && cents[11] < 1200
    }
    static func parse(_ text: String) throws -> ScalaScale {
        guard text.utf8.count <= 65536 else { throw FortemError.invalidScala }
        let lines = text.replacingOccurrences(of: "\u{FEFF}", with: "").components(separatedBy: .newlines)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("!") }
        guard lines.count >= 14,
              lines[1].split(whereSeparator: { $0.isWhitespace }).first == "12" else { throw FortemError.invalidScala }
        let pitches = lines.dropFirst(2).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard pitches.count >= 12 else { throw FortemError.invalidScala }
        var values: [Double] = []
        for line in pitches.prefix(12) {
            guard let token = line.split(whereSeparator: { $0.isWhitespace }).first else { throw FortemError.invalidScala }
            let value: Double
            if token.contains("/") {
                let parts = token.split(separator: "/", omittingEmptySubsequences: false)
                guard parts.count == 2, let a = Int64(parts[0]), let b = Int64(parts[1]), a > 0, b > 0 else { throw FortemError.invalidScala }
                value = 1200 * log2(Double(a) / Double(b))
            } else if token.contains(".") {
                guard let cents = Double(token) else { throw FortemError.invalidScala }; value = cents
            } else {
                guard let a = Int64(token), a > 0 else { throw FortemError.invalidScala }; value = 1200 * log2(Double(a))
            }
            guard value.isFinite else { throw FortemError.invalidScala }; values.append(value)
        }
        guard abs(values[11] - 1200) < 0.001 else { throw FortemError.invalidScala }
        let title = String(lines[0].trimmingCharacters(in: .whitespaces).prefix(240))
        let scale = ScalaScale(title: title.isEmpty ? "Scala" : title, cents: [0] + values.dropLast())
        guard scale.valid else { throw FortemError.invalidScala }; return scale
    }
}

struct InstrumentConfiguration: Codable, Equatable {
    var tuning: Tuning = .equal
    var tonic = 0
    var flats = false
    var reference = 440.0
    var piano = true
    var scala: ScalaScale?
    var valid: Bool { (0..<12).contains(tonic) && reference.isFinite && (300...500).contains(reference) && (tuning != .scala || scala?.valid == true) }
    func frequency(_ midi: Int) -> Double {
        if tuning == .equal { return reference * pow(2, Double(midi - 69) / 12) }
        let distance = midi - 60 - tonic
        let degree = (distance % 12 + 12) % 12
        let octave = (distance - degree) / 12
        let ratios = tuning == .scala ? scala!.cents.map { pow(2, $0 / 1200) } : tuning.ratios
        var multiplier = 1.0
        if flats && [1,3,6,8,10].contains(tonic) {
            switch tuning {
            case .pythagorean: multiplier = 524288.0/531441
            case .meantone: multiplier = 128.0/125
            case .just: multiplier = tonic == 6 ? 2048.0/2025 : 128.0/125
            default: break
            }
        }
        return reference * pow(2, Double(60 + tonic - 69) / 12) * multiplier * ratios[degree] * pow(2, Double(octave))
    }
    func name(_ midi: Int, octave: Bool = true) -> String {
        let names = flats ? ["C","D♭","D","E♭","E","F","G♭","G","A♭","A","B♭","B"] : ["C","C♯","D","D♯","E","F","F♯","G","G♯","A","A♯","B"]
        return names[(midi % 12 + 12) % 12] + (octave ? String(midi / 12 - 1) : "")
    }
}

enum FortemError: Error { case invalidScala, invalidRecording, audioUnavailable, pianoUnavailable, midiUnavailable }

