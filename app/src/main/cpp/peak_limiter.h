// SPDX-License-Identifier: GPL-3.0-only
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

/** Stereo-linked lookahead limiter: identical gain in both channels.
 * A sliding peak window followed by a moving gain average guarantees that
 * every delayed sample sees gain <= ceiling / its peak. 1 ms lookahead;
 * smooth 120 ms release. No allocation, locks, or waveform clipping.
 */
class PeakLimiter {
public:
    static constexpr float kCeiling = 0.82F; // -1.72 dBFS, digital sample peak
    void reset(float sampleRate) {
        delay_ = std::clamp(static_cast<int>(std::lround(sampleRate * 0.001F)), 1, kCapacity - 2);
        window_ = delay_ + 1;
        release_ = 1.0F - std::exp(-1.0F / (sampleRate * 0.120F));
        frames_.fill({0.0F, 0.0F}); gains_.fill(1.0F);
        head_ = tail_ = 0; index_ = 0; gain_ = 1.0F; sum_ = window_;
    }
    struct Frame { float left, right; };
    Frame process(float left, float right) {
        // Non-finite samples cannot poison the envelope or reach the DAC.
        if (!std::isfinite(left)) left = 0.0F;
        if (!std::isfinite(right)) right = 0.0F;
        const float peak = std::max(std::abs(left), std::abs(right));
        while (head_ != tail_ && peaks_[head_].index + delay_ < index_)
            head_ = (head_ + 1) % kCapacity;
        while (head_ != tail_) {
            const int back = (tail_ + kCapacity - 1) % kCapacity;
            if (peaks_[back].value > peak) break;
            tail_ = back;
        }
        peaks_[tail_] = {index_, peak}; tail_ = (tail_ + 1) % kCapacity;
        const float maximum = peaks_[head_].value;
        const float target = maximum > kCeiling ? kCeiling / maximum : 1.0F;
        gain_ = std::min(target, gain_ + (1.0F - gain_) * release_);
        const int slot = static_cast<int>(index_ % window_);
        sum_ += static_cast<double>(gain_) - gains_[slot];
        gains_[slot] = gain_;
        frames_[slot] = {left, right};
        const auto delayed = frames_[(slot + 1) % window_];
        const float smoothed = static_cast<float>(std::clamp(sum_ / window_, 0.0, 1.0));
        ++index_;
        return {delayed.left * smoothed, delayed.right * smoothed};
    }
    int latencyFrames() const { return delay_; }
private:
    static constexpr int kCapacity = 1024;
    struct Peak { std::int64_t index = 0; float value = 0; };
    std::array<Peak, kCapacity> peaks_{};
    std::array<Frame, kCapacity> frames_{};
    std::array<float, kCapacity> gains_{};
    int delay_ = 48, window_ = 49, head_ = 0, tail_ = 0;
    std::int64_t index_ = 0;
    float release_ = 0.00017F, gain_ = 1.0F;
    double sum_ = 49.0;
};
