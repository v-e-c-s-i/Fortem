// SPDX-License-Identifier: GPL-3.0-only
#include "FortemDSP.h"
#include "piano_sample_bank.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

namespace {
// Artificial fixtures exercise the piano path without the bundled recordings.
const std::array<const char *, 30> names{{
    "021_A0v07.wav", "024_C1v07.wav", "027_D#1v07.wav", "030_F#1v07.wav",
    "033_A1v07.wav", "036_C2v07.wav", "039_D#2v07.wav", "042_F#2v07.wav",
    "045_A2v07.wav", "048_C3v07.wav", "051_D#3v07.wav", "054_F#3v07.wav",
    "057_A3v07.wav", "060_C4v07.wav", "063_D#4v07.wav", "066_F#4v07.wav",
    "069_A4v07.wav", "072_C5v07.wav", "075_D#5v07.wav", "078_F#5v07.wav",
    "081_A5v07.wav", "084_C6v07.wav", "087_D#6v07.wav", "090_F#6v07.wav",
    "093_A6v07.wav", "096_C7v07.wav", "099_D#7v07.wav", "102_F#7v07.wav",
    "105_A7v07.wav", "108_C8v07.wav"
}};

void integer(std::ostream &out, std::uint32_t value, int bytes) {
    for (int i = 0; i < bytes; ++i) out.put(static_cast<char>((value >> (8 * i)) & 255));
}
void fixture(const std::filesystem::path &path) {
    constexpr int frames = 24000;
    std::ofstream out(path, std::ios::binary);
    out.write("RIFF", 4); integer(out, 36 + frames * 6, 4); out.write("WAVEfmt ", 8);
    integer(out, 16, 4); integer(out, 1, 2); integer(out, 2, 2);
    integer(out, 48000, 4); integer(out, 48000 * 6, 4);
    integer(out, 6, 2); integer(out, 24, 2);
    out.write("data", 4); integer(out, frames * 6, 4);
    for (int frame = 0; frame < frames; ++frame) {
        const auto value = static_cast<std::int32_t>(2097152 * std::sin(frame * 440 * 2 * 3.141592653589793 / 48000));
        integer(out, static_cast<std::uint32_t>(value), 3);
        integer(out, static_cast<std::uint32_t>(-value / 2), 3);
    }
    assert(out.good());
}

// Rendering must be independent of callback length, including non-128 multiples.
std::vector<float> render(FortemDSP *engine, int rate, bool planar) {
    FortemReset(engine); FortemConfigure(engine, rate);
    FortemNoteOn(engine, 69, 440, 0.8F);
    const int frames = rate / 4;
    std::vector<float> signal(frames * 2);
    if (!planar) FortemRender(engine, signal.data(), frames);
    else {
        std::vector<float> left(513), right(513);
        constexpr std::array<int, 4> sizes{{127, 256, 480, 513}};
        int block = 0;
        for (int offset = 0; offset < frames; ++block) {
            const int count = std::min(sizes[block % sizes.size()], frames - offset);
            FortemRenderPlanar(engine, left.data(), right.data(), count);
            for (int i = 0; i < count; ++i) {
                signal[2 * (offset + i)] = left[i];
                signal[2 * (offset + i) + 1] = right[i];
            }
            offset += count;
        }
    }
    float peak = 0;
    int crossings = 0;
    for (int i = 0; i < frames; ++i) {
        assert(std::isfinite(signal[2 * i]) && std::isfinite(signal[2 * i + 1]));
        peak = std::max(peak, std::abs(signal[2 * i]));
        assert(std::abs(signal[2 * i + 1] + signal[2 * i] / 2) < 0.000001F);
        if (i > rate / 20 && signal[2 * (i - 1)] <= 0 && signal[2 * i] > 0) ++crossings;
    }
    assert(peak > 0.05F && peak < 0.1F);
    assert(std::abs(crossings - 440 * 0.2) <= 1);
    return signal;
}
}

int main() {
    auto pattern = (std::filesystem::temp_directory_path() / "fortem-piano-XXXXXX").string();
    const char *directory = mkdtemp(pattern.data()); assert(directory);
    struct Cleanup { std::filesystem::path path; ~Cleanup() { std::filesystem::remove_all(path); } } cleanup{directory};
    for (const auto *name : names) fixture(cleanup.path / name);
    auto *engine = FortemCreate();
    assert(FortemLoadPiano(engine, directory));
    FortemSetTimbre(engine, 1);
    for (int rate : {44100, 48000}) {
        const auto baseline = render(engine, rate, false);
        assert(baseline == render(engine, rate, true));
    }
    // Loading owns a complete snapshot; rendering never needs the files again.
    const auto baseline = render(engine, 48000, false);
    for (const auto *name : names) {
        std::fstream file(cleanup.path / name, std::ios::in | std::ios::out | std::ios::binary);
        file.seekp(44);
        const std::vector<char> silence(24000 * 6, 0);
        file.write(silence.data(), static_cast<std::streamsize>(silence.size()));
        assert(file.good());
    }
    assert(baseline == render(engine, 48000, true));
    FortemDestroy(engine);
}
