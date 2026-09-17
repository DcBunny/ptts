package com.example.ptts.features.parent_camera.domain

data class PoseFrame(
    val timestampMs: Long,
    val landmarks: Map<BodyLandmark, PosePoint>,
    /** Cumulative camera translation in normalized image coordinates. */
    val cameraMotion: CameraMotion = CameraMotion(),
)

data class PosePoint(
    val x: Float,
    val y: Float,
    val confidence: Float,
)

data class CameraMotion(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val available: Boolean = false,
    val reliable: Boolean = false,
    val magnitude: Float = 0f,
)

enum class BodyLandmark {
    LeftShoulder,
    RightShoulder,
    LeftHip,
    RightHip,
    LeftKnee,
    RightKnee,
    LeftAnkle,
    RightAnkle,
    LeftHeel,
    RightHeel,
}

enum class JumpPhase {
    Searching,
    Grounded,
    Rising,
    Airborne,
    Landing,
}

enum class TrackingQuality {
    NoPose,
    PartialBody,
    Tracking,
}

data class JumpCounterResult(
    val count: Int,
    val phase: JumpPhase,
    val trackingQuality: TrackingQuality,
    val countedThisFrame: Boolean,
    val confirmedCount: Int = count,
    val estimatedCount: Int = 0,
    val estimatedThisFrame: Boolean = false,
    val recovering: Boolean = false,
)

data class JumpDiagnostic(
    val timestampMs: Long,
    val phase: JumpPhase,
    val count: Int,
    val confirmedCount: Int,
    val estimatedCount: Int,
    val recovering: Boolean,
    val event: String,
    val sampleIntervalMs: Long? = null,
    val rawLift: Float? = null,
    val smoothedLift: Float? = null,
    val peakLift: Float? = null,
    val jumpDurationMs: Long? = null,
    val rejectionReason: String? = null,
    val recoveryStage: String? = null,
    val recoveryBaselineBodyY: Float? = null,
    val recoveryBaselineFootY: Float? = null,
    val recoveryScale: Float? = null,
    val recoveryCycleMs: Long? = null,
    val recoveryValidPeakThreshold: Float? = null,
    val recoveryMatchedEstimate: Boolean = false,
    /** Median observed pose-frame interval, the basis for every adaptive phase window. */
    val medianFrameIntervalMs: Long? = null,
    /** 90th percentile interval: what the counter treats as a normal worst-case gap. */
    val typicalWorstFrameIntervalMs: Long? = null,
    /** Learned typical jump amplitude; null until real jumps have been observed. */
    val adaptivePeakLift: Float? = null,
)
