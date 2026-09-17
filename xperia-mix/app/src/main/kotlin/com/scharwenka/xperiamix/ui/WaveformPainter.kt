package com.scharwenka.xperiamix.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.scharwenka.xperiamix.decode.PcmBuffer
import com.scharwenka.xperiamix.waveform.WaveformPyramid
import kotlin.math.ceil
import kotlin.math.floor
import android.graphics.Paint as AndroidPaint

/**
 * Rendu d'une forme d'onde : une colonne de pixels par paquet de crêtes.
 *
 * Trois choix dictent la fluidite :
 *
 * 1. **Un seul appel de dessin.** Tous les segments verticaux sont accumules
 *    dans un `FloatArray` puis envoyes en une fois a `Canvas.drawLines`. Un
 *    `drawLine` par colonne, soit ~3800 appels sur la dalle du Xperia 1 V,
 *    couterait a lui seul plus que le budget de 8,3 ms d'une trame a 120 Hz.
 *
 * 2. **Tableaux reutilises.** Ils sont alloues une fois pour la largeur
 *    maximale : la phase de dessin ne doit jamais declencher le GC.
 *
 * 3. **Cout independant de la duree.** La pyramide fournit un niveau de detail
 *    ou un paquet vaut environ un pixel ; dessiner 10 s ou 10 min demande le
 *    meme travail.
 *
 * Au-dela d'un echantillon par pixel (zoom extreme, indispensable pour verifier
 * un calage a l'echantillon pres), on bascule sur le PCM et on trace la
 * polyligne reelle.
 */
class WaveformPainter(
    peakColor: Color,
    rmsColor: Color,
    private val strokeWidth: Float = 1f,
) {
    private val peakPaint = AndroidPaint().apply {
        color = peakColor.toArgb()
        this.strokeWidth = this@WaveformPainter.strokeWidth
        isAntiAlias = false          // segments verticaux : l'anticrenelage ne fait que flouter
        strokeCap = AndroidPaint.Cap.BUTT
    }

    private val rmsPaint = AndroidPaint().apply {
        color = rmsColor.toArgb()
        this.strokeWidth = this@WaveformPainter.strokeWidth
        isAntiAlias = false
        strokeCap = AndroidPaint.Cap.BUTT
    }

    private val curvePaint = AndroidPaint().apply {
        color = peakColor.toArgb()
        this.strokeWidth = 2f
        isAntiAlias = true           // ici les traits sont obliques : l'anticrenelage compte
        strokeCap = AndroidPaint.Cap.ROUND
    }

    private var peakLines = FloatArray(0)
    private var rmsLines = FloatArray(0)

    fun setColors(peak: Color, rms: Color) {
        peakPaint.color = peak.toArgb()
        curvePaint.color = peak.toArgb()
        rmsPaint.color = rms.toArgb()
    }

    /**
     * @param firstFrame trame de timeline affichee en x = 0
     * @param offsetFrames decalage de la piste sur la timeline (calage)
     */
    fun draw(
        scope: DrawScope,
        pyramid: WaveformPyramid,
        pcm: PcmBuffer?,
        firstFrame: Long,
        framesPerPixel: Float,
        offsetFrames: Long,
    ) {
        val width = scope.size.width.toInt()
        if (width <= 0) return
        val centerY = scope.size.height / 2f
        val halfHeight = centerY - 1f

        if (framesPerPixel < 1f && pcm != null) {
            drawSamples(scope, pcm, firstFrame, framesPerPixel, offsetFrames, centerY, halfHeight)
            return
        }

        ensureCapacity(width)
        val level = pyramid.levels[pyramid.levelFor(framesPerPixel)]
        val bucket = level.bucketFrames
        val fpp = framesPerPixel.toDouble()

        var peakCount = 0
        var rmsCount = 0

        for (x in 0 until width) {
            // Arithmetique en double : a 65 000 trames/pixel sur 3840 pixels, un
            // float perdrait deja des trames entieres par arrondi.
            val start = firstFrame + (x * fpp).toLong() - offsetFrames
            val end = firstFrame + ((x + 1) * fpp).toLong() - offsetFrames
            if (end < 0 || start >= pyramid.frameCount) continue

            val b0 = floor(start.toDouble() / bucket).toInt().coerceAtLeast(0)
            val b1 = (ceil(end.toDouble() / bucket).toInt() - 1)
                .coerceAtMost(level.bucketCount - 1)
            if (b1 < b0) continue

            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var rms = 0f
            for (b in b0..b1) {
                if (level.mins[b] < lo) lo = level.mins[b]
                if (level.maxs[b] > hi) hi = level.maxs[b]
                if (level.rms[b] > rms) rms = level.rms[b]
            }
            if (lo == Float.MAX_VALUE) continue

            val px = x + 0.5f
            peakLines[peakCount++] = px
            peakLines[peakCount++] = centerY - hi * halfHeight
            peakLines[peakCount++] = px
            peakLines[peakCount++] = centerY - lo * halfHeight

            rmsLines[rmsCount++] = px
            rmsLines[rmsCount++] = centerY - rms * halfHeight
            rmsLines[rmsCount++] = px
            rmsLines[rmsCount++] = centerY + rms * halfHeight
        }

        scope.drawIntoCanvas { canvas ->
            if (peakCount > 0) canvas.nativeCanvas.drawLines(peakLines, 0, peakCount, peakPaint)
            if (rmsCount > 0) canvas.nativeCanvas.drawLines(rmsLines, 0, rmsCount, rmsPaint)
        }
    }

    /** Zoom au-dela de l'echantillon : polyligne du signal reel. */
    private fun drawSamples(
        scope: DrawScope,
        pcm: PcmBuffer,
        firstFrame: Long,
        framesPerPixel: Float,
        offsetFrames: Long,
        centerY: Float,
        halfHeight: Float,
    ) {
        val width = scope.size.width
        val start = (firstFrame - offsetFrames).coerceAtLeast(0L)
        val visible = (width * framesPerPixel).toLong() + 2
        val end = (start + visible).coerceAtMost(pcm.frameCount)
        val count = (end - start).toInt()
        if (count < 2) return

        ensureCapacity(count + 2)
        var i = 0
        var previousX = ((start - firstFrame + offsetFrames) / framesPerPixel)
        var previousY = centerY - pcm.monoAt(start) * halfHeight
        for (f in start + 1 until end) {
            val x = ((f - firstFrame + offsetFrames) / framesPerPixel)
            val y = centerY - pcm.monoAt(f) * halfHeight
            peakLines[i++] = previousX
            peakLines[i++] = previousY
            peakLines[i++] = x
            peakLines[i++] = y
            previousX = x
            previousY = y
        }
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawLines(peakLines, 0, i, curvePaint)
        }
    }

    private fun ensureCapacity(columns: Int) {
        val needed = columns * 4
        if (peakLines.size < needed) {
            peakLines = FloatArray(needed)
            rmsLines = FloatArray(needed)
        }
    }
}
