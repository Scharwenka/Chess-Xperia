package com.scharwenka.xperiamix.waveform

import com.scharwenka.xperiamix.decode.PcmBuffer
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Un niveau de detail : pour chaque paquet de [bucketFrames] trames, le minimum,
 * le maximum et la valeur efficace (RMS) du signal mono.
 */
class PeakLevel(
    val bucketFrames: Int,
    val mins: FloatArray,
    val maxs: FloatArray,
    val rms: FloatArray,
) {
    val bucketCount: Int get() = mins.size
}

/**
 * Pyramide de crêtes ("mip-map" de forme d'onde).
 *
 * Dessiner directement le PCM est impossible : a 48 kHz, une piste de 10 min
 * represente 28,8 M de trames pour ~3800 colonnes de pixels sur le Xperia 1 V.
 * Chaque niveau divise par deux le nombre de paquets ; le rendu choisit celui
 * qui donne environ un paquet par pixel, ce qui rend le cout du dessin
 * proportionnel a la largeur de l'ecran et non a la duree de la piste.
 *
 * Cout memoire : niveau 0 a 256 trames/paquet = 112 500 paquets pour 10 min,
 * soit 1,35 Mo (3 x float) ; l'ensemble des niveaux superieurs ajoute 100 %
 * au plus, donc < 3 Mo par piste.
 */
class WaveformPyramid private constructor(
    val sampleRate: Int,
    val frameCount: Long,
    val levels: List<PeakLevel>,
) {

    /** Niveau le plus grossier utilisable pour [framesPerPixel] (>= 1 paquet/pixel). */
    fun levelFor(framesPerPixel: Float): Int {
        var best = 0
        for (i in levels.indices) {
            if (levels[i].bucketFrames <= framesPerPixel) best = i else break
        }
        return best
    }

    companion object {
        const val BASE_BUCKET_FRAMES = 256

        suspend fun build(
            pcm: PcmBuffer,
            onProgress: (Float) -> Unit = {},
        ): WaveformPyramid = withContext(Dispatchers.Default) {
            val base = buildBaseLevel(pcm, onProgress)
            val levels = mutableListOf(base)
            // Chaque niveau est construit a partir du precedent : O(n), jamais
            // une nouvelle passe sur le PCM.
            while (levels.last().bucketCount > 2) {
                coroutineContext.ensureActive()
                levels += decimate(levels.last())
            }
            WaveformPyramid(pcm.sampleRate, pcm.frameCount, levels)
        }

        private suspend fun buildBaseLevel(pcm: PcmBuffer, onProgress: (Float) -> Unit): PeakLevel {
            val bucketCount = ((pcm.frameCount + BASE_BUCKET_FRAMES - 1) / BASE_BUCKET_FRAMES).toInt()
            val mins = FloatArray(bucketCount)
            val maxs = FloatArray(bucketCount)
            val rms = FloatArray(bucketCount)

            for (b in 0 until bucketCount) {
                if (b % 256 == 0) {
                    coroutineContext.ensureActive()
                    onProgress(b.toFloat() / bucketCount)
                }
                val start = b.toLong() * BASE_BUCKET_FRAMES
                val end = minOf(start + BASE_BUCKET_FRAMES, pcm.frameCount)
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                var energy = 0.0
                var f = start
                while (f < end) {
                    val v = pcm.monoAt(f)
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                    energy += v.toDouble() * v
                    f++
                }
                val n = (end - start).coerceAtLeast(1)
                mins[b] = if (lo == Float.MAX_VALUE) 0f else lo
                maxs[b] = if (hi == -Float.MAX_VALUE) 0f else hi
                rms[b] = sqrt(energy / n).toFloat()
            }
            onProgress(1f)
            return PeakLevel(BASE_BUCKET_FRAMES, mins, maxs, rms)
        }

        private fun decimate(level: PeakLevel): PeakLevel {
            val count = (level.bucketCount + 1) / 2
            val mins = FloatArray(count)
            val maxs = FloatArray(count)
            val rms = FloatArray(count)
            for (i in 0 until count) {
                val a = i * 2
                val b = minOf(a + 1, level.bucketCount - 1)
                mins[i] = minOf(level.mins[a], level.mins[b])
                maxs[i] = maxOf(level.maxs[a], level.maxs[b])
                // RMS d'une reunion = racine de la moyenne des carres.
                rms[i] = sqrt((level.rms[a] * level.rms[a] + level.rms[b] * level.rms[b]) / 2f)
            }
            return PeakLevel(level.bucketFrames * 2, mins, maxs, rms)
        }

        /** Utilitaire de test : crete absolue d'un niveau. */
        fun peakOf(level: PeakLevel): Float {
            var peak = 0f
            for (i in 0 until level.bucketCount) {
                peak = maxOf(peak, maxOf(abs(level.mins[i]), abs(level.maxs[i])))
            }
            return peak
        }
    }
}
