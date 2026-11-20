// SPDX-License-Identifier: GPL-3.0-only

#include "piano_sample_bank.h"
#include "peak_limiter.h"

#include <android/asset_manager_jni.h>
#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>

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

class SynthEngine final :
        public oboe::AudioStreamDataCallback,
        public oboe::AudioStreamErrorCallback,
        public std::enable_shared_from_this<SynthEngine> {
public:
    bool loadPianoSamples(AAssetManager *assetManager) {
        // Загрузка выполняется до запуска потока, поэтому банк после публикации
        // остаётся неизменяемым и безопасно читается из callback.
        if (pianoSamples_->available()) return true;
        return pianoSamples_->load(assetManager);
    }

    bool start() {
        if (stream_ != nullptr && !disconnected_.load(std::memory_order_acquire)) {
            return true;
        }

        if (stream_ != nullptr) {
            stream_->close();
            stream_.reset();
        }
        disconnected_.store(false, std::memory_order_release);

        oboe::AudioStreamBuilder builder;
        configureBuilder(builder, oboe::SharingMode::Exclusive);

        auto result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            // Некоторые телефоны не разрешают эксклюзивный поток; общий режим
            // остаётся рабочим резервом с теми же параметрами формата.
            stream_.reset();
            configureBuilder(builder, oboe::SharingMode::Shared);
            result = builder.openStream(stream_);
        }

        if (result != oboe::Result::OK || stream_ == nullptr) {
            stream_.reset();
            return false;
        }

        configureSampleRate(static_cast<float>(stream_->getSampleRate()));

        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            stream_->close();
            stream_.reset();
            return false;
        }

        running_.store(true, std::memory_order_release);
        return true;
    }

    void stop() {
        running_.store(false, std::memory_order_release);

        if (stream_ != nullptr) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }

        for (auto &voice : voices_) {
            voice.gate.store(false, std::memory_order_release);
            voice.fastRelease.store(false, std::memory_order_release);
            voice.note.store(kFreeVoice, std::memory_order_release);
        }
    }

    [[nodiscard]] bool isRunning() const {
        return running_.load(std::memory_order_acquire);
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

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override {
        auto *output = static_cast<float *>(audioData);
        const int32_t channels = audioStream->getChannelCount();
        render(output, channels, numFrames);
        // Короткий линейный переход (~5 мс) исключает щелчок при переключении.
        // Голоса продолжают отсчёт, поэтому возврат звука не повторяет атаку.
        const float target = outputEnabled_.load(std::memory_order_relaxed) ? 1.0F : 0.0F;
        if (target == 1.0F && outputGain_ == 1.0F) return oboe::DataCallbackResult::Continue;
        for (int32_t frame = 0; frame < numFrames; ++frame) {
            outputGain_ = target > outputGain_
                ? std::min(target, outputGain_ + outputGainStep_)
                : std::max(target, outputGain_ - outputGainStep_);
            if (outputGain_ == 1.0F) continue; // Обычный звук побитово прежний.
            for (int32_t channel = 0; channel < channels; ++channel)
                output[frame * channels + channel] *= outputGain_;
        }
        return oboe::DataCallbackResult::Continue;
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

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result) override {
        running_.store(false, std::memory_order_release);
        disconnected_.store(true, std::memory_order_release);
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

    void configureBuilder(oboe::AudioStreamBuilder &builder, oboe::SharingMode sharingMode) {
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(sharingMode);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(kChannelCount);
        builder.setUsage(oboe::Usage::Game);
        builder.setContentType(oboe::ContentType::Music);
        builder.setDataCallback(shared_from_this());
        builder.setErrorCallback(shared_from_this());
    }

    std::array<Voice, kMaximumVoices> voices_{};
    std::shared_ptr<PianoSampleBank> pianoSamples_ = std::make_shared<PianoSampleBank>();
    PeakLimiter limiter_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<bool> outputEnabled_{true};
    float outputGain_ = 1.0F; // Только звуковой callback пишет плавный уровень.
    float outputGainStep_ = 1.0F / 240.0F;
    std::atomic<bool> running_{false};
    std::atomic<bool> disconnected_{false};
    std::atomic<int32_t> currentTimbre_{static_cast<int32_t>(Timbre::PureTone)};
    float sampleRate_ = 48000.0F;
    float pureToneReleaseMultiplier_ = 0.998F;
    float pianoReleaseMultiplier_ = 0.9998F;
    float retriggerReleaseMultiplier_ = 0.98F;
    float tuningSmoothingFactor_ = 1.0F;
};

std::mutex gEngineMutex;
std::shared_ptr<SynthEngine> gEngine;

std::shared_ptr<SynthEngine> engineSnapshot() {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    return gEngine;
}

std::shared_ptr<SynthEngine> getOrCreateEngine() {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine == nullptr) {
        gEngine = std::make_shared<SynthEngine>();
    }
    return gEngine;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeLoadPianoSamples(
        JNIEnv *env,
        jobject,
        jobject assetManager) {
    auto *manager = AAssetManager_fromJava(env, assetManager);
    const auto engine = getOrCreateEngine();
    return engine->loadPianoSamples(manager) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeStart(JNIEnv *, jobject) {
    return getOrCreateEngine()->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeStop(JNIEnv *, jobject) {
    // Банк WAV остаётся открытым после сворачивания; повторный вход не требует
    // заново разбирать 30 файлов, но сам аудиопоток полностью освобождается.
    const auto engine = engineSnapshot();
    if (engine != nullptr) {
        engine->stop();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeIsRunning(JNIEnv *, jobject) {
    const auto engine = engineSnapshot();
    return engine != nullptr && engine->isRunning() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeSetTimbre(
        JNIEnv *, jobject, jint timbre) {
    const auto engine = engineSnapshot();
    if (engine != nullptr) {
        engine->setTimbre(timbre);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeSetOutputEnabled(
        JNIEnv *, jobject, jboolean enabled) {
    const auto engine = engineSnapshot();
    if (engine != nullptr) engine->setOutputEnabled(enabled != 0);
}

extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeNoteOn(
        JNIEnv *, jobject, jint midiNote, jdouble frequencyHz, jfloat velocity) {
    const auto engine = engineSnapshot();
    if (engine != nullptr) {
        engine->noteOn(midiNote, static_cast<float>(frequencyHz), velocity);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeRetune(
        JNIEnv *, jobject, jint midiNote, jdouble frequencyHz) {
    const auto engine = engineSnapshot();
    if (engine != nullptr) {
        engine->retune(midiNote, static_cast<float>(frequencyHz));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeAudioEngine_nativeNoteOff(
        JNIEnv *, jobject, jint midiNote) {
    const auto engine = engineSnapshot();
    if (engine != nullptr) {
        engine->noteOff(midiNote);
    }
}

// These handles are private to a single offline worker and never open Oboe.
extern "C" JNIEXPORT jlong JNICALL
Java_app_fortem_audio_NativeOfflineRenderer_nativeCreate(JNIEnv *, jobject) {
    const auto live = engineSnapshot();
    if (!live) return 0;
    auto *renderer = new SynthEngine();
    renderer->useSampleBank(live->sampleBank());
    renderer->configureSampleRate(48000.0F);
    return reinterpret_cast<jlong>(renderer);
}
extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeOfflineRenderer_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<SynthEngine *>(handle);
}
extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeOfflineRenderer_nativeTimbre(JNIEnv *, jobject, jlong h, jint timbre) {
    reinterpret_cast<SynthEngine *>(h)->setTimbre(timbre);
}
extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeOfflineRenderer_nativeNote(JNIEnv *, jobject, jlong h,
        jint kind, jint note, jdouble frequency, jfloat velocity) {
    auto *renderer = reinterpret_cast<SynthEngine *>(h);
    if (kind == 0) renderer->noteOff(note);
    else if (kind == 1) renderer->noteOn(note, static_cast<float>(frequency), velocity);
    else renderer->retune(note, static_cast<float>(frequency));
}
extern "C" JNIEXPORT void JNICALL
Java_app_fortem_audio_NativeOfflineRenderer_nativeRender(JNIEnv *env, jobject, jlong h,
        jfloatArray buffer, jint frames) {
    if (frames < 0 || frames > env->GetArrayLength(buffer) / 2) return;
    auto *data = env->GetFloatArrayElements(buffer, nullptr);
    if (!data) return;
    reinterpret_cast<SynthEngine *>(h)->render(data, 2, frames);
    env->ReleaseFloatArrayElements(buffer, data, 0);
}
