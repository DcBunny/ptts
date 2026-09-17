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
)
