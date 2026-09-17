#pragma once

#include <oboe/Oboe.h>

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

namespace xmix {

inline constexpr int kMaxTracks = 2;
inline constexpr int kOutputChannels = 2;

/**
 * Une piste = un bloc PCM float32 entrelace, possede cote JVM (DirectByteBuffer
 * ou fichier mappe en memoire). Le moteur ne fait que lire ce bloc.
 *
 * Les champs modifies par l'UI sont atomiques et relaxes : ils sont lus une
 * fois par callback, jamais pendant une boucle d'echantillons, et une valeur
 * "vue en retard" d'un buffer (~5 ms) est inaudible.
 */
struct TrackSlot {
    // Atomiques parce qu'ils traversent la frontiere entre le thread appelant
    // (JNI) et le thread audio : un `const float*` ordinaire serait une course
    // de donnees, et le compilateur serait libre de reordonner l'ecriture du
    // pointeur avant celle de la taille.
    std::atomic<const float*> data{nullptr};
    std::atomic<int64_t> frames{0};
    std::atomic<int32_t> channels{0};

    std::atomic<int64_t> offsetFrames{0};   // > 0 : la piste demarre plus tard
    std::atomic<float> targetGain{1.0f};
    std::atomic<bool> muted{false};
    std::atomic<bool> inverted{false};      // inversion de polarite
};

/** Etat de lissage de gain : propre a un thread de rendu (RT ou export). */
struct GainRamp {
    std::array<float, kMaxTracks> current{};
    bool primed = false;
};

class MixerEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    MixerEngine() = default;
    ~MixerEngine() override;

    /** Ouvre le flux AAudio. `requestedSampleRate` doit etre le taux natif du
     *  peripherique de sortie pour rester sur le chemin rapide (pas de resampler). */
    oboe::Result open(int32_t requestedSampleRate);
    void close();

    void setTrack(int index, const float* data, int64_t frames, int32_t channels);
    void clearTrack(int index);

    void setOffsetFrames(int index, int64_t offset);
    void setGain(int index, float linearGain);
    void setMuted(int index, bool muted);
    void setInverted(int index, bool inverted);

    void play();
    void pause();
    void seek(int64_t frame);

    /** Position brute du curseur (derniere trame ecrite dans le flux). */
    [[nodiscard]] int64_t playheadFrames() const { return mPlayhead.load(std::memory_order_relaxed); }
    /** Position reellement entendue = curseur - latence du chemin de sortie. */
    [[nodiscard]] int64_t audiblePlayheadFrames() const;
    [[nodiscard]] float outputPeak();
    [[nodiscard]] int32_t sampleRate() const { return mSampleRate; }
    [[nodiscard]] double latencyMillis() const { return mLatencyMillis.load(std::memory_order_relaxed); }
    [[nodiscard]] int64_t underrunCount() const { return mUnderruns.load(std::memory_order_relaxed); }
    /** Nombre de trames du mixage = max(offset + duree) sur les pistes actives. */
    [[nodiscard]] int64_t timelineFrames() const;

    /** Rendu hors-ligne (export). Thread quelconque, jamais le thread audio. */
    void renderRange(float* out, int64_t startFrame, int32_t numFrames, GainRamp& ramp) const;

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result result) override;

private:
    /** Coeur du mixage, partage entre le thread RT et l'export : l'export est
     *  donc bit-a-bit identique a ce qui est entendu. */
    void mixInto(float* out, int64_t startFrame, int32_t numFrames, GainRamp& ramp) const;

    std::array<TrackSlot, kMaxTracks> mTracks{};
    std::shared_ptr<oboe::AudioStream> mStream;
    std::mutex mStreamLock;                 // protege open/close, jamais le callback

    std::atomic<int64_t> mPlayhead{0};
    std::atomic<bool> mPlaying{false};
    std::atomic<float> mPeak{0.0f};
    std::atomic<double> mLatencyMillis{0.0};
    std::atomic<int64_t> mUnderruns{0};

    GainRamp mRtRamp{};                     // thread audio uniquement
    float mTransportGain = 0.0f;            // thread audio uniquement
    int32_t mSampleRate = 48000;
    float mRampCoeff = 0.002f;
};

}  // namespace xmix
