package com.scharwenka.xperiamix.align

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FftTest {

    @Test
    fun `transformee comparee a une DFT naive`() {
        val n = 64
        val random = Random(1)
        val x = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft.transform(re, im, inverse = false)

        for (k in 0 until n) {
            var dftRe = 0.0
            var dftIm = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                dftRe += x[t] * cos(angle)
                dftIm += x[t] * sin(angle)
            }
            assertEquals(dftRe, re[k], 1e-9)
            assertEquals(dftIm, im[k], 1e-9)
        }
    }

    @Test
    fun `aller-retour sans perte`() {
        val n = 256
        val random = Random(2)
        val x = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = x.copyOf()
        val im = DoubleArray(n)
        Fft.transform(re, im, inverse = false)
        Fft.transform(re, im, inverse = true)
        for (i in 0 until n) assertEquals(x[i], re[i], 1e-12)
    }
}

class CrossCorrelationTest {

    /**
     * Verifie la convention de signe, qui conditionne tout le reste : un pic au
     * decalage `lag` signifie `lag = t_A - t_B`, donc `offsetB += lag` aligne B sur A.
     */
    @Test
    fun `le signe du decalage suit la convention t_A moins t_B`() {
        val n = 1024
        val a = FloatArray(n)
        val b = FloatArray(n)
        a[100] = 1f
        b[300] = 1f

        val corr = Dsp.crossCorrelate(a, b, maxLag = 512, phat = false)
        val lag = Dsp.argMaxAbs(corr) - 512

        assertEquals(-200, lag)
        assertEquals(100, 300 + lag)   // B decale de `lag` tombe sur A
    }

    @Test
    fun `retrouve un decalage sur un signal bruite`() {
        val random = Random(7)
        val source = FloatArray(4000) { random.nextDouble(-1.0, 1.0).toFloat() }
        val trueLag = 137
        val a = FloatArray(5000)
        val b = FloatArray(5000)
        for (i in source.indices) {
            a[i + 500 + trueLag] = source[i]
            b[i + 500] = source[i] + random.nextDouble(-0.3, 0.3).toFloat()
        }

        val plain = Dsp.crossCorrelate(a, b, maxLag = 400, phat = false)
        assertEquals(trueLag, Dsp.argMaxAbs(plain) - 400)

        val phat = Dsp.crossCorrelate(a, b, maxLag = 400, phat = true)
        val peak = Dsp.argMaxAbs(phat)
        assertEquals(trueLag, peak - 400)
        // La transformation de phase doit produire un pic nettement plus fin.
        assertTrue(
            "pic/lobes trop faible",
            Dsp.peakToSidelobe(phat, peak, guard = 8) > Dsp.peakToSidelobe(plain, peak, guard = 8),
        )
    }

    @Test
    fun `une polarite opposee donne un pic negatif`() {
        val random = Random(11)
        val source = FloatArray(2048) { random.nextDouble(-1.0, 1.0).toFloat() }
        val a = FloatArray(3000)
        val b = FloatArray(3000)
        for (i in source.indices) {
            a[i + 400] = source[i]
            b[i + 400] = -source[i]
        }
        val corr = Dsp.crossCorrelate(a, b, maxLag = 128, phat = false)
        val peak = Dsp.argMaxAbs(corr)
        assertEquals(0, peak - 128)
        assertTrue("le pic devrait etre negatif", corr[peak] < 0)
    }

    @Test
    fun `interpolation parabolique du sommet`() {
        // Parabole de sommet en +0,25 : y = -(x - 0,25)^2
        val values = doubleArrayOf(-1.5625, -0.0625, -0.5625)
        assertEquals(0.25, Dsp.parabolicOffset(values[0], values[1], values[2]), 1e-9)
    }

    @Test
    fun `la decimation attenue ce qui depasse la nouvelle bande`() {
        val rate = 48_000
        val factor = 4
        val n = 8192
        // Sinus a 9 kHz : au-dessus des 6 kHz de Nyquist apres decimation par 4.
        val tone = FloatArray(n) { sin(2.0 * PI * 9000.0 * it / rate).toFloat() }
        val decimated = Dsp.decimate(tone, factor)

        var energy = 0.0
        for (v in decimated) energy += v.toDouble() * v
        val rms = kotlin.math.sqrt(energy / decimated.size)
        // Sans filtre, le repliement ressortirait a 3 kHz avec un RMS proche de 0,7.
        assertTrue("repliement non filtre : RMS = $rms", rms < 0.02)
    }

    @Test
    fun `l-enveloppe de transitoires marque les attaques`() {
        val rate = 12_000
        val hop = 256
        val onsets = listOf(rate / 2, rate, rate * 3 / 2)
        val x = FloatArray(rate * 2)
        val random = Random(3)
        // Trois impulsions bruitees amorties, espacees d'une demi-seconde.
        for (onset in onsets) {
            for (i in 0 until 600) {
                x[onset + i] = (random.nextDouble(-1.0, 1.0) * (1.0 - i / 600.0)).toFloat()
            }
        }

        val envelope = Dsp.onsetEnvelope(x, frameSize = 1024, hop = hop)
        assertTrue(envelope.size > 50)

        for (onset in onsets) {
            val center = onset / hop
            // Le flux monte des que la fenetre d'analyse mord sur l'attaque, donc
            // legerement avant la trame contenant l'echantillon d'attaque.
            val atOnset = (center - 3..center + 4)
                .filter { it in envelope.indices }
                .maxOf { envelope[it] }
            val quiet = (center + 12..center + 16)
                .filter { it in envelope.indices }
                .maxOf { envelope[it] }
            assertTrue(
                "attaque $onset : flux $atOnset contre $quiet en zone calme",
                atOnset > 10f * maxOf(quiet, 1e-6f),
            )
        }
    }
}
