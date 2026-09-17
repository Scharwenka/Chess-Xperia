package com.scharwenka.xperiamix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.scharwenka.xperiamix.decode.PcmBuffer
import com.scharwenka.xperiamix.model.TrackState
import com.scharwenka.xperiamix.waveform.WaveformPyramid
import kotlin.math.abs
import android.graphics.Paint as AndroidPaint

/** Disposition des deux pistes : l'une sous l'autre, ou superposees. */
enum class LaneLayout { STACKED, OVERLAID }

/**
 * Plan de travail : regle temporelle, deux formes d'onde, curseur de lecture.
 *
 * Gestes :
 *  - pincer   : zoom centre sur les doigts (jusqu'a l'echantillon) ;
 *  - glisser  : defilement ;
 *  - toucher  : deplacement du curseur de lecture ;
 *  - glisser sur la piste B en mode calage : decalage temporel de la piste.
 *
 * Le mode calage prend la priorite sur le defilement parce que le detecteur de
 * la piste consomme l'evenement avant que le conteneur ne le voie.
 */
@Composable
fun WaveformTimeline(
    state: TimelineState,
    trackA: TrackState,
    trackB: TrackState,
    layout: LaneLayout,
    slipEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onSlip: (Long) -> Unit,
    onSlipFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colorA = MaterialTheme.colorScheme.primary
    val colorB = MaterialTheme.colorScheme.tertiary

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .onSizeChanged { state.widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    state.zoomBy(zoom, centroid.x)
                    state.panBy(pan.x)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    onSeek(state.frameAt(position.x).coerceAtLeast(0L))
                }
            }
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeRuler(
                state = state,
                sampleRate = trackA.sampleRate,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )

            when (layout) {
                LaneLayout.STACKED -> {
                    WaveformLane(
                        state = state,
                        track = trackA,
                        color = colorA,
                        label = "A",
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    WaveformLane(
                        state = state,
                        track = trackB,
                        color = colorB,
                        label = "B",
                        slipEnabled = slipEnabled,
                        onSlip = onSlip,
                        onSlipFinished = onSlipFinished,
                        haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }

                LaneLayout.OVERLAID -> Box(Modifier.fillMaxWidth().weight(1f)) {
                    // Superposition : c'est la vue qui permet de juger visuellement
                    // d'un calage a quelques millisecondes pres.
                    WaveformLane(state, trackA, colorA.copy(alpha = 0.75f), "A", Modifier.fillMaxSize())
                    WaveformLane(
                        state = state,
                        track = trackB,
                        color = colorB.copy(alpha = 0.75f),
                        label = "B",
                        slipEnabled = slipEnabled,
                        onSlip = onSlip,
                        onSlipFinished = onSlipFinished,
                        haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        PlayheadOverlay(state, Modifier.fillMaxSize())
    }
}

/**
 * Une piste. Le Canvas lit `state.firstFrame` / `framesPerPixel` **dans** la
 * lambda de dessin : seul le dessin est invalide quand la vue bouge.
 */
@Composable
fun WaveformLane(
    state: TimelineState,
    track: TrackState,
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    slipEnabled: Boolean = false,
    onSlip: (Long) -> Unit = {},
    onSlipFinished: () -> Unit = {},
    haptic: () -> Unit = {},
) {
    val painter = remember { WaveformPainter(color, color.copy(alpha = 0.45f)) }
    SideEffect { painter.setColors(color, color.copy(alpha = 0.45f)) }
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    // Accumulateur de fractions de trame : au zoom maximal un pixel vaut 0,05
    // trame, un arrondi par evenement de glissement immobiliserait la piste.
    var slipAccumulator by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(
                if (!slipEnabled) Modifier else Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { slipAccumulator = 0f },
                        onDragEnd = { slipAccumulator = 0f; onSlipFinished() },
                        onDragCancel = { slipAccumulator = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        slipAccumulator += dragAmount * state.framesPerPixel
                        val delta = slipAccumulator.toLong()
                        if (delta != 0L) {
                            slipAccumulator -= delta
                            val before = track.offsetFrames
                            onSlip(delta)
                            // Retour haptique au passage par le calage nul :
                            // reperage au doigt sans quitter l'ecran des yeux.
                            if (before != 0L && (before > 0) != (before + delta > 0)) haptic()
                        }
                    }
                }
            )
    ) {
        val pyramid: WaveformPyramid? = track.waveform
        val pcm: PcmBuffer? = track.pcm

        Canvas(Modifier.fillMaxSize()) {
            // --- phase de dessin : lectures d'etat confinees ici ---
            val first = state.firstFrame
            val fpp = state.framesPerPixel

            drawLine(
                color = gridColor,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1f,
            )
            if (pyramid != null) {
                painter.draw(this, pyramid, pcm, first, fpp, track.offsetFrames)
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
        if (track.offsetFrames != 0L) {
            Text(
                text = formatSignedMillis(track.offsetMillis()),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
    }
}

/** Curseur de lecture, dessine par-dessus les deux pistes. */
@Composable
private fun PlayheadOverlay(state: TimelineState, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val x = state.xOf(state.playheadFrame)
        if (x < -2f || x > size.width + 2f) return@Canvas
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
    }
}

/**
 * Regle temporelle. Le pas est choisi pour qu'une graduation occupe au moins
 * 120 pixels : sur les 3840 pixels de large du Xperia 1 V en paysage, cela
 * laisse une trentaine de reperes lisibles.
 */
@Composable
fun TimeRuler(state: TimelineState, sampleRate: Int, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickColor = MaterialTheme.colorScheme.outline
    val textPaint = remember {
        AndroidPaint().apply {
            isAntiAlias = true
            textSize = 26f
            textAlign = AndroidPaint.Align.LEFT
        }
    }
    SideEffect { textPaint.color = textColor.toArgb() }

    Canvas(modifier) {
        val fpp = state.framesPerPixel
        val first = state.firstFrame
        val stepMs = chooseTickMillis(fpp, sampleRate, minPixels = 120f)
        val stepFrames = (stepMs * sampleRate / 1000.0).toLong().coerceAtLeast(1L)

        var frame = (first / stepFrames) * stepFrames
        if (frame < first) frame += stepFrames
        while (true) {
            val x = state.xOf(frame)
            if (x > size.width) break
            drawLine(tickColor, Offset(x, size.height * 0.55f), Offset(x, size.height), 1f)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(
                    formatTimecode(frame, sampleRate, stepMs),
                    x + 4f,
                    size.height * 0.5f,
                    textPaint,
                )
            }
            frame += stepFrames
        }
    }
}

private val TICK_STEPS_MS = longArrayOf(
    1, 2, 5, 10, 20, 50, 100, 200, 500,
    1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000,
)

internal fun chooseTickMillis(framesPerPixel: Float, sampleRate: Int, minPixels: Float): Long {
    val millisPerPixel = framesPerPixel * 1000.0 / sampleRate
    val minimum = millisPerPixel * minPixels
    return TICK_STEPS_MS.firstOrNull { it >= minimum } ?: TICK_STEPS_MS.last()
}

internal fun formatTimecode(frame: Long, sampleRate: Int, stepMs: Long): String {
    val totalMs = frame * 1000.0 / sampleRate
    val negative = totalMs < 0
    val ms = abs(totalMs).toLong()
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1000
    val millis = ms % 1000
    val sign = if (negative) "-" else ""
    return if (stepMs < 1000) {
        "%s%d:%02d.%03d".format(sign, minutes, seconds, millis)
    } else {
        "%s%d:%02d".format(sign, minutes, seconds)
    }
}

internal fun formatSignedMillis(millis: Double): String =
    if (millis >= 0) "+%.1f ms".format(millis) else "%.1f ms".format(millis)
