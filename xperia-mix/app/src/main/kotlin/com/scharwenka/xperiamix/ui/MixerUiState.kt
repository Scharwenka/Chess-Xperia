package com.scharwenka.xperiamix.ui

import com.scharwenka.xperiamix.model.AlignmentResult
import com.scharwenka.xperiamix.model.TaskProgress
import com.scharwenka.xperiamix.model.TrackId
import com.scharwenka.xperiamix.model.TrackState

data class MixerUiState(
    val trackA: TrackState = TrackState(TrackId.A),
    val trackB: TrackState = TrackState(TrackId.B),
    val isPlaying: Boolean = false,
    val laneLayout: LaneLayout = LaneLayout.STACKED,
    val slipEnabled: Boolean = false,
    val progress: TaskProgress = TaskProgress.Idle,
    val alignment: AlignmentResult? = null,
    val sampleRate: Int = 48_000,
    val latencyMillis: Double = 0.0,
    val outputName: String = "",
    val timelineFrames: Long = 0,
) {
    val bothLoaded: Boolean get() = trackA.isLoaded && trackB.isLoaded

    /** Decalage percu par l'utilisateur : B relativement a A. */
    val relativeOffsetFrames: Long get() = trackB.offsetFrames - trackA.offsetFrames

    fun track(id: TrackId): TrackState = if (id == TrackId.A) trackA else trackB
}
