// SPDX-License-Identifier: GPL-3.0-only

#include "piano_sample_bank.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <string>

namespace {

struct SampleDefinition {
    std::int32_t rootMidiNote;
    const char *fileName;
};

constexpr std::array<SampleDefinition, 30> kDefinitions{{
        {21, "021_A0v07.wav"}, {24, "024_C1v07.wav"},
        {27, "027_D#1v07.wav"}, {30, "030_F#1v07.wav"},
        {33, "033_A1v07.wav"}, {36, "036_C2v07.wav"},
        {39, "039_D#2v07.wav"}, {42, "042_F#2v07.wav"},
        {45, "045_A2v07.wav"}, {48, "048_C3v07.wav"},
        {51, "051_D#3v07.wav"}, {54, "054_F#3v07.wav"},
        {57, "057_A3v07.wav"}, {60, "060_C4v07.wav"},
        {63, "063_D#4v07.wav"}, {66, "066_F#4v07.wav"},
        {69, "069_A4v07.wav"}, {72, "072_C5v07.wav"},
        {75, "075_D#5v07.wav"}, {78, "078_F#5v07.wav"},
        {81, "081_A5v07.wav"}, {84, "084_C6v07.wav"},
        {87, "087_D#6v07.wav"}, {90, "090_F#6v07.wav"},
        {93, "093_A6v07.wav"}, {96, "096_C7v07.wav"},
        {99, "099_D#7v07.wav"}, {102, "102_F#7v07.wav"},
        {105, "105_A7v07.wav"}, {108, "108_C8v07.wav"},
}};

// Поклавишная коррекция из Accurate-Salamander flat.Recommended для слоя v07.
// Значения малы, но сохраняют ровный баланс банка от A0 до C8.
constexpr std::array<float, 88> kFlatVolumesDb{{
        +0.00F, +0.00F, -0.10F, +0.00F, +0.10F, -0.10F, +0.00F, +0.10F,
        +0.00F, +0.00F, +0.10F, -0.10F, +0.00F, +0.10F, -0.20F, +0.00F,
        +0.20F, -0.20F, +0.00F, +0.10F, -0.20F, +0.00F, +0.10F, -0.10F,
        +0.00F, +0.10F, -0.10F, +0.00F, +0.10F, -0.10F, +0.00F, +0.20F,
        -0.10F, +0.00F, +0.20F, -0.10F, +0.00F, +0.20F, -0.20F, +0.00F,
        +0.20F, -0.20F, +0.00F, +0.30F, -0.20F, +0.00F, +0.10F, -0.20F,
        +0.00F, +0.17F, -0.17F, +0.00F, +0.13F, -0.13F, +0.00F, +0.07F,
        -0.07F, +0.00F, +0.10F, +0.00F, +0.00F, -0.10F, +0.10F, +0.00F,
        -0.27F, +0.17F, +0.00F, -0.37F, +0.27F, +0.00F, -0.40F, +0.30F,
        +0.00F, -0.47F, +0.47F, +0.00F, -0.40F, +0.50F, +0.00F, -0.40F,
        +0.50F, +0.00F, -0.60F, +0.50F, +0.00F, -0.67F, +0.67F, +0.00F,
}};

std::uint16_t readU16(const std::uint8_t *bytes) {
    return static_cast<std::uint16_t>(bytes[0]) |
           (static_cast<std::uint16_t>(bytes[1]) << 8U);
}

std::uint32_t readU32(const std::uint8_t *bytes) {
    return static_cast<std::uint32_t>(bytes[0]) |
           (static_cast<std::uint32_t>(bytes[1]) << 8U) |
           (static_cast<std::uint32_t>(bytes[2]) << 16U) |
           (static_cast<std::uint32_t>(bytes[3]) << 24U);
}

std::int32_t readSigned24(const std::uint8_t *bytes) {
    std::int32_t value = static_cast<std::int32_t>(bytes[0]) |
                         (static_cast<std::int32_t>(bytes[1]) << 8) |
                         (static_cast<std::int32_t>(bytes[2]) << 16);
    if ((value & 0x00800000) != 0) {
        value |= static_cast<std::int32_t>(0xFF000000);
    }
    return value;
}

bool hasTag(const std::uint8_t *bytes, const char *tag) {
    return std::memcmp(bytes, tag, 4) == 0;
}

bool parseWave(
        const std::uint8_t *bytes,
        std::size_t byteCount,
        std::int32_t rootMidiNote,
        PianoSampleBank::SampleView &view) {
    if (byteCount < 12 || !hasTag(bytes, "RIFF") || !hasTag(bytes + 8, "WAVE")) {
        return false;
    }

    std::uint16_t audioFormat = 0;
    std::uint16_t extensibleSubFormat = 0;
    std::uint16_t channelCount = 0;
    std::uint16_t bitsPerSample = 0;
    std::uint16_t blockAlign = 0;
    std::uint32_t sampleRate = 0;
    const std::uint8_t *pcm = nullptr;
    std::uint32_t pcmByteCount = 0;

    std::size_t cursor = 12;
    while (cursor + 8 <= byteCount) {
        const auto *chunk = bytes + cursor;
        const std::uint32_t chunkSize = readU32(chunk + 4);
        const std::size_t dataStart = cursor + 8;
        if (dataStart + chunkSize > byteCount) {
            return false;
        }

        if (hasTag(chunk, "fmt ") && chunkSize >= 16) {
            audioFormat = readU16(bytes + dataStart);
            channelCount = readU16(bytes + dataStart + 2);
            sampleRate = readU32(bytes + dataStart + 4);
            blockAlign = readU16(bytes + dataStart + 12);
            bitsPerSample = readU16(bytes + dataStart + 14);
            // FFmpeg сохраняет 24-битный PCM как WAVE_FORMAT_EXTENSIBLE.
            // Первые два байта GUID подформата равны обычному коду PCM (1).
            if (audioFormat == 0xFFFE && chunkSize >= 40) {
                extensibleSubFormat = readU16(bytes + dataStart + 24);
            }
        } else if (hasTag(chunk, "data")) {
            pcm = bytes + dataStart;
            pcmByteCount = chunkSize;
        }

        // RIFF дополняет нечётный блок одним выравнивающим байтом.
        cursor = dataStart + chunkSize + (chunkSize & 1U);
    }

    const bool isPcm = audioFormat == 1 ||
            (audioFormat == 0xFFFE && extensibleSubFormat == 1);
    if (!isPcm || channelCount != 2 || bitsPerSample != 24 ||
        blockAlign != 6 || sampleRate == 0 || pcm == nullptr || pcmByteCount < 12) {
        return false;
    }

    view.pcm = pcm;
    view.frameCount = pcmByteCount / blockAlign;
    view.sampleRate = sampleRate;
    view.rootMidiNote = rootMidiNote;
    return true;
}

} // namespace

PianoSampleBank::AssetSample::~AssetSample() {
    if (asset != nullptr) {
        AAsset_close(asset);
    }
}

bool PianoSampleBank::load(AAssetManager *assetManager) {
    if (assetManager == nullptr) {
        return false;
    }

    std::array<std::unique_ptr<AssetSample>, kSampleCount> loaded{};
    for (std::size_t index = 0; index < kDefinitions.size(); ++index) {
        const auto &definition = kDefinitions[index];
        const std::string path = std::string("piano/") + definition.fileName;

        auto sample = std::make_unique<AssetSample>();
        sample->asset = AAssetManager_open(assetManager, path.c_str(), AASSET_MODE_BUFFER);
        if (sample->asset == nullptr) {
            return false;
        }

        const auto length = static_cast<std::size_t>(AAsset_getLength64(sample->asset));
        const auto *bytes = static_cast<const std::uint8_t *>(AAsset_getBuffer(sample->asset));
        if (bytes == nullptr) {
            // Резервный путь нужен, если сборщик всё же упаковал WAV со сжатием.
            sample->ownedBytes.resize(length);
            const int64_t readCount = AAsset_read(
                    sample->asset,
                    sample->ownedBytes.data(),
                    sample->ownedBytes.size());
            if (readCount != static_cast<int64_t>(length)) {
                return false;
            }
            bytes = sample->ownedBytes.data();
        }

        if (!parseWave(bytes, length, definition.rootMidiNote, sample->view)) {
            return false;
        }
        loaded[index] = std::move(sample);
    }

    samples_ = std::move(loaded);
    return true;
}

bool PianoSampleBank::available() const {
    return samples_.front() != nullptr && samples_.back() != nullptr;
}

int PianoSampleBank::sampleIndexForMidiNote(std::int32_t midiNote) const {
    if (midiNote <= kDefinitions.front().rootMidiNote) {
        return 0;
    }
    if (midiNote >= kDefinitions.back().rootMidiNote) {
        return static_cast<int>(kDefinitions.size() - 1);
    }

    const auto upper = std::lower_bound(
            kDefinitions.begin(),
            kDefinitions.end(),
            midiNote,
            [](const SampleDefinition &definition, std::int32_t note) {
                return definition.rootMidiNote < note;
            });
    const auto upperIndex = static_cast<std::size_t>(upper - kDefinitions.begin());
    const auto lowerIndex = upperIndex - 1;
    const auto lowerDistance = midiNote - kDefinitions[lowerIndex].rootMidiNote;
    const auto upperDistance = kDefinitions[upperIndex].rootMidiNote - midiNote;
    return static_cast<int>(lowerDistance <= upperDistance ? lowerIndex : upperIndex);
}

float PianoSampleBank::gainForMidiNote(std::int32_t midiNote) const {
    const auto index = static_cast<std::size_t>(std::clamp(midiNote, 21, 108) - 21);
    return std::pow(10.0F, kFlatVolumesDb[index] / 20.0F);
}

const PianoSampleBank::SampleView *PianoSampleBank::sampleAt(int index) const {
    if (index < 0 || index >= static_cast<int>(samples_.size()) || samples_[index] == nullptr) {
        return nullptr;
    }
    return &samples_[index]->view;
}

PianoSampleBank::StereoFrame PianoSampleBank::readLinear(
        const SampleView &sample,
        double position) const {
    if (position < 0.0 || position + 1.0 >= static_cast<double>(sample.frameCount)) {
        return {};
    }

    const auto firstFrame = static_cast<std::uint64_t>(position);
    const auto secondFrame = firstFrame + 1;
    const float fraction = static_cast<float>(position - static_cast<double>(firstFrame));
    const auto *first = sample.pcm + firstFrame * 6;
    const auto *second = sample.pcm + secondFrame * 6;

    constexpr float kScale = 1.0F / 8388608.0F;
    const float leftA = static_cast<float>(readSigned24(first)) * kScale;
    const float rightA = static_cast<float>(readSigned24(first + 3)) * kScale;
    const float leftB = static_cast<float>(readSigned24(second)) * kScale;
    const float rightB = static_cast<float>(readSigned24(second + 3)) * kScale;

    return {
            leftA + (leftB - leftA) * fraction,
            rightA + (rightB - rightA) * fraction,
    };
}
