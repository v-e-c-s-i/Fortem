// SPDX-License-Identifier: GPL-3.0-only

#pragma once



#include <array>
#include <cstdint>
#include <memory>


/**
 * Opens the existing 30 Salamander 24-bit stereo WAV files from the app bundle.
 *
 * Каждый файл является опорой для трёх соседних клавиш. Это сохраняет характер
 * записи и ограничивает транспонирование максимум одним полутоном.
 */
class PianoSampleBank final {
public:
    struct SampleView {
        const std::uint8_t *pcm = nullptr;
        std::uint64_t frameCount = 0;
        std::uint32_t sampleRate = 0;
        std::int32_t rootMidiNote = 69;
    };

    struct StereoFrame {
        float left = 0.0F;
        float right = 0.0F;
    };

    bool load(const char *directory);
    [[nodiscard]] bool available() const;
    [[nodiscard]] int sampleIndexForMidiNote(std::int32_t midiNote) const;
    [[nodiscard]] float gainForMidiNote(std::int32_t midiNote) const;
    [[nodiscard]] const SampleView *sampleAt(int index) const;
    [[nodiscard]] StereoFrame readLinear(const SampleView &sample, double position) const;

private:
    static constexpr std::size_t kSampleCount = 30;

    struct AssetSample {
        // Own fully loaded PCM storage before the real-time renderer starts.
        std::unique_ptr<std::uint8_t[]> bytes;
        std::size_t length = 0;
        SampleView view;

        AssetSample() = default;
        AssetSample(const AssetSample &) = delete;
        AssetSample &operator=(const AssetSample &) = delete;
    };

    std::array<std::unique_ptr<AssetSample>, kSampleCount> samples_{};
};


