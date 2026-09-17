package com.scharwenka.xperiamix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.scharwenka.xperiamix.model.AlignmentResult
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Reglage du calage de la piste B par rapport a la piste A.
 *
 * Trois granularites, parce qu'aucune seule ne couvre le besoin :
 *  - le curseur pour la recherche grossiere (+/- 2 s, resolution ~4 ms au doigt) ;
 *  - les boutons pas a pas pour le reglage fin (10 ms, 1 ms) ;
 *  - le pas a l'echantillon (~21 us a 48 kHz) pour la mise en phase de deux
 *    captations du meme signal, ou une milliseconde d'ecart suffit deja a
 *    creuser un filtre en peigne audible.
 *
 * Au-dela de +/- 2 s, le calage se fait au doigt sur la forme d'onde ou par
 * l'analyse automatique.
 */
@Composable
fun OffsetControl(
    offsetFrames: Long,
    sampleRate: Int,
    onOffsetChange: (Long) -> Unit,
    onAutoAlign: () -> Unit,
    autoAlignEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val offsetMs = offsetFrames * 1000.0 / sampleRate
    val framesPerMs = sampleRate / 1000.0

    Card(modifier = modifier) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Decalage B / A", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${formatSignedMillis(offsetMs)}  (${offsetFrames} tr.)",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Slider(
                value = offsetMs.toFloat().coerceIn(-SLIDER_RANGE_MS, SLIDER_RANGE_MS),
                onValueChange = { onOffsetChange((it * framesPerMs).roundToLong()) },
                valueRange = -SLIDER_RANGE_MS..SLIDER_RANGE_MS,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NudgeButton("-10 ms") { onOffsetChange(offsetFrames - (10 * framesPerMs).toLong()) }
                NudgeButton("-1 ms") { onOffsetChange(offsetFrames - framesPerMs.toLong()) }
                NudgeButton("-1 tr.") { onOffsetChange(offsetFrames - 1) }
                NudgeButton("+1 tr.") { onOffsetChange(offsetFrames + 1) }
                NudgeButton("+1 ms") { onOffsetChange(offsetFrames + framesPerMs.toLong()) }
                NudgeButton("+10 ms") { onOffsetChange(offsetFrames + (10 * framesPerMs).toLong()) }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onAutoAlign,
                    enabled = autoAlignEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null)
                    Text("  Calage auto")
                }
                OutlinedButton(onClick = { onOffsetChange(0) }) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "Remettre a zero")
                }
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Compte rendu du calage automatique.
 *
 * Le resultat n'est jamais applique d'office : une correlation peut se tromper
 * de periode sur un signal repetitif. On expose donc la confiance et le rapport
 * pic / lobes, et l'utilisateur tranche.
 */
@Composable
fun AlignmentBanner(
    result: AlignmentResult,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Decalage detecte : ${formatSignedMillis(result.lagMillis)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("confiance ${(result.confidence * 100).roundToLong()} %") },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("pic/lobes ${"%.1f".format(result.peakToSidelobe)}") },
                )
                AssistChip(onClick = {}, label = { Text(methodLabel(result.method)) })
            }
            if (result.polarityInverted) {
                Text(
                    "Polarite opposee detectee : inverser la piste B ameliorera la somme.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            result.driftPpm?.let { ppm ->
                if (abs(ppm) > 5) {
                    Text(
                        "Derive d'horloge estimee : ${"%.1f".format(ppm)} ppm " +
                            "(~${"%.0f".format(abs(ppm) * 3.6)} ms par heure). " +
                            "Un decalage constant ne tiendra pas sur toute la duree.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!result.isReliable) {
                Text(
                    "Correlation peu marquee : verifier a l'oreille avant d'appliquer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply) { Text("Appliquer") }
                TextButton(onClick = onDismiss) { Text("Ignorer") }
            }
        }
    }
}

private fun methodLabel(method: AlignmentResult.Method): String = when (method) {
    AlignmentResult.Method.ENVELOPE -> "enveloppes"
    AlignmentResult.Method.GCC_PHAT -> "GCC-PHAT"
    AlignmentResult.Method.HYBRID -> "hybride"
}

private const val SLIDER_RANGE_MS = 2000f
