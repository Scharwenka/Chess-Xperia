#include "MixerEngine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <thread>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "XperiaMix", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "XperiaMix", __VA_ARGS__)

namespace xmix {
namespace {

// Constante de temps du lissage des gains : assez court pour suivre un geste,
// assez long pour supprimer le "zipper noise" d'un changement par bloc.
constexpr float kGainTauSeconds = 0.010f;
// Rampe transport (play/pause/seek) : ~8 ms, supprime le clic de demarrage.
constexpr float kTransportTauSeconds = 0.008f;

inline float rampCoeff(float tauSeconds, int32_t sampleRate) {
    return 1.0f - std::exp(-1.0f / (tauSeconds * static_cast<float>(sampleRate)));
}

}  // namespace

MixerEngine::~MixerEngine() { close(); }

oboe::Result MixerEngine::open(int32_t requestedSampleRate) {
    std::lock_guard<std::mutex> lock(mStreamLock);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setSharingMode(oboe::SharingMode::Exclusive)      // chemin MMAP/FAST
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(kOutputChannels)
            ->setSampleRate(requestedSampleRate)                // = taux natif : pas de resampler
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    // ADPF (API 33+) : le thread audio declare sa charge au gouverneur CPU, ce qui
    // evite les descentes de frequence responsables d'une partie des xruns.
    builder.setPerformanceHintEnabled(true);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGE("openStream: %s", oboe::convertToText(result));
        return result;
    }

    mSampleRate = stream->getSampleRate();
    mRampCoeff = rampCoeff(kGainTauSeconds, mSampleRate);
    // Une taille de tampon de 2 rafales est le meilleur compromis stabilite /
    // latence sur AAudio ; Oboe ajuste dynamiquement en cas d'underrun.
    stream->setBufferSizeInFrames(stream->getFramesPerBurst() * 2);

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart: %s", oboe::convertToText(result));
        stream->close();
        return result;
    }

    LOGI("stream ouvert: %d Hz, burst=%d, exclusif=%d, mmap=%d", mSampleRate,
         stream->getFramesPerBurst(),
         stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0,
         stream->usesAAudio() ? 1 : 0);

    mStream = std::move(stream);
    return oboe::Result::OK;
}

void MixerEngine::close() {
    std::lock_guard<std::mutex> lock(mStreamLock);
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

void MixerEngine::setTrack(int index, const float* data, int64_t frames, int32_t channels) {
    if (index < 0 || index >= kMaxTracks) return;
    TrackSlot& slot = mTracks[index];
    // Sequence volontaire : on coupe d'abord la piste (frames = 0, donc
    // silencieuse quel que soit le pointeur), on publie ensuite le nouveau bloc,
    // et seulement en dernier la taille. Le callback lit dans l'ordre inverse,
    // acquire apres release : il ne peut jamais voir une taille sans le
    // pointeur correspondant.
    slot.frames.store(0, std::memory_order_release);
    slot.data.store(data, std::memory_order_relaxed);
    slot.channels.store(channels, std::memory_order_relaxed);
    slot.frames.store(frames, std::memory_order_release);
}

void MixerEngine::clearTrack(int index) { setTrack(index, nullptr, 0, 0); }

void MixerEngine::setOffsetFrames(int index, int64_t offset) {
    if (index < 0 || index >= kMaxTracks) return;
    mTracks[index].offsetFrames.store(offset, std::memory_order_relaxed);
}

void MixerEngine::setGain(int index, float linearGain) {
    if (index < 0 || index >= kMaxTracks) return;
    mTracks[index].targetGain.store(linearGain, std::memory_order_relaxed);
}

void MixerEngine::setMuted(int index, bool muted) {
    if (index < 0 || index >= kMaxTracks) return;
    mTracks[index].muted.store(muted, std::memory_order_relaxed);
}

void MixerEngine::setInverted(int index, bool inverted) {
    if (index < 0 || index >= kMaxTracks) return;
    mTracks[index].inverted.store(inverted, std::memory_order_relaxed);
}

void MixerEngine::play() { mPlaying.store(true, std::memory_order_relaxed); }

void MixerEngine::pause() { mPlaying.store(false, std::memory_order_relaxed); }

void MixerEngine::seek(int64_t frame) {
    mPlayhead.store(std::max<int64_t>(0, frame), std::memory_order_relaxed);
}

int64_t MixerEngine::timelineFrames() const {
    int64_t end = 0;
    for (const TrackSlot& slot : mTracks) {
        const int64_t frames = slot.frames.load(std::memory_order_acquire);
        if (frames > 0) {
            end = std::max(end, slot.offsetFrames.load(std::memory_order_relaxed) + frames);
        }
    }
    return end;
}

int64_t MixerEngine::audiblePlayheadFrames() const {
    const double latencyFrames = mLatencyMillis.load(std::memory_order_relaxed) * mSampleRate / 1000.0;
    return std::max<int64_t>(0, mPlayhead.load(std::memory_order_relaxed) -
                                        static_cast<int64_t>(latencyFrames));
}

float MixerEngine::outputPeak() { return mPeak.exchange(0.0f, std::memory_order_relaxed); }

void MixerEngine::mixInto(float* out, int64_t startFrame, int32_t numFrames, GainRamp& ramp) const {
    std::fill(out, out + static_cast<size_t>(numFrames) * kOutputChannels, 0.0f);

    for (int t = 0; t < kMaxTracks; ++t) {
        const TrackSlot& slot = mTracks[t];
        const int64_t frames = slot.frames.load(std::memory_order_acquire);
        const float* data = slot.data.load(std::memory_order_relaxed);
        if (data == nullptr || frames == 0) continue;

        const int64_t offset = slot.offsetFrames.load(std::memory_order_relaxed);
        const bool muted = slot.muted.load(std::memory_order_relaxed);
        const float sign = slot.inverted.load(std::memory_order_relaxed) ? -1.0f : 1.0f;
        const float target = muted ? 0.0f : slot.targetGain.load(std::memory_order_relaxed) * sign;
        const int32_t channels = slot.channels.load(std::memory_order_relaxed);

        if (!ramp.primed) ramp.current[t] = target;
        float gain = ramp.current[t];

        // Intersection [startFrame, startFrame+numFrames) x [offset, offset+frames)
        // pour ne parcourir que la region reellement sonore.
        const int64_t first = std::max<int64_t>(startFrame, offset);
        const int64_t last = std::min<int64_t>(startFrame + numFrames, offset + frames);

        if (first >= last) {
            // Hors zone : on laisse quand meme le gain converger pour eviter un
            // saut quand la piste rentre.
            gain += (target - gain) * mRampCoeff * static_cast<float>(numFrames);
            ramp.current[t] = std::clamp(gain, -4.0f, 4.0f);
            continue;
        }

        // Rattrapage du gain sur la partie silencieuse precedant l'entree.
        for (int64_t f = startFrame; f < first; ++f) gain += (target - gain) * mRampCoeff;

        const int64_t count = last - first;
        const int64_t srcBase = first - offset;
        float* dst = out + static_cast<size_t>(first - startFrame) * kOutputChannels;

        if (channels == 1) {
            const float* src = data + srcBase;
            for (int64_t i = 0; i < count; ++i) {
                gain += (target - gain) * mRampCoeff;
                const float v = src[i] * gain;
                dst[i * 2] += v;
                dst[i * 2 + 1] += v;
            }
        } else {
            const float* src = data + srcBase * channels;
            for (int64_t i = 0; i < count; ++i) {
                gain += (target - gain) * mRampCoeff;
                dst[i * 2] += src[i * channels] * gain;
                dst[i * 2 + 1] += src[i * channels + 1] * gain;
            }
        }

        for (int64_t f = last; f < startFrame + numFrames; ++f) gain += (target - gain) * mRampCoeff;
        ramp.current[t] = gain;
    }
    ramp.primed = true;
}

void MixerEngine::renderRange(float* out, int64_t startFrame, int32_t numFrames,
                              GainRamp& ramp) const {
    mixInto(out, startFrame, numFrames, ramp);
}

oboe::DataCallbackResult MixerEngine::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    auto* out = static_cast<float*>(audioData);
    const int64_t start = mPlayhead.load(std::memory_order_relaxed);
    const bool playing = mPlaying.load(std::memory_order_relaxed);

    // Rampe transport : etat prive du thread audio, aucune synchronisation.
    const float transportCoeff = rampCoeff(kTransportTauSeconds, mSampleRate);
    const float transportTarget = playing ? 1.0f : 0.0f;

    if (!playing && mTransportGain < 1.0e-4f) {
        std::fill(out, out + static_cast<size_t>(numFrames) * kOutputChannels, 0.0f);
        mRtRamp.primed = false;  // au prochain demarrage, les gains partent a la cible
        return oboe::DataCallbackResult::Continue;
    }

    mixInto(out, start, numFrames, mRtRamp);

    float peak = 0.0f;
    for (int32_t i = 0; i < numFrames; ++i) {
        mTransportGain += (transportTarget - mTransportGain) * transportCoeff;
        for (int c = 0; c < kOutputChannels; ++c) {
            float v = out[i * kOutputChannels + c] * mTransportGain;
            peak = std::max(peak, std::fabs(v));
            // Ecretage franc en derniere ligne : le limiteur "propre" est
            // applique a l'export, pas sur le monitoring.
            out[i * kOutputChannels + c] = std::clamp(v, -1.0f, 1.0f);
        }
    }

    float previousPeak = mPeak.load(std::memory_order_relaxed);
    while (peak > previousPeak &&
           !mPeak.compare_exchange_weak(previousPeak, peak, std::memory_order_relaxed)) {
    }

    mPlayhead.store(start + numFrames, std::memory_order_relaxed);
    mLatencyMillis.store(stream->calculateLatencyMillis().value(), std::memory_order_relaxed);
    mUnderruns.store(stream->getXRunCount().value(), std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

void MixerEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result result) {
    // Typiquement Disconnected : jack 3,5 mm branche/debranche, ou bascule vers
    // le Bluetooth. Le flux doit etre rouvert hors du thread audio.
    LOGI("flux ferme (%s), reouverture", oboe::convertToText(result));
    const int32_t rate = mSampleRate;
    std::thread([this, rate] {
        mStream.reset();
        open(rate);
    }).detach();
}

}  // namespace xmix
