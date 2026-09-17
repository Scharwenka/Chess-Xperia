package com.scharwenka.xperiamix.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwipeLeft
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scharwenka.xperiamix.export.MixdownExporter
import com.scharwenka.xperiamix.model.TaskProgress
import com.scharwenka.xperiamix.model.TrackId

/**
 * Ecran principal.
 *
 * Le format 21:9 du Xperia 1 V est traite comme un atout : en paysage, 3840
 * pixels de large affichent environ 40 secondes de programme a une resolution
 * d'une milliseconde par pixel, ce qui rend le calage manuel realiste au doigt.
 * La colonne de commandes est donc fixee a droite plutot que superposee, et la
 * timeline recupere toute la largeur restante.
 */
@Composable
fun MixerScreen(viewModel: MixerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeline = remember { TimelineState() }

    val openA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persist(context, it)
            viewModel.loadTrack(TrackId.A, it)
        }
    }
    val openB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persist(context, it)
            viewModel.loadTrack(TrackId.B, it)
        }
    }

    // Synchronisation du curseur sur la cadence d'affichage : `withFrameNanos`
    // livre une valeur par trame de composition, soit 120 fois par seconde sur
    // cette dalle, sans timer independant qui reveillerait le CPU pour rien.
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            withFrameNanos { }
            timeline.playheadFrame = viewModel.playheadFrames()
        }
    }
    LaunchedEffect(state.timelineFrames) {
        timeline.totalFrames = state.timelineFrames
    }

    Scaffold { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).padding(8.dp)
        ) {
            val wide = maxWidth > maxHeight

            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TransportBar(state, viewModel, timeline)
                        TimelinePane(state, viewModel, timeline, Modifier.weight(1f))
                    }
                    ControlColumn(
                        state = state,
                        viewModel = viewModel,
                        onLoadA = { openA.launch(AUDIO_MIMES) },
                        onLoadB = { openB.launch(AUDIO_MIMES) },
                        modifier = Modifier.width(360.dp).fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransportBar(state, viewModel, timeline)
                    TimelinePane(state, viewModel, timeline, Modifier.weight(1f))
                    ControlColumn(
                        state = state,
                        viewModel = viewModel,
                        onLoadA = { openA.launch(AUDIO_MIMES) },
                        onLoadB = { openB.launch(AUDIO_MIMES) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }

            (state.progress as? TaskProgress.Running)?.let { running ->
                ProgressOverlay(running, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun TimelinePane(
    state: MixerUiState,
    viewModel: MixerViewModel,
    timeline: TimelineState,
    modifier: Modifier = Modifier,
) {
    WaveformTimeline(
        state = timeline,
        trackA = state.trackA,
        trackB = state.trackB,
        layout = state.laneLayout,
        slipEnabled = state.slipEnabled,
        onSeek = { frame ->
            timeline.playheadFrame = frame
            viewModel.seekTo(frame)
        },
        onSlip = { delta -> viewModel.slipBy(delta) },
        onSlipFinished = { viewModel.refreshEngineInfo() },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun TransportBar(
    state: MixerUiState,
    viewModel: MixerViewModel,
    timeline: TimelineState,
) {
    var exportMenu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = { viewModel.togglePlayPause() }, enabled = state.bothLoaded) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Lecture",
            )
        }
        IconButton(onClick = {
            viewModel.pause()
            viewModel.seekTo(0)
            timeline.playheadFrame = 0
        }) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop")
        }

        Text(
            text = formatTimecode(timeline.playheadFrame, state.sampleRate, stepMs = 1),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        FilledIconToggleButton(
            checked = state.slipEnabled,
            onCheckedChange = { viewModel.setSlipEnabled(it) },
        ) {
            Icon(Icons.Filled.SwipeLeft, contentDescription = "Mode calage")
        }
        IconButton(onClick = {
            viewModel.setLaneLayout(
                if (state.laneLayout == LaneLayout.STACKED) LaneLayout.OVERLAID else LaneLayout.STACKED
            )
        }) {
            Icon(Icons.Filled.Layers, contentDescription = "Superposer / separer")
        }
        IconButton(onClick = { timeline.fitToWidth() }) {
            Icon(Icons.Filled.OpenInFull, contentDescription = "Vue d'ensemble")
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = "%s · %d Hz · %.1f ms".format(
                state.outputName, state.sampleRate, state.latencyMillis
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box {
            IconButton(onClick = { exportMenu = true }, enabled = state.bothLoaded) {
                Icon(Icons.Filled.IosShare, contentDescription = "Exporter")
            }
            ExportMenu(
                expanded = exportMenu,
                onDismiss = { exportMenu = false },
                onExport = { format, uri -> viewModel.exportTo(uri, format) },
            )
        }
    }
}

@Composable
private fun ExportMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onExport: (MixdownExporter.Format, android.net.Uri) -> Unit,
) {
    var pending by remember { mutableStateOf(MixdownExporter.Format.WAV_24) }
    // Le contrat d'un launcher est fige a la premiere composition : on declare
    // donc un type MIME generique et c'est l'extension proposee qui distingue
    // WAV et FLAC dans le selecteur de destination.
    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/*")
    ) { uri -> uri?.let { onExport(pending, it) } }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MixdownExporter.Format.entries.forEach { format ->
            DropdownMenuItem(
                text = { Text(exportLabel(format)) },
                onClick = {
                    pending = format
                    onDismiss()
                    create.launch("mixage.${format.extension}")
                },
            )
        }
    }
}

private fun exportLabel(format: MixdownExporter.Format): String = when (format) {
    MixdownExporter.Format.WAV_16 -> "WAV 16 bits (dither)"
    MixdownExporter.Format.WAV_24 -> "WAV 24 bits"
    MixdownExporter.Format.WAV_FLOAT32 -> "WAV 32 bits flottant"
    MixdownExporter.Format.FLAC -> "FLAC"
}

@Composable
private fun ControlColumn(
    state: MixerUiState,
    viewModel: MixerViewModel,
    onLoadA: () -> Unit,
    onLoadB: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TrackStrip(
            track = state.trackA,
            onLoad = onLoadA,
            onGainChange = { viewModel.setGainDb(TrackId.A, it) },
            onToggleMute = { viewModel.toggleMute(TrackId.A) },
            onToggleSolo = { viewModel.toggleSolo(TrackId.A) },
            onTogglePolarity = { viewModel.togglePolarity(TrackId.A) },
            modifier = Modifier.fillMaxWidth(),
        )
        TrackStrip(
            track = state.trackB,
            onLoad = onLoadB,
            onGainChange = { viewModel.setGainDb(TrackId.B, it) },
            onToggleMute = { viewModel.toggleMute(TrackId.B) },
            onToggleSolo = { viewModel.toggleSolo(TrackId.B) },
            onTogglePolarity = { viewModel.togglePolarity(TrackId.B) },
            modifier = Modifier.fillMaxWidth(),
        )
        OffsetControl(
            offsetFrames = state.relativeOffsetFrames,
            sampleRate = state.sampleRate,
            onOffsetChange = { viewModel.setRelativeOffset(it) },
            onAutoAlign = { viewModel.autoAlign() },
            autoAlignEnabled = state.bothLoaded,
            modifier = Modifier.fillMaxWidth(),
        )
        state.alignment?.let { result ->
            AlignmentBanner(
                result = result,
                onApply = { viewModel.applyAlignment() },
                onDismiss = { viewModel.dismissAlignment() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        (state.progress as? TaskProgress.Failed)?.let { failure ->
            Text(
                "${failure.label} : ${failure.cause}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProgressOverlay(running: TaskProgress.Running, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Text(running.label, style = MaterialTheme.typography.labelMedium)
        val fraction = running.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private fun persist(context: android.content.Context, uri: android.net.Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

private val AUDIO_MIMES = arrayOf(
    "audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/x-flac",
    "audio/aac", "audio/mp4", "audio/ogg", "application/ogg", "audio/*",
)
