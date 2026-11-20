#pragma once
#include <memory>
namespace oboe {
enum class Result { OK }; enum class SharingMode { Exclusive, Shared };
enum class Direction { Output }; enum class PerformanceMode { LowLatency };
enum class AudioFormat { Float }; enum class Usage { Game }; enum class ContentType { Music };
enum class DataCallbackResult { Continue };
struct AudioStream { int getSampleRate() { return 48000; } int getChannelCount() { return 2; }
Result requestStart() { return Result::OK; } void requestStop() {} void close() {} };
struct AudioStreamDataCallback { virtual ~AudioStreamDataCallback() = default; virtual DataCallbackResult onAudioReady(AudioStream*,void*,int32_t)=0; };
struct AudioStreamErrorCallback { virtual ~AudioStreamErrorCallback() = default; virtual void onErrorAfterClose(AudioStream*,Result)=0; };
struct AudioStreamBuilder {
void setDirection(Direction) {} void setPerformanceMode(PerformanceMode) {} void setSharingMode(SharingMode) {}
void setFormat(AudioFormat) {} void setChannelCount(int) {} void setUsage(Usage) {} void setContentType(ContentType) {}
template<class T> void setDataCallback(T) {} template<class T> void setErrorCallback(T) {}
Result openStream(std::shared_ptr<AudioStream> &s) { s = std::make_shared<AudioStream>(); return Result::OK; }
};
}
