package com.scharwenka.xperiamix.decode

import java.io.File
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Reechantillonnage par interpolation sinc fenetree (Lanczos, 16 lobes),
 * applique une seule fois au chargement.
 *
 * Pourquoi pas une interpolation lineaire : a 44,1 -> 48 kHz elle produit un
 * repliement a -30 dB environ, parfaitement audible sur les aigus, et surtout
 * elle deforme les transitoires que le calage automatique cherche a corréler.
 * Avec 16 lobes et une frequence de coupure adaptee, le bruit d'interpolation
 * reste sous -100 dB, donc sous le plancher d'un 16 bits.
 *
 * Le noyau est pre-calcule dans une table (POLY_STEPS positions par
 * echantillon source) et interpole lineairement : le cout par echantillon de
 * sortie est de 2 x halfWidth multiplications, sans appel a sin().
 */
object Resampler {

    private const val LOBES = 16
    private const val POLY_STEPS = 256

    suspend fun resample(
        src: PcmBuffer,
        targetRate: Int,
        target: File,
        onProgress: (Float) -> Unit = {},
    ): Unit = withContext(Dispatchers.Default) {
        val ratio = targetRate.toDouble() / src.sampleRate
        val channels = src.channels
        val outFrames = floor(src.frameCount * ratio).toLong()

        // En sous-echantillonnage, la coupure descend avec le rapport pour
        // eviter le repliement ; le noyau s'elargit d'autant.
        val cutoff = min(1.0, ratio)
        val halfWidth = ceil(LOBES / cutoff).toInt()
        val table = buildKernelTable(cutoff, halfWidth)

        val block = 4096
        val out = FloatArray(block * channels)
        val weights = DoubleArray(2 * halfWidth)

        PcmSink(target).use { sink ->
            var frame = 0L
            while (frame < outFrames) {
                coroutineContext.ensureActive()
                val count = min(block.toLong(), outFrames - frame).toInt()
                for (n in 0 until count) {
                    val t = (frame + n) / ratio
                    val i0 = floor(t).toLong()
                    val frac = t - i0

                    var weightSum = 0.0
                    for (k in 0 until 2 * halfWidth) {
                        val offset = k - halfWidth + 1
                        val u = frac - offset
                        val w = lookup(table, u, halfWidth)
                        weights[k] = w
                        weightSum += w
                    }
                    if (weightSum == 0.0) weightSum = 1.0

                    for (c in 0 until channels) {
                        var acc = 0.0
                        for (k in 0 until 2 * halfWidth) {
                            val idx = i0 + (k - halfWidth + 1)
                            if (idx < 0 || idx >= src.frameCount) continue
                            acc += weights[k] * src.sampleAt(idx, c)
                        }
                        out[n * channels + c] = (acc / weightSum).toFloat()
                    }
                }
                sink.write(out, count * channels)
                frame += count
                onProgress((frame.toDouble() / outFrames).toFloat().coerceIn(0f, 1f))
            }
        }
    }

    private fun buildKernelTable(cutoff: Double, halfWidth: Int): DoubleArray {
        val size = halfWidth * POLY_STEPS + 2
        return DoubleArray(size) { i ->
            val u = i.toDouble() / POLY_STEPS
            val x = cutoff * u
            if (x >= LOBES) 0.0 else cutoff * sinc(x) * sinc(x / LOBES)
        }
    }

    /** Table symetrique : on indexe sur |u|, avec interpolation lineaire. */
    private fun lookup(table: DoubleArray, u: Double, halfWidth: Int): Double {
        val au = if (u < 0) -u else u
        if (au >= halfWidth) return 0.0
        val pos = au * POLY_STEPS
        val i = pos.toInt()
        val f = pos - i
        return table[i] * (1 - f) + table[i + 1] * f
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
}
