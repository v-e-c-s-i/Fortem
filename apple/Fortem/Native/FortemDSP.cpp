// SPDX-License-Identifier: GPL-3.0-only

#include "piano_sample_bank.h"
#include "peak_limiter.h"

#include "FortemDSP.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>


namespace {

constexpr int32_t kChannelCount = 2;
constexpr int32_t kMaximumVoices = 48;
constexpr int32_t kFreeVoice = -1;
constexpr int32_t kReservedVoice = -2;
constexpr double kPianoStartFrame = 768.0;
constexpr float kPi = 3.14159265358979323846F;
constexpr float kMasterGain = 0.36F;
enum class Timbre : int32_t {
    PureTone = 0,
    Piano = 1,
};

/**
 * Одна полифоническая голосовая ячейка.
 *
 * Интерфейс меняет только атомарные команды. Фаза, позиция WAV и огибающая
 * принадлежат звуковому потоку, поэтому callback не ждёт блокировок.
 */
struct Voice {
    std::atomic<int32_t> note{kFreeVoice};
    std::atomic<float> targetFrequencyHz{440.0F};
    std::atomic<float> velocity{0.8F};
    std::atomic<bool> gate{false};
    std::atomic<bool> fastRelease{false};
    std::atomic<int32_t> requestedTimbre{static_cast<int32_t>(Timbre::PureTone)};
    std::atomic<uint32_t> triggerGeneration{0};

    uint32_t renderedGeneration = 0;
    Timbre renderedTimbre = Timbre::PureTone;
    float currentFrequencyHz = 440.0F;
    float phase = 0.0F;
    float envelope = 0.0F;
    double samplePosition = kPianoStartFrame;
    int sampleIndex = -1;
    float pianoGain = 1.0F;
    double pianoRootFrequency = 440.0;
    double pianoSourceRateRatio = 1.0;
};

class SynthEngine final {
public:
    bool loadPianoSamples(const char *directory) {
        if (pianoSamples_->available()) return true;
        return pianoSamples_->load(directory);
    }
    // Caller must stop the AVAudioEngine before reset/configuration.
    void reset() {
        for (auto &voice : voices_) releaseVoice(voice);
        limiter_.reset(sampleRate_);
    }
    void setOutputEnabled(bool enabled) {
        outputEnabled_.store(enabled, std::memory_order_relaxed);
    }

    void setTimbre(int32_t rawTimbre) {
        Timbre requested = rawTimbre == static_cast<int32_t>(Timbre::Piano)
                ? Timbre::Piano
                : Timbre::PureTone;
        if (requested == Timbre::Piano && !pianoSamples_->available()) {
            requested = Timbre::PureTone;
        }

        const int32_t requestedValue = static_cast<int32_t>(requested);
        const int32_t previousValue = currentTimbre_.exchange(
                requestedValue,
                std::memory_order_acq_rel);
        if (previousValue == requestedValue) {
            // Повторная публикация того же тембра не должна запускать атаку заново.
            return;
        }

        for (auto &voice : voices_) {
            if (voice.note.load(std::memory_order_acquire) >= 0 &&
                voice.gate.load(std::memory_order_acquire)) {
                voice.requestedTimbre.store(
                        requestedValue,
                        std::memory_order_relaxed);
                voice.triggerGeneration.fetch_add(1, std::memory_order_release);
            }
        }
    }

    void noteOn(int32_t note, float frequencyHz, float velocity) {
        if (!std::isfinite(frequencyHz) || frequencyHz <= 0.0F) {
            return;
        }

        const auto timbre = currentTimbre_.load(std::memory_order_acquire);

        // Старый экземпляр той же ноты не сбрасывается в начало WAV: такой
        // скачок формы сигнала и создавал слышимый щелчок. Он быстро затихает,
        // пока новая атака запускается в отдельной полифонической ячейке.
        for (auto &voice : voices_) {
            if (voice.note.load(std::memory_order_acquire) == note) {
                voice.fastRelease.store(true, std::memory_order_release);
                voice.gate.store(false, std::memory_order_release);
            }
        }

        for (auto &voice : voices_) {
            int32_t expected = kFreeVoice;
            if (voice.note.compare_exchange_strong(
                        expected,
                        kReservedVoice,
                        std::memory_order_acq_rel)) {
                prepareTrigger(voice, frequencyHz, velocity, timbre);
                voice.note.store(note, std::memory_order_release);
                return;
            }
        }

        // При исчерпании 48 голосов новое нажатие пропускается без блокировки потока.
    }

    void retune(int32_t note, float frequencyHz) {
        if (!std::isfinite(frequencyHz) || frequencyHz <= 0.0F) {
            return;
        }

        // Позиция WAV и фаза не сбрасываются: удерживаемая нота перестраивается плавно.
        for (auto &voice : voices_) {
            if (voice.note.load(std::memory_order_acquire) == note) {
                voice.targetFrequencyHz.store(frequencyHz, std::memory_order_relaxed);
            }
        }
    }

    void noteOff(int32_t note) {
        for (auto &voice : voices_) {
            if (voice.note.load(std::memory_order_acquire) == note) {
                voice.gate.store(false, std::memory_order_release);
            }
        }
    }

    void renderLive(float *output, int32_t numFrames) {
        render(output, 2, numFrames);
        const float target = outputEnabled_.load(std::memory_order_relaxed) ? 1.0F : 0.0F;
        for (int32_t frame = 0; frame < numFrames; ++frame) {
            outputGain_ = target > outputGain_ ? std::min(target, outputGain_ + outputGainStep_)
                                               : std::max(target, outputGain_ - outputGainStep_);
            output[frame * 2] *= outputGain_;
            output[frame * 2 + 1] *= outputGain_;
        }
    }

    // Offline export uses exactly the live synthesis path, its own voices and
    // limiter, and the immutable sample bank shared with the live engine.
    void configureSampleRate(float sampleRate) {
        sampleRate_ = sampleRate;
        limiter_.reset(sampleRate_);
        // При новом потоке начинаем сразу с выбранного уровня, без всплеска звука.
        outputGain_ = outputEnabled_.load(std::memory_order_relaxed) ? 1.0F : 0.0F;
        outputGainStep_ = 1.0F / std::max(1.0F, sampleRate_ * 0.005F);
        pureToneReleaseMultiplier_ = releaseMultiplierForSeconds(0.12F);
        pianoReleaseMultiplier_ = releaseMultiplierForSeconds(1.0F);
        retriggerReleaseMultiplier_ = releaseMultiplierForSeconds(0.008F);
        // Перестройка за несколько десятков миллисекунд не создаёт ложного
        // ощущения новой атаки, но остаётся достаточно быстрой для сравнения строев.
        tuningSmoothingFactor_ = 1.0F - std::exp(
                -1.0F / std::max(1.0F, sampleRate_ * 0.018F));

    }
    std::shared_ptr<PianoSampleBank> sampleBank() const { return pianoSamples_; }
    void useSampleBank(std::shared_ptr<PianoSampleBank> bank) { pianoSamples_ = std::move(bank); }
    void render(float *output, int32_t channelCount, int32_t numFrames) {
        const float pureAttackIncrement = 1.0F / std::max(1.0F, sampleRate_ * 0.004F);
        const float pianoAttackIncrement = 1.0F / std::max(1.0F, sampleRate_ * 0.0005F);

        for (int32_t frame = 0; frame < numFrames; ++frame) {
            float mixedLeft = 0.0F;
            float mixedRight = 0.0F;

            for (auto &voice : voices_) {
                const int32_t note = voice.note.load(std::memory_order_acquire);
                if (note < 0) {
                    continue;
                }

                const uint32_t generation =
                        voice.triggerGeneration.load(std::memory_order_acquire);
                if (generation != voice.renderedGeneration) {
                    resetRenderedVoice(voice, note, generation);
                }

                const bool gate = voice.gate.load(std::memory_order_acquire);
                if (gate) {
                    const float increment = voice.renderedTimbre == Timbre::Piano
                            ? pianoAttackIncrement
                            : pureAttackIncrement;
                    voice.envelope = std::min(1.0F, voice.envelope + increment);
                } else {
                    const bool fastRelease =
                            voice.fastRelease.load(std::memory_order_acquire);
                    const float multiplier = fastRelease
                            ? retriggerReleaseMultiplier_
                            : (voice.renderedTimbre == Timbre::Piano
                                    ? pianoReleaseMultiplier_
                                    : pureToneReleaseMultiplier_);
                    voice.envelope *= multiplier;
                    if (voice.envelope < 0.0001F) {
                        releaseVoice(voice);
                        continue;
                    }
                }

                const float targetFrequency =
                        voice.targetFrequencyHz.load(std::memory_order_relaxed);
                voice.currentFrequencyHz +=
                        (targetFrequency - voice.currentFrequencyHz) * tuningSmoothingFactor_;
                const float gain = voice.velocity.load(std::memory_order_relaxed) * voice.envelope;

                if (voice.renderedTimbre == Timbre::Piano) {
                    const auto *sample = pianoSamples_->sampleAt(voice.sampleIndex);
                    if (sample == nullptr ||
                        voice.samplePosition + 1.0 >= static_cast<double>(sample->frameCount)) {
                        releaseVoice(voice);
                        continue;
                    }

                    const auto pianoFrame = pianoSamples_->readLinear(*sample, voice.samplePosition);
                    mixedLeft += pianoFrame.left * gain * voice.pianoGain;
                    mixedRight += pianoFrame.right * gain * voice.pianoGain;

                    // Constant sample metadata is computed once per trigger.
                    // Preserve the old division/multiplication order exactly.
                    const double pitchRatio = voice.currentFrequencyHz / voice.pianoRootFrequency;
                    voice.samplePosition += voice.pianoSourceRateRatio * pitchRatio;
                } else {
                    // Синус немного ослаблен относительно записанного рояля, чтобы
                    // переключение тембра не сопровождалось резким скачком громкости.
                    const float oscillator = std::sin(voice.phase) * 0.42F;
                    mixedLeft += oscillator * gain;
                    mixedRight += oscillator * gain;

                    voice.phase += (2.0F * kPi * voice.currentFrequencyHz) / sampleRate_;
                    if (voice.phase >= 2.0F * kPi) {
                        voice.phase -= 2.0F * kPi;
                    }
                }
            }

            // Одинаковое плавное ослабление каналов сохраняет стереокартину.
            // Ниже порога отсчёты не меняются (кроме задержки 1 мс).
            const auto limited = limiter_.process(mixedLeft * kMasterGain, mixedRight * kMasterGain);
            const float left = limited.left;
            const float right = limited.right;
            if (channelCount == 1) {
                output[frame] = (left + right) * 0.5F;
            } else {
                output[frame * channelCount] = left;
                output[frame * channelCount + 1] = right;
                for (int32_t channel = 2; channel < channelCount; ++channel) {
                    output[frame * channelCount + channel] = (left + right) * 0.5F;
                }
            }
        }

    }

private:
    void prepareTrigger(Voice &voice, float frequencyHz, float velocity, int32_t timbre) {
        voice.targetFrequencyHz.store(frequencyHz, std::memory_order_relaxed);
        voice.velocity.store(std::clamp(velocity, 0.0F, 1.0F), std::memory_order_relaxed);
        voice.requestedTimbre.store(timbre, std::memory_order_relaxed);
        voice.fastRelease.store(false, std::memory_order_relaxed);
        voice.gate.store(true, std::memory_order_relaxed);
        voice.triggerGeneration.fetch_add(1, std::memory_order_release);
    }

    void resetRenderedVoice(Voice &voice, int32_t note, uint32_t generation) {
        const auto requested = static_cast<Timbre>(
                voice.requestedTimbre.load(std::memory_order_relaxed));
        voice.renderedTimbre = requested == Timbre::Piano && pianoSamples_->available()
                ? Timbre::Piano
                : Timbre::PureTone;
        voice.currentFrequencyHz = voice.targetFrequencyHz.load(std::memory_order_relaxed);
        voice.phase = 0.0F;
        voice.envelope = 0.0F;
        voice.sampleIndex = pianoSamples_->sampleIndexForMidiNote(note);
        voice.pianoGain = pianoSamples_->gainForMidiNote(note);
        if (const auto *sample = pianoSamples_->sampleAt(voice.sampleIndex)) {
            voice.pianoRootFrequency = 440.0 * std::pow(2.0,
                    (static_cast<double>(sample->rootMidiNote) - 69.0) / 12.0);
            voice.pianoSourceRateRatio = static_cast<double>(sample->sampleRate) / sampleRate_;
        }
        voice.samplePosition = kPianoStartFrame;
        voice.renderedGeneration = generation;
    }

    static void releaseVoice(Voice &voice) {
        voice.envelope = 0.0F;
        voice.gate.store(false, std::memory_order_relaxed);
        voice.fastRelease.store(false, std::memory_order_relaxed);
        voice.note.store(kFreeVoice, std::memory_order_release);
    }

    [[nodiscard]] float releaseMultiplierForSeconds(float seconds) const {
        return std::exp(std::log(0.0001F) / std::max(1.0F, sampleRate_ * seconds));
    }

    std::array<Voice, kMaximumVoices> voices_{};
    std::shared_ptr<PianoSampleBank> pianoSamples_ = std::make_shared<PianoSampleBank>();
    PeakLimiter limiter_;
    std::atomic<bool> outputEnabled_{true};
    float outputGain_ = 1.0F; // Только звуковой callback пишет плавный уровень.
    float outputGainStep_ = 1.0F / 240.0F;
    std::atomic<int32_t> currentTimbre_{static_cast<int32_t>(Timbre::PureTone)};
    float sampleRate_ = 48000.0F;
    float pureToneReleaseMultiplier_ = 0.998F;
    float pianoReleaseMultiplier_ = 0.9998F;
    float retriggerReleaseMultiplier_ = 0.98F;
    float tuningSmoothingFactor_ = 1.0F;
};

} // namespace

struct FortemDSP { SynthEngine engine; };
extern "C" {
FortemDSP *FortemCreate(void) { return new FortemDSP(); }
void FortemDestroy(FortemDSP *dsp) { delete dsp; }
bool FortemLoadPiano(FortemDSP *dsp, const char *directory) { return dsp->engine.loadPianoSamples(directory); }
void FortemConfigure(FortemDSP *dsp, double sampleRate) { dsp->engine.configureSampleRate(static_cast<float>(sampleRate)); }
void FortemReset(FortemDSP *dsp) { dsp->engine.reset(); }
void FortemSetTimbre(FortemDSP *dsp, int timbre) { dsp->engine.setTimbre(timbre); }
void FortemSetOutputEnabled(FortemDSP *dsp, bool enabled) { dsp->engine.setOutputEnabled(enabled); }
void FortemNoteOn(FortemDSP *dsp, int note, float frequency, float velocity) { dsp->engine.noteOn(note, frequency, velocity); }
void FortemRetune(FortemDSP *dsp, int note, float frequency) { dsp->engine.retune(note, frequency); }
void FortemNoteOff(FortemDSP *dsp, int note) { dsp->engine.noteOff(note); }
void FortemRender(FortemDSP *dsp, float *output, int frames) { dsp->engine.renderLive(output, frames); }
void FortemRenderPlanar(FortemDSP *dsp, float *left, float *right, int frames) {
    float scratch[256];
    for (int offset = 0; offset < frames; offset += 128) {
        const int count = std::min(128, frames - offset);
        dsp->engine.renderLive(scratch, count);
        for (int i = 0; i < count; ++i) {
            left[offset + i] = scratch[2 * i];
            right[offset + i] = scratch[2 * i + 1];
        }
    }
}
void FortemSharePiano(FortemDSP *destination, FortemDSP *source) { destination->engine.useSampleBank(source->engine.sampleBank()); }
}
