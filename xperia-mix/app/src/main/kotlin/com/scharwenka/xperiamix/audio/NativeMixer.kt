package com.scharwenka.xperiamix.audio

import java.nio.ByteBuffer

/**
 * Frontiere JNI, volontairement minimale : un handle opaque et des appels sans
 * allocation. Aucun callback natif ne remonte vers la JVM - le thread audio ne
 * doit jamais s'attacher a la VM ni risquer une pause GC.
 */
internal object NativeMixer {

    init {
        System.loadLibrary("xperiamix")
    }

    external fun nativeCreate(sampleRate: Int): Long
    external fun nativeDestroy(handle: Long)

    external fun nativeSetTrack(handle: Long, index: Int, buffer: ByteBuffer, frames: Long, channels: Int)
    external fun nativeClearTrack(handle: Long, index: Int)

    external fun nativeSetOffset(handle: Long, index: Int, frames: Long)
    external fun nativeSetGain(handle: Long, index: Int, gain: Float)
    external fun nativeSetMuted(handle: Long, index: Int, muted: Boolean)
    external fun nativeSetInverted(handle: Long, index: Int, inverted: Boolean)

    external fun nativePlay(handle: Long)
    external fun nativePause(handle: Long)
    external fun nativeSeek(handle: Long, frame: Long)

    external fun nativePlayhead(handle: Long): Long
    external fun nativeTimelineFrames(handle: Long): Long
    external fun nativeOutputPeak(handle: Long): Float
    external fun nativeLatencyMillis(handle: Long): Double
    external fun nativeUnderruns(handle: Long): Long
    external fun nativeSampleRate(handle: Long): Int

    external fun nativeResetExportRamp(handle: Long)
    external fun nativeRenderRange(handle: Long, out: ByteBuffer, startFrame: Long, numFrames: Int)
}
