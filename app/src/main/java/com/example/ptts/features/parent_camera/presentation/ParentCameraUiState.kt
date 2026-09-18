package com.example.ptts.features.parent_camera.presentation

import com.example.ptts.features.jump_session.presentation.JumpSessionDefaults
import com.example.ptts.features.parent_camera.domain.JumpPhase
import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.TrackingQuality
import com.example.ptts.features.parent_camera.domain.AutoLowLightState

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
    val confirmedJumpCount: Int = 0,
    val estimatedJumpCount: Int = 0,
    val bestRecord: Int = 0,
    val trackingQuality: TrackingQuality = TrackingQuality.NoPose,
    val captureQuality: CaptureQualityState = CaptureQualityState(),
    val jumpPhase: JumpPhase = JumpPhase.Searching,
    val isRecovering: Boolean = false,
    val isCalibrating: Boolean = false,
    val autoLowLightState: AutoLowLightState = AutoLowLightState.Normal,
    val autoLowLightLevel: Int = 0,
    val autoLowLightReason: String = "",
    val guidanceText: String = "",
    val analysisFps: Float = 0f,
    val inferenceMs: Long = 0L,
    val poseOverlay: PoseOverlay = PoseOverlay(),
    /** Aspect ratio of the analysis image the overlay landmarks live in. */
    val analysisAspectRatio: Float = AnalysisAspectRatioDefault,
    val errorState: ParentCameraError? = null,
    val cameraPermissionGranted: Boolean = false,
    val isCameraReady: Boolean = false,
    val videoFile: java.io.File? = null,
    val isFinalizingVideo: Boolean = false,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
)

data class CaptureQualityState(
    val score: Int = 0,
    val issue: CaptureQualityIssue = CaptureQualityIssue.NoPose,
)

enum class CaptureQualityIssue {
    Good,
    NoPose,
    UnreliablePose,
    PartialBody,
    TooFar,
    EdgeClipped,
    LowLightOrBlur,
    Shaky,
}

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
    val confidence: Float = 1f,
)

/** 4:3 upright analysis frames are the previous default and a safe fallback. */
const val AnalysisAspectRatioDefault = 4f / 3f
