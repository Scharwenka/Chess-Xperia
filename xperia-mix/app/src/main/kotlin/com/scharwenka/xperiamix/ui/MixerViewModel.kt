package com.scharwenka.xperiamix.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scharwenka.xperiamix.align.TrackAligner
import com.scharwenka.xperiamix.audio.AudioEngine
import com.scharwenka.xperiamix.decode.PcmDecoder
import com.scharwenka.xperiamix.export.MixdownExporter
import com.scharwenka.xperiamix.model.TaskProgress
import com.scharwenka.xperiamix.model.TrackId
import com.scharwenka.xperiamix.model.TrackState
import com.scharwenka.xperiamix.waveform.WaveformPyramid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Etat de session et orchestration.
 *
 * Regle de placement : le decodage, la construction des pyramides, l'analyse de
 * correlation et l'export vivent dans `viewModelScope` sur des dispatchers de
 * calcul ; le moteur natif, lui, n'est jamais pilote par des coroutines - ses
 * commandes sont de simples ecritures atomiques, appelables depuis le thread
 * principal pendant un geste sans risque de blocage.
 *
 * Convention d'offsets : les deux pistes portent un offset positif ou nul, et
 * la session est renormalisee apres chaque modification pour que le plus petit
 * des deux vaille zero. Reculer la piste B revient donc a avancer la piste A.
 * Le moteur n'a ainsi jamais a gerer de temps negatifs, et la timeline commence
 * toujours au premier echantillon audible.
 */
class MixerViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = AudioEngine(application)
    private val decoder = PcmDecoder(application)
    private val exporter = MixdownExporter(application, engine)

    private val _uiState = MutableStateFlow(
        MixerUiState(
            sampleRate = engine.sampleRate,
            outputName = engine.profile.outputName,
        )
    )
    val uiState: StateFlow<MixerUiState> = _uiState.asStateFlow()

    private var alignJob: Job? = null

    // ------------------------------------------------------------------ pistes

    fun loadTrack(id: TrackId, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(progress = TaskProgress.Running("Decodage", 0f)) }
            runCatching {
                val decoded = decoder.decode(uri, engine.sampleRate) { fraction ->
                    _uiState.update { it.copy(progress = TaskProgress.Running("Decodage", fraction * 0.7f)) }
                }
                val pyramid = WaveformPyramid.build(decoded.pcm) { fraction ->
                    _uiState.update {
                        it.copy(progress = TaskProgress.Running("Forme d'onde", 0.7f + fraction * 0.3f))
                    }
                }
                decoded to pyramid
            }.onSuccess { (decoded, pyramid) ->
                engine.setTrack(id, decoded.pcm)
                _uiState.update { state ->
                    val track = state.track(id).copy(
                        uri = uri,
                        displayName = decoded.displayName,
                        pcm = decoded.pcm,
                        waveform = pyramid,
                    )
                    state.withTrack(track).copy(progress = TaskProgress.Idle)
                }
                syncEngine()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(progress = TaskProgress.Failed("Chargement", error.message ?: "echec"))
                }
            }
        }
    }

    fun setGainDb(id: TrackId, gainDb: Float) {
        _uiState.update { it.withTrack(it.track(id).copy(gainDb = gainDb)) }
        syncEngine()
    }

    fun toggleMute(id: TrackId) {
        _uiState.update { it.withTrack(it.track(id).let { t -> t.copy(muted = !t.muted) }) }
        syncEngine()
    }

    fun toggleSolo(id: TrackId) {
        _uiState.update { it.withTrack(it.track(id).let { t -> t.copy(soloed = !t.soloed) }) }
        syncEngine()
    }

    fun togglePolarity(id: TrackId) {
        _uiState.update {
            it.withTrack(it.track(id).let { t -> t.copy(polarityInverted = !t.polarityInverted) })
        }
        syncEngine()
    }

    // ------------------------------------------------------------------ calage

    /** Deplace la piste B de [deltaFrames] par rapport a la piste A. */
    fun slipBy(deltaFrames: Long) {
        setRelativeOffset(_uiState.value.relativeOffsetFrames + deltaFrames)
    }

    fun setRelativeOffset(frames: Long) {
        _uiState.update { state ->
            // B porte le decalage ; la renormalisation le reporte sur A s'il est negatif.
            val normalized = normalize(state.trackA.offsetFrames, state.trackA.offsetFrames + frames)
            state.copy(
                trackA = state.trackA.copy(offsetFrames = normalized.first),
                trackB = state.trackB.copy(offsetFrames = normalized.second),
            )
        }
        syncEngine()
    }

    fun autoAlign() {
        val state = _uiState.value
        val a = state.trackA.pcm ?: return
        val b = state.trackB.pcm ?: return
        alignJob?.cancel()
        alignJob = viewModelScope.launch {
            _uiState.update { it.copy(progress = TaskProgress.Running("Analyse", 0f), alignment = null) }
            runCatching {
                TrackAligner.align(a, b) { label, fraction ->
                    _uiState.update { it.copy(progress = TaskProgress.Running(label, fraction)) }
                }
            }.onSuccess { result ->
                _uiState.update { it.copy(alignment = result, progress = TaskProgress.Idle) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(progress = TaskProgress.Failed("Analyse", error.message ?: "echec"))
                }
            }
        }
    }

    fun applyAlignment() {
        val result = _uiState.value.alignment ?: return
        setRelativeOffset(_uiState.value.relativeOffsetFrames + result.lagFrames)
        if (result.polarityInverted) togglePolarity(TrackId.B)
        dismissAlignment()
    }

    fun dismissAlignment() {
        _uiState.update { it.copy(alignment = null) }
    }

    // --------------------------------------------------------------- transport

    fun play() {
        engine.play()
        _uiState.update { it.copy(isPlaying = true) }
    }

    fun pause() {
        engine.pause()
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun togglePlayPause() = if (_uiState.value.isPlaying) pause() else play()

    fun seekTo(frame: Long) = engine.seekFrames(frame.coerceAtLeast(0))

    fun playheadFrames(): Long = engine.playheadFrames()

    fun refreshEngineInfo() {
        _uiState.update {
            it.copy(
                latencyMillis = engine.latencyMillis(),
                timelineFrames = engine.timelineFrames(),
            )
        }
    }

    // ------------------------------------------------------------------ export

    fun exportTo(destination: Uri, format: MixdownExporter.Format) {
        viewModelScope.launch {
            _uiState.update { it.copy(progress = TaskProgress.Running("Rendu", 0f)) }
            runCatching {
                exporter.export(
                    destination = destination,
                    totalFrames = engine.timelineFrames(),
                    options = MixdownExporter.Options(format = format),
                ) { fraction ->
                    _uiState.update { it.copy(progress = TaskProgress.Running("Rendu", fraction)) }
                }
            }.onSuccess {
                _uiState.update { it.copy(progress = TaskProgress.Idle) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(progress = TaskProgress.Failed("Export", error.message ?: "echec"))
                }
            }
        }
    }

    // ------------------------------------------------------------------ vue

    fun setLaneLayout(layout: LaneLayout) = _uiState.update { it.copy(laneLayout = layout) }

    fun setSlipEnabled(enabled: Boolean) = _uiState.update { it.copy(slipEnabled = enabled) }

    // ------------------------------------------------------------------ interne

    /** Pousse l'etat complet vers le moteur : quelques ecritures atomiques. */
    private fun syncEngine() {
        val state = _uiState.value
        val anySolo = state.trackA.soloed || state.trackB.soloed
        for (track in listOf(state.trackA, state.trackB)) {
            engine.setGain(track.id, track.linearGain(anySolo))
            engine.setMuted(track.id, !track.isLoaded)
            engine.setInverted(track.id, track.polarityInverted)
            engine.setOffsetFrames(track.id, track.offsetFrames)
        }
        refreshEngineInfo()
    }

    private fun normalize(offsetA: Long, offsetB: Long): Pair<Long, Long> {
        val shift = minOf(offsetA, offsetB)
        return (offsetA - shift) to (offsetB - shift)
    }

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }
}

private fun MixerUiState.withTrack(track: TrackState): MixerUiState =
    if (track.id == TrackId.A) copy(trackA = track) else copy(trackB = track)
