package com.example.ptts.features.parent_camera.domain

data class PoseFrame(
    val timestampMs: Long,
    val landmarks: Map<BodyLandmark, PosePoint>,
)

data class PosePoint(
    val x: Float,
    val y: Float,
    val confidence: Float,
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
)
