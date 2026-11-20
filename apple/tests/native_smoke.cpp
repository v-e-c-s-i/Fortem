// SPDX-License-Identifier: GPL-3.0-only
#include "FortemDSP.h"
#include <cassert>
#include <cmath>
#include <vector>

int main() {
    auto *engine = FortemCreate();
    FortemConfigure(engine, 48000);
    std::vector<float> signal(48000 * 2);
    FortemRender(engine, signal.data(), 1000);
    for (int i = 0; i < 2000; ++i) assert(signal[i] == 0);
    FortemNoteOn(engine, 69, 440, 0.8F);
    FortemRender(engine, signal.data(), 48000);
    int rising = 0;
    for (int frame = 5001; frame < 45000; ++frame) {
        assert(std::isfinite(signal[frame * 2]));
        assert(std::abs(signal[frame * 2]) <= 0.82F);
        if (signal[(frame - 1) * 2] <= 0 && signal[frame * 2] > 0) ++rising;
    }
    assert(std::abs(rising - 440.0 * 39999 / 48000) < 2);
    // A non-equal frequency change preserves the oscillator phase across callbacks.
    float previous = signal.back();
    FortemRetune(engine, 69, 447.37F);
    FortemRender(engine, signal.data(), 256);
    for (int frame = 0; frame < 256; ++frame) {
        assert(std::abs(signal[frame * 2] - previous) < 0.04F);
        previous = signal[frame * 2];
    }
    FortemRetune(engine, 69, 880);
    FortemRender(engine, signal.data(), 48000);
    rising = 0;
    for (int frame = 24001; frame < 48000; ++frame)
        if (signal[(frame - 1) * 2] <= 0 && signal[frame * 2] > 0) ++rising;
    assert(std::abs(rising - 440) < 2);
    FortemNoteOff(engine, 69);
    FortemRender(engine, signal.data(), 48000);
    for (int frame = 24000; frame < 48000; ++frame) assert(signal[frame * 2] == 0);
    for (int note = 21; note <= 108; ++note) FortemNoteOn(engine, note, 440, 1);
    FortemRender(engine, signal.data(), 48000);
    for (auto sample : signal) assert(std::isfinite(sample) && std::abs(sample) <= 0.82001F);
    FortemReset(engine);
    std::vector<float> left(1000), right(1000);
    FortemRenderPlanar(engine, left.data(), right.data(), 1000);
    for (auto value : left) assert(value == 0);
    FortemDestroy(engine);
}
