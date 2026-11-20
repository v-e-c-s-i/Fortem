// SPDX-License-Identifier: GPL-3.0-only
// Host test runs production rendering + production WAV loading. Only Android/Oboe
// platform adapters are stubbed; this is NOT an Android integration test.
#include "../app/src/main/cpp/native_audio.cpp"
#include <cassert>
#include <iostream>
#include <random>
#include <vector>

int main(int argc, char **argv) {
    assert(argc == 2);
    for (const int rate : {44100, 48000, 96000, 192000}) {
        PeakLimiter limiter; limiter.reset(rate);
        std::mt19937 random(42);
        std::uniform_real_distribution<float> distribution(-32,32);
        float peak = 0;
        for (int i = 0; i < 300000; ++i) {
            float input = i % 1234 < 20 ? distribution(random) : std::sin(i * .17F) * .2F;
            auto out = limiter.process(input, input * .37F);
            assert(std::isfinite(out.left) && std::isfinite(out.right));
            assert(std::abs(out.left) <= PeakLimiter::kCeiling + 1e-6F);
            assert(std::abs(out.right - out.left * .37F) < 1e-6F);
            peak = std::max(peak, std::abs(out.left));
        }
        limiter.reset(rate);
        std::vector<float> input(10000);
        for (int i = 0; i < 10000; ++i) {
            input[i] = std::sin(i * .2F) * .7F;
            const auto out = limiter.process(input[i], input[i]);
            if (i >= limiter.latencyFrames()) assert(out.left == input[i - limiter.latencyFrames()]);
        }
        std::cout << "Limiter " << rate << " Hz: peak=" << peak << "; transparent below threshold\n";
    }
    AAssetManager assets{argv[1]};
    auto live = std::make_shared<SynthEngine>();
    assert(live->loadPianoSamples(&assets));
    live->start();
    SynthEngine offline; offline.useSampleBank(live->sampleBank()); offline.configureSampleRate(48000);
    std::array<float, 256 * 2> a{}, b{};
    oboe::AudioStream stream;
    float peak = 0;
    for (int timbre : {0,1}) {
        live->stop(); live->start();
        SynthEngine fresh; fresh.useSampleBank(live->sampleBank()); fresh.configureSampleRate(48000);
        live->setTimbre(timbre); fresh.setTimbre(timbre);
        for (int n=36;n<84;++n) {
            double hz=440.0*std::pow(2.0,(n-69.0)/12.0);
            live->noteOn(n,hz,.8F); fresh.noteOn(n,hz,.8F);
        }
        for (int block=0;block<500;++block) {
            if (block==100) { live->retune(60,270); fresh.retune(60,270); }
            if (block==200) for(int n=36;n<84;++n) {live->noteOff(n);fresh.noteOff(n);}
            live->onAudioReady(&stream,a.data(),256); fresh.render(b.data(),2,256);
            assert(a==b);
            for(float sample:a) { assert(std::isfinite(sample));assert(std::abs(sample)<=.820001F);peak=std::max(peak,std::abs(sample)); }
        }
        std::cout<<"48-voice timbre "<<timbre<<": live/offline identical; bounded peak\n";
    }
    std::cout<<"All 30 production samples loaded. Maximum output="<<peak<<"\n";
    // Педаль должна лишь откладывать note-off. Проверяем настоящий рендер:
    // удерживаем трезвучие две секунды без повторной атаки, затем отпускаем.
    SynthEngine pedal; pedal.configureSampleRate(48000); pedal.setTimbre(0);
    for (int n : {57, 60, 64}) pedal.noteOn(n, 440.0 * std::pow(2.0, (n - 69.0) / 12.0), .8F);
    for (int block = 0; block < 375; ++block) pedal.render(a.data(), 2, 256);
    float heldEnergy = 0;
    for (float value : a) heldEnergy += value * value;
    assert(heldEnergy > .001F);
    for (int n : {57, 60, 64}) pedal.noteOff(n);
    for (int block = 0; block < 375; ++block) pedal.render(a.data(), 2, 256);
    float releasedEnergy = 0;
    for (float value : a) releasedEnergy += value * value;
    assert(releasedEnergy < .000001F);
    std::cout << "Pedal gate: chord remains sounding until note-off, then fades to silence\n";
    // Общие ноты соседних аккордов: живой повторный удар и записанная пара
    // OFF/ON должны давать одинаковые отсчёты, включая перекрытие 150 мс.
    for (int timbre : {0, 1}) {
        SynthEngine played, recorded;
        for (SynthEngine *engine : {&played, &recorded}) {
            engine->configureSampleRate(48000);
            engine->useSampleBank(live->sampleBank());
            engine->setTimbre(timbre);
            for (int n : {57, 60, 64})
                engine->noteOn(n, 440.0 * std::pow(2.0, (n - 69.0) / 12.0), .8F);
        }
        auto renderIdentically = [&](int frames) {
            while (frames > 0) {
                const int count = std::min(frames, 256);
                played.render(a.data(), 2, count);
                recorded.render(b.data(), 2, count);
                for (int i = 0; i < count * 2; ++i) {
                    assert(a[i] == b[i]);
                    assert(std::isfinite(a[i]) && std::abs(a[i]) <= .820001F);
                }
                frames -= count;
            }
        };
        renderIdentically(4800);
        for (int n : {53, 57, 60}) {
            // В записи общие ноты имеют явную пару OFF/ON в один момент.
            if (n == 57 || n == 60) recorded.noteOff(n);
            const float hz = 440.0 * std::pow(2.0, (n - 69.0) / 12.0);
            played.noteOn(n, hz, .8F); recorded.noteOn(n, hz, .8F);
        }
        renderIdentically(7200); // 150 мс перекрытия, затем отпускается старая E4.
        played.noteOff(64); recorded.noteOff(64);
        renderIdentically(48000);
        for (int n : {53, 57, 60}) { played.noteOff(n); recorded.noteOff(n); }
        renderIdentically(96000);
        std::cout << "Chord overlap timbre " << timbre
                  << ": recorded common-note attacks identical to live sound\n";
    }
    // Заглушается только живой выход, не синтез и не WAV. После возврата не
    // появляется новая атака: фаза и огибающая совпадают с непрерывным рендером.
    SynthEngine monitored, reference;
    for (SynthEngine *engine : {&monitored, &reference}) {
        engine->configureSampleRate(48000);
        engine->setTimbre(0);
        engine->noteOn(69, 440, .8F);
    }
    monitored.onAudioReady(&stream, a.data(), 256);
    reference.render(b.data(), 2, 256);
    assert(a == b);
    monitored.setOutputEnabled(false);
    monitored.onAudioReady(&stream, a.data(), 256);
    reference.render(b.data(), 2, 256);
    for (int i = 0; i < 512; ++i) {
        assert(std::isfinite(a[i]));
        assert(std::abs(a[i]) <= std::abs(b[i]) + 1e-7F);
    }
    for (int block = 0; block < 20; ++block) {
        monitored.onAudioReady(&stream, a.data(), 256);
        reference.render(b.data(), 2, 256);
        for (float sample : a) assert(sample == 0.0F);
    }
    // render() вызывается экспортом: выключенный телефон не создаёт пустой WAV.
    monitored.render(a.data(), 2, 256);
    reference.render(b.data(), 2, 256);
    assert(a == b);
    assert(std::any_of(a.begin(), a.end(), [](float sample) { return sample != 0.0F; }));
    monitored.setOutputEnabled(true);
    monitored.onAudioReady(&stream, a.data(), 256);
    reference.render(b.data(), 2, 256);
    monitored.onAudioReady(&stream, a.data(), 256);
    reference.render(b.data(), 2, 256);
    assert(a == b); // Без нового note-on, точное совпадение непрерывной фазы.
    monitored.setOutputEnabled(false);
    monitored.configureSampleRate(48000); // Новый маршрут сразу остаётся беззвучным.
    monitored.onAudioReady(&stream, a.data(), 256);
    for (float sample : a) assert(sample == 0.0F);
    monitored.noteOff(69);
    for (int block = 0; block < 150; ++block) monitored.onAudioReady(&stream, a.data(), 256);
    monitored.setOutputEnabled(true);
    monitored.onAudioReady(&stream, a.data(), 256);
    for (float sample : a) assert(sample == 0.0F);
    std::cout << "MIDI monitoring: silence, independent WAV, no retrigger, route restart and note-off verified\n";

}
