package com.scharwenka.xperiamix.audio

import android.content.Context
import com.scharwenka.xperiamix.decode.PcmBuffer
import com.scharwenka.xperiamix.model.TrackId
import java.nio.ByteBuffer

/**
 * Facade Kotlin du moteur natif.
 *
 * Toutes les commandes sont des ecritures atomiques cote C++ : elles ne
 * bloquent jamais, ce qui permet de les appeler directement depuis un geste de
 * l'interface sans passer par une file d'attente.
 */
class AudioEngine(context: Context) : AutoCloseable {

    val profile: DeviceAudioProfile = DeviceAudioProfile.query(context)

    private var handle: Long = NativeMixer.nativeCreate(profile.sampleRate)

    val isOpen: Boolean get() = handle != 0L
    val sampleRate: Int get() = if (isOpen) NativeMixer.nativeSampleRate(handle) else profile.sampleRate

    fun setTrack(id: TrackId, pcm: PcmBuffer) {
        if (!isOpen) return
        NativeMixer.nativeSetTrack(handle, id.index, pcm.bytes, pcm.frameCount, pcm.channels)
    }

    fun clearTrack(id: TrackId) {
        if (isOpen) NativeMixer.nativeClearTrack(handle, id.index)
    }

    fun setOffsetFrames(id: TrackId, frames: Long) {
        if (isOpen) NativeMixer.nativeSetOffset(handle, id.index, frames)
    }

    fun setGain(id: TrackId, linear: Float) {
        if (isOpen) NativeMixer.nativeSetGain(handle, id.index, linear)
    }

    fun setMuted(id: TrackId, muted: Boolean) {
        if (isOpen) NativeMixer.nativeSetMuted(handle, id.index, muted)
    }

    fun setInverted(id: TrackId, inverted: Boolean) {
        if (isOpen) NativeMixer.nativeSetInverted(handle, id.index, inverted)
    }

    fun play() { if (isOpen) NativeMixer.nativePlay(handle) }
    fun pause() { if (isOpen) NativeMixer.nativePause(handle) }
    fun seekFrames(frame: Long) { if (isOpen) NativeMixer.nativeSeek(handle, frame) }

    fun playheadFrames(): Long = if (isOpen) NativeMixer.nativePlayhead(handle) else 0L
    fun timelineFrames(): Long = if (isOpen) NativeMixer.nativeTimelineFrames(handle) else 0L
    fun outputPeak(): Float = if (isOpen) NativeMixer.nativeOutputPeak(handle) else 0f
    fun latencyMillis(): Double = if (isOpen) NativeMixer.nativeLatencyMillis(handle) else 0.0
    fun underruns(): Long = if (isOpen) NativeMixer.nativeUnderruns(handle) else 0L

    /** Prepare un rendu hors-ligne : remet a zero la rampe de gain de l'export. */
    fun beginOfflineRender() {
        if (isOpen) NativeMixer.nativeResetExportRamp(handle)
    }

    /**
     * Rend une tranche de la timeline dans [dest] (float32 stereo entrelace).
     * Reutilise la fonction de mixage du thread temps reel : l'export est
     * l'exact reflet de ce qui a ete entendu.
     */
    fun renderRange(dest: ByteBuffer, startFrame: Long, numFrames: Int) {
        if (isOpen) NativeMixer.nativeRenderRange(handle, dest, startFrame, numFrames)
    }

    override fun close() {
        if (isOpen) {
            NativeMixer.nativeDestroy(handle)
            handle = 0L
        }
    }
}
