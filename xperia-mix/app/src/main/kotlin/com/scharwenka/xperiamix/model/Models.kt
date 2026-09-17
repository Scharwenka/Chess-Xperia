package com.scharwenka.xperiamix.model

import android.net.Uri
import com.scharwenka.xperiamix.decode.PcmBuffer
import com.scharwenka.xperiamix.waveform.WaveformPyramid

/** Emplacement de piste. Le moteur natif n'en connait que l'index. */
enum class TrackId(val index: Int) { A(0), B(1) }

/** Etat complet d'une piste chargee. Immuable : porte par un StateFlow. */
data class TrackState(
    val id: TrackId,
    val uri: Uri? = null,
    val displayName: String = "",
    val pcm: PcmBuffer? = null,
    val waveform: WaveformPyramid? = null,
    /** Decalage en trames sur la timeline. > 0 : la piste demarre plus tard. */
    val offsetFrames: Long = 0,
    val gainDb: Float = 0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
    val polarityInverted: Boolean = false,
) {
    val isLoaded: Boolean get() = pcm != null
    val frameCount: Long get() = pcm?.frameCount ?: 0L
    val sampleRate: Int get() = pcm?.sampleRate ?: 48_000

    fun offsetMillis(): Double = offsetFrames * 1000.0 / sampleRate

    /**
     * Gain lineaire effectif : le solo d'une piste impose le silence a l'autre.
     * Regle classique de console : des qu'un solo est actif, seules les pistes
     * en solo passent.
     */
    fun linearGain(anySoloed: Boolean): Float {
        val audible = if (anySoloed) soloed else !muted
        if (!audible) return 0f
        return Math.pow(10.0, gainDb / 20.0).toFloat()
    }
}

/** Fenetre visible de la timeline, en trames. */
data class Viewport(
    val firstFrame: Long = 0,
    val framesPerPixel: Float = 512f,
) {
    fun frameAt(x: Float): Long = firstFrame + (x * framesPerPixel).toLong()
    fun xOf(frame: Long): Float = (frame - firstFrame) / framesPerPixel
    fun visibleFrames(widthPx: Float): Long = (widthPx * framesPerPixel).toLong()
}

/** Resultat d'un calage automatique. */
data class AlignmentResult(
    /** Decalage a appliquer a l'offset de la piste B, en trames (signe inclus). */
    val lagFrames: Long,
    val lagMillis: Double,
    /** Correlation normalisee au pic, dans [0, 1]. */
    val confidence: Float,
    /** Rapport pic / lobes secondaires : discrimine un vrai pic d'un plateau. */
    val peakToSidelobe: Float,
    val method: Method,
    /** Vrai si le pic de correlation est negatif : les pistes sont en opposition. */
    val polarityInverted: Boolean,
    /** Derive d'horloge estimee entre les deux enregistreurs, en ppm. */
    val driftPpm: Double?,
) {
    enum class Method { ENVELOPE, GCC_PHAT, HYBRID }

    /** En dessous de ce seuil, on propose le resultat sans l'appliquer d'office. */
    val isReliable: Boolean get() = confidence >= 0.35f && peakToSidelobe >= 3f
}

sealed interface TaskProgress {
    data object Idle : TaskProgress
    data class Running(val label: String, val fraction: Float?) : TaskProgress
    data class Failed(val label: String, val cause: String) : TaskProgress
}
