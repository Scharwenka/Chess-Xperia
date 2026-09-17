#include <jni.h>
#include <sys/mman.h>

#include <array>
#include <new>

#include "MixerEngine.h"

namespace {

/**
 * Enveloppe le moteur + ce qu'il faut maintenir en vie cote JVM.
 *
 * Les blocs PCM sont des DirectByteBuffer (ou des MappedByteBuffer) alloues en
 * Kotlin. Une reference globale empeche le GC de liberer le buffer - et donc
 * d'unmapper la memoire - pendant que le thread audio la lit.
 */
struct EngineHandle {
    xmix::MixerEngine engine;
    xmix::GainRamp exportRamp;
    std::array<jobject, xmix::kMaxTracks> pcmRefs{};
};

inline EngineHandle* handleOf(jlong h) { return reinterpret_cast<EngineHandle*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeCreate(JNIEnv*, jobject, jint sampleRate) {
    auto* h = new (std::nothrow) EngineHandle();
    if (h == nullptr) return 0;
    if (h->engine.open(sampleRate) != oboe::Result::OK) {
        delete h;
        return 0;
    }
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeDestroy(JNIEnv* env, jobject, jlong handle) {
    auto* h = handleOf(handle);
    if (h == nullptr) return;
    h->engine.close();
    for (jobject& ref : h->pcmRefs) {
        if (ref != nullptr) env->DeleteGlobalRef(ref);
        ref = nullptr;
    }
    delete h;
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeSetTrack(JNIEnv* env, jobject, jlong handle,
                                                               jint index, jobject buffer,
                                                               jlong frames, jint channels) {
    auto* h = handleOf(handle);
    if (h == nullptr || index < 0 || index >= xmix::kMaxTracks) return;

    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr) return;  // buffer non direct : erreur de programmation cote Kotlin

    // Fichier mappe : on demande au noyau de pre-charger les pages pour eviter
    // une faute de page majeure dans le callback temps reel.
    const size_t bytes = static_cast<size_t>(frames) * channels * sizeof(float);
    madvise(data, bytes, MADV_WILLNEED);

    h->engine.setTrack(index, data, frames, channels);

    if (h->pcmRefs[index] != nullptr) env->DeleteGlobalRef(h->pcmRefs[index]);
    h->pcmRefs[index] = env->NewGlobalRef(buffer);
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeClearTrack(JNIEnv* env, jobject, jlong handle,
                                                                 jint index) {
    auto* h = handleOf(handle);
    if (h == nullptr || index < 0 || index >= xmix::kMaxTracks) return;
    h->engine.clearTrack(index);
    if (h->pcmRefs[index] != nullptr) {
        env->DeleteGlobalRef(h->pcmRefs[index]);
        h->pcmRefs[index] = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeSetOffset(JNIEnv*, jobject, jlong handle,
                                                                jint index, jlong frames) {
    if (auto* h = handleOf(handle)) h->engine.setOffsetFrames(index, frames);
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeSetGain(JNIEnv*, jobject, jlong handle,
                                                              jint index, jfloat gain) {
    if (auto* h = handleOf(handle)) h->engine.setGain(index, gain);
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeSetMuted(JNIEnv*, jobject, jlong handle,
                                                               jint index, jboolean muted) {
    if (auto* h = handleOf(handle)) h->engine.setMuted(index, muted == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeSetInverted(JNIEnv*, jobject, jlong handle,
                                                                  jint index, jboolean inverted) {
    if (auto* h = handleOf(handle)) h->engine.setInverted(index, inverted == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativePlay(JNIEnv*, jobject, jlong handle) {
    if (auto* h = handleOf(handle)) h->engine.play();
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativePause(JNIEnv*, jobject, jlong handle) {
    if (auto* h = handleOf(handle)) h->engine.pause();
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeSeek(JNIEnv*, jobject, jlong handle,
                                                           jlong frame) {
    if (auto* h = handleOf(handle)) h->engine.seek(frame);
}

JNIEXPORT jlong JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativePlayhead(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h ? h->engine.audiblePlayheadFrames() : 0;
}

JNIEXPORT jlong JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeTimelineFrames(JNIEnv*, jobject,
                                                                     jlong handle) {
    auto* h = handleOf(handle);
    return h ? h->engine.timelineFrames() : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeOutputPeak(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h ? h->engine.outputPeak() : 0.0f;
}

JNIEXPORT jdouble JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeLatencyMillis(JNIEnv*, jobject,
                                                                    jlong handle) {
    auto* h = handleOf(handle);
    return h ? h->engine.latencyMillis() : 0.0;
}

JNIEXPORT jlong JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeUnderruns(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h ? h->engine.underrunCount() : 0;
}

JNIEXPORT jint JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeSampleRate(JNIEnv*, jobject, jlong handle) {
    auto* h = handleOf(handle);
    return h ? h->engine.sampleRate() : 0;
}

JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeResetExportRamp(JNIEnv*, jobject,
                                                                      jlong handle) {
    if (auto* h = handleOf(handle)) h->exportRamp = xmix::GainRamp{};
}

/**
 * Rendu hors-ligne d'une tranche de la timeline, appele depuis un thread de
 * travail. Reutilise exactement la fonction de mixage du thread temps reel :
 * l'export est donc identique a ce qui a ete entendu.
 */
JNIEXPORT void JNICALL
Java_com_scharwenka_xperiamix_audio_NativeMixer_nativeRenderRange(JNIEnv* env, jobject, jlong handle,
                                                                  jobject out, jlong startFrame,
                                                                  jint numFrames) {
    auto* h = handleOf(handle);
    if (h == nullptr) return;
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(out));
    if (dst == nullptr) return;
    h->engine.renderRange(dst, startFrame, numFrames, h->exportRamp);
}

}  // extern "C"
