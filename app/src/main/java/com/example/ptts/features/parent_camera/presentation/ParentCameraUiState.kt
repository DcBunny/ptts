package com.example.ptts.features.parent_camera.presentation

import com.example.ptts.features.jump_session.presentation.JumpSessionDefaults
import com.example.ptts.features.parent_camera.domain.JumpPhase
import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.TrackingQuality

enum class ParentCameraStage {
    Framing,
    Countdown,
    Recording,
    Summary,
}

data class ParentCameraUiState(
    val stage: ParentCameraStage = ParentCameraStage.Framing,
    val durationSeconds: Int = JumpSessionDefaults.InitialDurationSeconds,
    val countdownValue: Int? = null,
    val remainingSeconds: Int = durationSeconds,
    val jumpCount: Int = 0,
    val bestRecord: Int = 0,
    val trackingQuality: TrackingQuality = TrackingQuality.NoPose,
    val jumpPhase: JumpPhase = JumpPhase.Searching,
    val guidanceText: String = "",
    val analysisFps: Float = 0f,
    val inferenceMs: Long = 0L,
    val poseOverlay: PoseOverlay = PoseOverlay(),
    val errorState: ParentCameraError? = null,
    val cameraPermissionGranted: Boolean = false,
    val isCameraReady: Boolean = false,
    val videoFile: java.io.File? = null,
    val isFinalizingVideo: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
)

enum class ParentCameraError {
    PermissionDenied,
    NoBackCamera,
    CameraUnavailable,
}

data class PoseOverlay(
    val points: List<PoseOverlayPoint> = emptyList(),
)

data class PoseOverlayPoint(
    val landmark: BodyLandmark,
    val x: Float,
    val y: Float,
)
