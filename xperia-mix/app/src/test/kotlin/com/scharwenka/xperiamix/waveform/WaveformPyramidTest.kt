package com.scharwenka.xperiamix.waveform

import com.scharwenka.xperiamix.decode.PcmBuffer
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformPyramidTest {

    private fun sineBuffer(frames: Int, channels: Int = 2): PcmBuffer {
        val pcm = PcmBuffer.allocateDirect(48_000, channels, frames.toLong())
        for (f in 0 until frames) {
            val v = sin(2.0 * PI * f / 480.0).toFloat()
            for (c in 0 until channels) pcm.floats.put(f * channels + c, v)
        }
        return pcm
    }

    @Test
    fun `chaque niveau conserve les extremes du niveau precedent`() = runBlocking {
        val pyramid = WaveformPyramid.build(sineBuffer(100_000))
        assertTrue(pyramid.levels.size > 4)

        val base = pyramid.levels.first()
        for (level in pyramid.levels) {
            // La reduction ne doit jamais rogner la crete : c'est ce qui garantit
            // qu'un transitoire reste visible en vue d'ensemble.
            assertEquals(
                WaveformPyramid.peakOf(base),
                WaveformPyramid.peakOf(level),
                1e-6f,
            )
        }
    }

    @Test
    fun `la taille des paquets double a chaque niveau`() = runBlocking {
        val pyramid = WaveformPyramid.build(sineBuffer(200_000))
        for (i in 1 until pyramid.levels.size) {
            assertEquals(
                pyramid.levels[i - 1].bucketFrames * 2,
                pyramid.levels[i].bucketFrames,
            )
        }
    }

    @Test
    fun `le niveau choisi donne au plus un paquet par pixel`() = runBlocking {
        val pyramid = WaveformPyramid.build(sineBuffer(500_000))
        for (fpp in listOf(256f, 1000f, 4096f, 50_000f)) {
            val level = pyramid.levels[pyramid.levelFor(fpp)]
            assertTrue(
                "fpp=$fpp paquet=${level.bucketFrames}",
                level.bucketFrames <= fpp || level.bucketFrames == WaveformPyramid.BASE_BUCKET_FRAMES,
            )
        }
    }
}
