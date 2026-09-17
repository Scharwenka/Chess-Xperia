package com.scharwenka.xperiamix.align

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Primitives d'analyse partagees par le calage automatique. */
object Dsp {

    fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    fun hann(size: Int): FloatArray =
        FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / size)).toFloat() }

    /**
     * Filtre passe-bas + decimation d'un facteur entier, en une seule passe :
     * seuls les echantillons conserves sont calcules.
     *
     * Sans le passe-bas, tout le contenu au-dessus de la nouvelle frequence de
     * Nyquist se replierait dans la bande d'analyse et brouillerait la
     * correlation - une caisse claire a 5 kHz reapparaitrait a 1 kHz.
     */
    fun decimate(source: FloatArray, factor: Int): FloatArray {
        if (factor <= 1) return source
        val taps = buildLowPass(factor)
        val half = taps.size / 2
        val outCount = source.size / factor
        val out = FloatArray(outCount)
        for (n in 0 until outCount) {
            val center = n * factor
            var acc = 0.0
            for (k in taps.indices) {
                val idx = center + k - half
                if (idx in source.indices) acc += taps[k] * source[idx]
            }
            out[n] = acc.toFloat()
        }
        return out
    }

    /** Sinc fenetre Blackman, coupure a 90 % de la nouvelle frequence de Nyquist. */
    private fun buildLowPass(factor: Int): DoubleArray {
        val taps = 16 * factor + 1
        val half = taps / 2
        val fc = 0.45 / factor
        var sum = 0.0
        val h = DoubleArray(taps) { i ->
            val x = (i - half).toDouble()
            val w = 0.42 - 0.5 * cos(2.0 * PI * i / (taps - 1)) + 0.08 * cos(4.0 * PI * i / (taps - 1))
            val v = 2.0 * fc * sinc(2.0 * fc * x) * w
            sum += v
            v
        }
        for (i in h.indices) h[i] /= sum
        return h
    }

    /**
     * Enveloppe de flux spectral : somme des augmentations d'energie par bande
     * entre deux trames successives. C'est un detecteur de transitoires, donc
     * une representation de "quand ca attaque" independante du timbre.
     *
     * C'est ce qui permet de caler deux captations tres differentes (un enregistreur
     * de salle et une sortie console) la ou une correlation forme d'onde a forme
     * d'onde echouerait.
     */
    fun onsetEnvelope(x: FloatArray, frameSize: Int = 1024, hop: Int = 256): FloatArray {
        if (x.size < frameSize) return FloatArray(0)
        val window = hann(frameSize)
        val frames = 1 + (x.size - frameSize) / hop
        val env = FloatArray(frames)
        val re = DoubleArray(frameSize)
        val im = DoubleArray(frameSize)
        var previous = DoubleArray(frameSize / 2)
        val current = DoubleArray(frameSize / 2)

        for (f in 0 until frames) {
            val start = f * hop
            for (i in 0 until frameSize) {
                re[i] = x[start + i].toDouble() * window[i]
                im[i] = 0.0
            }
            Fft.transform(re, im, inverse = false)
            var flux = 0.0
            for (k in 0 until frameSize / 2) {
                current[k] = hypot(re[k], im[k])
                val d = current[k] - previous[k]
                if (d > 0) flux += d          // demi-onde : seules les montees comptent
            }
            env[f] = flux.toFloat()
            val swap = previous
            previous = current.copyInto(swap)
        }
        return env
    }

    /** Centre-reduit sur place ; renvoie false si le signal est constant. */
    fun zScore(x: FloatArray): Boolean {
        if (x.isEmpty()) return false
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var variance = 0.0
        for (v in x) {
            val d = v - mean
            variance += d * d
        }
        variance /= x.size
        val sd = sqrt(variance)
        if (sd < 1e-12) return false
        for (i in x.indices) x[i] = ((x[i] - mean) / sd).toFloat()
        return true
    }

    /**
     * Correlation croisee par FFT, renvoyee pour les decalages -maxLag..+maxLag
     * (index `lag + maxLag`).
     *
     * Convention de signe : `c[lag] ~ somme_t a[t + lag] * b[t]`. Un pic en
     * `lag` signifie donc qu'un evenement situe a l'instant t dans B se retrouve
     * a t + lag dans A, autrement dit **lag = t_A - t_B**. Pour aligner B sur A,
     * on ajoute `lag` a l'offset de B.
     *
     * [phat] active la transformation de phase (GCC-PHAT) : le spectre croise est
     * ramene a un module unitaire avant transformee inverse. Le pic devient
     * quasi impulsionnel et l'estimation cesse de dependre du spectre du signal,
     * ce qui la rend robuste a une reverberation ou a une egalisation differente
     * entre les deux prises.
     */
    fun crossCorrelate(a: FloatArray, b: FloatArray, maxLag: Int, phat: Boolean): DoubleArray {
        val n = Fft.nextPowerOfTwo(a.size + b.size)
        val aRe = DoubleArray(n)
        val aIm = DoubleArray(n)
        val bRe = DoubleArray(n)
        val bIm = DoubleArray(n)
        for (i in a.indices) aRe[i] = a[i].toDouble()
        for (i in b.indices) bRe[i] = b[i].toDouble()

        Fft.transform(aRe, aIm, inverse = false)
        Fft.transform(bRe, bIm, inverse = false)

        for (i in 0 until n) {
            // A * conj(B)
            val cRe = aRe[i] * bRe[i] + aIm[i] * bIm[i]
            val cIm = aIm[i] * bRe[i] - aRe[i] * bIm[i]
            if (phat) {
                val mag = hypot(cRe, cIm)
                val g = if (mag > 1e-12) 1.0 / mag else 0.0
                aRe[i] = cRe * g
                aIm[i] = cIm * g
            } else {
                aRe[i] = cRe
                aIm[i] = cIm
            }
        }
        Fft.transform(aRe, aIm, inverse = true)

        val lags = DoubleArray(2 * maxLag + 1)
        for (lag in -maxLag..maxLag) {
            val idx = if (lag >= 0) lag else n + lag
            lags[lag + maxLag] = if (idx in 0 until n) aRe[idx] else 0.0
        }
        return lags
    }

    /** Correlation directe normalisee (Pearson) pour un decalage donne. */
    fun normalizedCorrelation(a: FloatArray, b: FloatArray, lag: Int): Double {
        var num = 0.0
        var ea = 0.0
        var eb = 0.0
        for (t in b.indices) {
            val i = t + lag
            if (i < 0 || i >= a.size) continue
            num += a[i].toDouble() * b[t]
            ea += a[i].toDouble() * a[i]
            eb += b[t].toDouble() * b[t]
        }
        val denom = sqrt(ea * eb)
        return if (denom < 1e-12) 0.0 else num / denom
    }

    /** Index du maximum en valeur absolue. */
    fun argMaxAbs(values: DoubleArray): Int {
        var best = 0
        var bestValue = -1.0
        for (i in values.indices) {
            val v = abs(values[i])
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }

    /**
     * Interpolation parabolique autour du pic : recupere une precision
     * inferieure a l'echantillon (utile quand la difference de trajet entre
     * deux micros ne tombe pas sur un multiple exact de la periode).
     */
    fun parabolicOffset(previous: Double, peak: Double, next: Double): Double {
        val denom = previous - 2 * peak + next
        if (abs(denom) < 1e-15) return 0.0
        return (0.5 * (previous - next) / denom).coerceIn(-1.0, 1.0)
    }

    /**
     * Rapport pic / lobes secondaires : mesure la nettete du pic hors d'une zone
     * de garde. Un plateau (signal periodique, boucle) donne un rapport proche
     * de 1 et doit etre signale comme non fiable.
     */
    fun peakToSidelobe(values: DoubleArray, peakIndex: Int, guard: Int): Double {
        var side = 0.0
        var count = 0
        for (i in values.indices) {
            if (abs(i - peakIndex) <= guard) continue
            side = max(side, abs(values[i]))
            count++
        }
        if (count == 0 || side < 1e-15) return Double.MAX_VALUE
        return abs(values[peakIndex]) / side
    }

    /** Debut de la fenetre de [length] echantillons la plus energetique. */
    fun bestEnergyWindow(x: FloatArray, length: Int): Int {
        if (x.size <= length) return 0
        var energy = 0.0
        for (i in 0 until length) energy += x[i].toDouble() * x[i]
        var best = 0
        var bestEnergy = energy
        for (i in length until x.size) {
            energy += x[i].toDouble() * x[i] - x[i - length].toDouble() * x[i - length]
            if (energy > bestEnergy) {
                bestEnergy = energy
                best = i - length + 1
            }
        }
        return best
    }
}
