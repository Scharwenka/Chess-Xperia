package com.scharwenka.xperiamix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scharwenka.xperiamix.model.TrackState

/**
 * Tranche de console : source, gain, mute / solo / polarite.
 *
 * Le gain est en decibels, pas en pourcentage : c'est l'unite dans laquelle on
 * raisonne quand on equilibre deux sources, et la conversion en gain lineaire
 * ne se fait qu'au moment de pousser la valeur vers le moteur.
 */
@Composable
fun TrackStrip(
    track: TrackState,
    onLoad: () -> Unit,
    onGainChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onTogglePolarity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Piste ${track.id.name}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = track.displayName.ifEmpty { "aucun fichier" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onLoad) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Charger un fichier")
                }
            }

            if (track.isLoaded) {
                Text(
                    text = "%d Hz · %d canaux · %s".format(
                        track.sampleRate,
                        track.pcm?.channels ?: 0,
                        formatDuration(track.frameCount, track.sampleRate),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%+.1f dB".format(track.gainDb),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Slider(
                    value = track.gainDb,
                    onValueChange = onGainChange,
                    valueRange = -60f..12f,
                    enabled = track.isLoaded,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = track.muted,
                    onClick = onToggleMute,
                    label = { Text("Mute") },
                    enabled = track.isLoaded,
                )
                FilterChip(
                    selected = track.soloed,
                    onClick = onToggleSolo,
                    label = { Text("Solo") },
                    enabled = track.isLoaded,
                )
                FilterChip(
                    selected = track.polarityInverted,
                    onClick = onTogglePolarity,
                    label = { Text("Ø") },
                    leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                    enabled = track.isLoaded,
                )
            }
        }
    }
}

internal fun formatDuration(frames: Long, sampleRate: Int): String {
    val totalSeconds = frames / sampleRate
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
