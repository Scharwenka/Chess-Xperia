package com.scharwenka.xperiamix.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * Fenetre de visualisation partagee par les deux pistes.
 *
 * Point de performance essentiel : ces trois valeurs sont des `MutableState`
 * lues **uniquement dans les lambdas de dessin**. Compose invalide alors la
 * seule phase de dessin, sans recomposition ni remesure. Un zoom ou un
 * defilement se resume donc a redessiner un Canvas - ce qui tient largement
 * dans les 8,3 ms d'une trame a 120 Hz sur le Xperia 1 V.
 *
 * Lire `framesPerPixel` dans le corps d'un composable au lieu du bloc de dessin
 * suffirait a declencher une recomposition par trame de geste : c'est l'erreur
 * classique sur ce genre d'ecran.
 */
@Stable
class TimelineState(
    initialFramesPerPixel: Float = 512f,
    private val minFramesPerPixel: Float = 0.05f,   // ~20 pixels par echantillon
    private val maxFramesPerPixel: Float = 65_536f,
) {
    var firstFrame by mutableLongStateOf(0L)
        private set

    var framesPerPixel by mutableFloatStateOf(initialFramesPerPixel)
        private set

    /** Position du curseur de lecture, en trames de timeline. */
    var playheadFrame by mutableLongStateOf(0L)

    /** Duree totale de la timeline, bornant le defilement. */
    var totalFrames by mutableLongStateOf(0L)

    /**
     * Largeur du plan de travail, renseignee par `onSizeChanged`. La mesure ne
     * peut pas etre lue depuis la phase de dessin sans risquer une boucle
     * invalidation -> dessin -> invalidation.
     */
    var widthPx by mutableFloatStateOf(1f)

    fun frameAt(x: Float): Long = firstFrame + (x * framesPerPixel).toLong()

    fun xOf(frame: Long): Float = (frame - firstFrame) / framesPerPixel

    /** Zoom centre sur [pivotX] : la trame sous le doigt reste sous le doigt. */
    fun zoomBy(factor: Float, pivotX: Float) {
        if (factor == 1f) return
        val pivotFrame = firstFrame + pivotX * framesPerPixel
        framesPerPixel = (framesPerPixel / factor).coerceIn(minFramesPerPixel, maxFramesPerPixel)
        firstFrame = (pivotFrame - pivotX * framesPerPixel).toLong()
        clamp()
    }

    fun panBy(deltaPx: Float) {
        firstFrame -= (deltaPx * framesPerPixel).toLong()
        clamp()
    }

    fun scrollTo(frame: Long) {
        firstFrame = frame
        clamp()
    }

    /** Recentre la vue sur le curseur si celui-ci sort de l'ecran. */
    fun followPlayhead() {
        val x = xOf(playheadFrame)
        if (x < widthPx * 0.1f || x > widthPx * 0.9f) {
            firstFrame = playheadFrame - (widthPx * 0.1f * framesPerPixel).toLong()
            clamp()
        }
    }

    /** Ajuste le zoom pour afficher toute la timeline. */
    fun fitToWidth() {
        if (totalFrames <= 0 || widthPx <= 1f) return
        framesPerPixel = (totalFrames / widthPx).coerceIn(minFramesPerPixel, maxFramesPerPixel)
        firstFrame = 0
    }

    private fun clamp() {
        val visible = (widthPx * framesPerPixel).toLong()
        val maxFirst = (totalFrames - visible / 2).coerceAtLeast(0L)
        firstFrame = firstFrame.coerceIn(-visible / 2, maxFirst)
    }
}
