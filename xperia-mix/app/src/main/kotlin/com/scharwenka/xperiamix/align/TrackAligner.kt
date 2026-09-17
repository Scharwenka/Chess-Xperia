package com.scharwenka.xperiamix.align

import com.scharwenka.xperiamix.decode.PcmBuffer
import com.scharwenka.xperiamix.model.AlignmentResult
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Calage automatique de deux pistes par correlation croisee, en trois etages.
 *
 * Un seul etage ne suffit pas :
 *  - une correlation a pleine resolution sur 10 minutes demanderait une FFT de
 *    2^26 points par piste (~1,6 Go en double precision) ;
 *  - une correlation d'enveloppes est peu couteuse et tres robuste, mais sa
 *    resolution est celle du pas d'analyse (~21 ms) ;
 *  - une GCC-PHAT donne un pic tres fin mais peut accrocher un mauvais lobe si
 *    la plage de recherche n'est pas deja restreinte.
 *
 * D'ou l'enchainement grossier -> fin -> exact :
 *
 *  1. ETAGE ENVELOPPE  : flux spectral des deux pistes (~47 Hz), correlation
 *     normalisee sur toute la duree. Donne un decalage a +/- 21 ms et, surtout,
 *     un indice de confiance global.
 *  2. ETAGE GCC-PHAT   : sur la fenetre la plus energetique (20 s) au taux
 *     d'analyse, recherche restreinte a +/- 3 pas autour du resultat de l'etage 1.
 *     Precision : un echantillon d'analyse, soit ~83 us a 12 kHz.
 *  3. ETAGE PLEIN TAUX : correlation directe sur +/- un facteur de decimation
 *     a la frequence d'origine, puis interpolation parabolique. Precision finale
 *     inferieure a l'echantillon (~20 us a 48 kHz), et signe du pic = polarite.
 *
 * Le resultat est un decalage a ajouter a l'offset de la piste B pour la caler
 * sur la piste A (convention de [Dsp.crossCorrelate] : lag = t_A - t_B).
 */
object TrackAligner {

    data class Config(
        /** Plage de recherche maximale, en secondes. */
        val maxLagSeconds: Double = 30.0,
        /** Taux d'analyse vise ; le facteur de decimation reel est entier. */
        val analysisRateHint: Int = 12_000,
        /** Duree de la fenetre utilisee par les etages 2 et 3. */
        val refineWindowSeconds: Double = 20.0,
        /** Estimation de la derive d'horloge entre les deux enregistreurs. */
        val estimateDrift: Boolean = true,
    )

    suspend fun align(
        trackA: PcmBuffer,
        trackB: PcmBuffer,
        config: Config = Config(),
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): AlignmentResult = withContext(Dispatchers.Default) {
        require(trackA.sampleRate == trackB.sampleRate) {
            "Les deux pistes doivent partager le meme taux d'echantillonnage"
        }
        val rate = trackA.sampleRate
        val factor = (rate.toDouble() / config.analysisRateHint).roundToInt().coerceAtLeast(1)
        val analysisRate = rate / factor

        onProgress("Preparation", 0f)
        val monoA = decimated(trackA, factor)
        coroutineContext.ensureActive()
        val monoB = decimated(trackB, factor)
        onProgress("Preparation", 0.25f)

        // --- Etage 1 : enveloppes de transitoires -------------------------------
        val hop = ENVELOPE_HOP
        val envA = Dsp.onsetEnvelope(monoA, ENVELOPE_FRAME, hop)
        val envB = Dsp.onsetEnvelope(monoB, ENVELOPE_FRAME, hop)
        onProgress("Enveloppes", 0.5f)

        val envelopeUsable = envA.size > 8 && envB.size > 8 && Dsp.zScore(envA) && Dsp.zScore(envB)
        var coarseLagSamples = 0L
        var envelopeConfidence = 0f
        var envelopePsr = 0f

        if (envelopeUsable) {
            val maxLagEnv = ((config.maxLagSeconds * analysisRate) / hop).toInt()
                .coerceIn(1, maxOf(envA.size, envB.size))
            val corr = Dsp.crossCorrelate(envA, envB, maxLagEnv, phat = false)
            val peak = Dsp.argMaxAbs(corr)
            val lagEnv = peak - maxLagEnv
            // Normalisation Pearson : les enveloppes sont deja centrees-reduites,
            // il reste a diviser par le nombre d'echantillons recouverts.
            val overlap = min(envA.size, envB.size) - abs(lagEnv)
            envelopeConfidence = if (overlap > 0) {
                (corr[peak] / overlap).toFloat().coerceIn(-1f, 1f)
            } else 0f
            envelopePsr = Dsp.peakToSidelobe(corr, peak, guard = 3).toFloat()
            coarseLagSamples = lagEnv.toLong() * hop * factor
        }
        onProgress("Correlation", 0.65f)

        // --- Etage 2 : GCC-PHAT autour du resultat grossier ---------------------
        val windowLen = (config.refineWindowSeconds * analysisRate).toInt()
            .coerceAtMost(min(monoA.size, monoB.size))
        val refined = refineWithPhat(
            monoA, monoB,
            coarseLagAnalysis = (coarseLagSamples / factor).toInt(),
            windowLen = windowLen,
            // Si l'etage 1 a echoue (piste sans transitoire : nappe, sinus), la
            // GCC-PHAT doit balayer toute la plage au lieu d'affiner.
            searchRadius = if (envelopeUsable) 3 * hop
            else (config.maxLagSeconds * analysisRate).toInt().coerceAtMost(windowLen - 1),
        )
        onProgress("Affinage", 0.85f)

        // --- Etage 3 : exactitude a l'echantillon, a pleine frequence -----------
        val exact = refineAtFullRate(
            trackA, trackB,
            approximateLag = refined.lagAnalysisSamples.toLong() * factor,
            rate = rate,
            radius = factor * 2,
            windowFrames = (config.refineWindowSeconds * rate).toLong()
                .coerceAtMost(min(trackA.frameCount, trackB.frameCount)),
        )

        // --- Derive d'horloge ---------------------------------------------------
        val drift = if (config.estimateDrift && windowLen > analysisRate) {
            estimateDrift(monoA, monoB, exact.lag / factor, windowLen, analysisRate)
        } else null

        onProgress("Termine", 1f)

        val method = when {
            !envelopeUsable -> AlignmentResult.Method.GCC_PHAT
            refined.searched -> AlignmentResult.Method.HYBRID
            else -> AlignmentResult.Method.ENVELOPE
        }
        AlignmentResult(
            lagFrames = exact.lag,
            lagMillis = exact.lag * 1000.0 / rate + exact.subSample * 1000.0 / rate,
            confidence = maxOf(abs(exact.correlation).toFloat(), abs(envelopeConfidence)),
            peakToSidelobe = maxOf(refined.psr.toFloat(), envelopePsr),
            method = method,
            polarityInverted = exact.correlation < 0,
            driftPpm = drift,
        )
    }

    private data class PhatResult(val lagAnalysisSamples: Int, val psr: Double, val searched: Boolean)

    private data class ExactResult(val lag: Long, val subSample: Double, val correlation: Double)

    /**
     * GCC-PHAT sur la fenetre la plus energetique de A et la fenetre
     * correspondante de B (deduite du decalage grossier).
     */
    private fun refineWithPhat(
        monoA: FloatArray,
        monoB: FloatArray,
        coarseLagAnalysis: Int,
        windowLen: Int,
        searchRadius: Int,
    ): PhatResult {
        if (windowLen < 1024) return PhatResult(coarseLagAnalysis, 0.0, searched = false)

        val startA = Dsp.bestEnergyWindow(monoA, windowLen)
        // lag = t_A - t_B  =>  t_B = t_A - lag
        val startB = (startA - coarseLagAnalysis).coerceIn(0, maxOf(0, monoB.size - windowLen))
        if (monoB.size < windowLen) return PhatResult(coarseLagAnalysis, 0.0, searched = false)

        val a = monoA.copyOfRange(startA, min(startA + windowLen, monoA.size))
        val b = monoB.copyOfRange(startB, min(startB + windowLen, monoB.size))
        if (a.size < 1024 || b.size < 1024) return PhatResult(coarseLagAnalysis, 0.0, searched = false)

        val corr = Dsp.crossCorrelate(a, b, searchRadius, phat = true)
        val peak = Dsp.argMaxAbs(corr)
        val psr = Dsp.peakToSidelobe(corr, peak, guard = 8)
        // Le lag mesure est relatif aux fenetres : on le ramene a la timeline.
        val windowLag = peak - searchRadius
        return PhatResult(windowLag + (startA - startB), psr, searched = true)
    }

    /**
     * Derniere passe a la frequence d'origine : correlation directe sur
     * quelques decalages seulement, donc negligeable en cout, mais c'est elle
     * qui donne la valeur affichee a l'utilisateur.
     */
    private fun refineAtFullRate(
        trackA: PcmBuffer,
        trackB: PcmBuffer,
        approximateLag: Long,
        rate: Int,
        radius: Int,
        windowFrames: Long,
    ): ExactResult {
        val length = windowFrames.coerceAtMost(MAX_FULL_RATE_WINDOW.toLong() * rate).toInt()
        if (length < 1024) return ExactResult(approximateLag, 0.0, 0.0)

        // Fenetre de B centree sur une zone valable pour les deux pistes.
        val startB = ((min(trackA.frameCount, trackB.frameCount) - length) / 2)
            .coerceAtLeast(0)
        val b = FloatArray(length)
        trackB.readMono(b, startB, length)

        val aStart = startB + approximateLag - radius
        val a = FloatArray(length + 2 * radius)
        trackA.readMono(a, aStart, a.size)

        var bestLag = 0
        var bestCorr = 0.0
        val correlations = DoubleArray(2 * radius + 1)
        for (d in -radius..radius) {
            val c = Dsp.normalizedCorrelation(a, b, d + radius)
            correlations[d + radius] = c
            if (abs(c) > abs(bestCorr)) {
                bestCorr = c
                bestLag = d
            }
        }

        val i = bestLag + radius
        val sub = if (i in 1 until correlations.size - 1) {
            Dsp.parabolicOffset(correlations[i - 1], correlations[i], correlations[i + 1])
        } else 0.0

        return ExactResult(approximateLag + bestLag, sub, bestCorr)
    }

    /**
     * Compare le decalage estime au debut et a la fin du programme. Un ecart
     * significatif revele une derive d'horloge entre deux enregistreurs
     * (typiquement 10 a 100 ppm, soit jusqu'a 360 ms sur une heure) : aucun
     * offset constant ne peut alors caler les deux pistes de bout en bout.
     */
    private fun estimateDrift(
        monoA: FloatArray,
        monoB: FloatArray,
        lagAnalysis: Long,
        windowLen: Int,
        analysisRate: Int,
    ): Double? {
        val usable = min(monoA.size, monoB.size)
        val window = min(windowLen, usable / 4)
        if (window < analysisRate) return null

        val earlyA = window
        val lateA = usable - 2 * window
        if (lateA - earlyA < analysisRate * 10) return null

        val early = lagAt(monoA, monoB, earlyA, lagAnalysis, window) ?: return null
        val late = lagAt(monoA, monoB, lateA, lagAnalysis, window) ?: return null

        val deltaSamples = (late - early).toDouble()
        val spanSeconds = (lateA - earlyA).toDouble() / analysisRate
        if (spanSeconds < 1.0) return null
        return (deltaSamples / analysisRate) / spanSeconds * 1e6
    }

    private fun lagAt(
        monoA: FloatArray,
        monoB: FloatArray,
        startA: Int,
        approximateLag: Long,
        window: Int,
    ): Int? {
        val startB = (startA - approximateLag).toInt()
        if (startA < 0 || startB < 0) return null
        if (startA + window > monoA.size || startB + window > monoB.size) return null
        val a = monoA.copyOfRange(startA, startA + window)
        val b = monoB.copyOfRange(startB, startB + window)
        val radius = 256
        val corr = Dsp.crossCorrelate(a, b, radius, phat = true)
        return Dsp.argMaxAbs(corr) - radius
    }

    private fun decimated(pcm: PcmBuffer, factor: Int): FloatArray {
        val frames = pcm.frameCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val mono = FloatArray(frames)
        pcm.readMono(mono, 0, frames)
        return Dsp.decimate(mono, factor)
    }

    private const val ENVELOPE_FRAME = 1024
    private const val ENVELOPE_HOP = 256
    private const val MAX_FULL_RATE_WINDOW = 20  // secondes
}
