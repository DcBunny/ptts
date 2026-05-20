package com.example.ptts.features.parent_camera.presentation

import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.PoseFrame
import kotlin.math.abs

class PoseCaptureQualityAnalyzer {
    private var previousBounds: PoseBounds? = null
    private var consecutiveShakyFrames = 0

    fun reset() {
        previousBounds = null
        consecutiveShakyFrames = 0
    }

    fun analyze(frame: PoseFrame): CaptureQualityState {
        if (frame.landmarks.isEmpty()) {
            previousBounds = null
            return CaptureQualityState(score = 0, issue = CaptureQualityIssue.NoPose)
        }

        val bounds = frame.boundsOrNull()
            ?: return CaptureQualityState(score = 20, issue = CaptureQualityIssue.PartialBody)
        val requiredPoints = RequiredLandmarks.mapNotNull { frame.landmarks[it] }
        val visibleRequiredPoints = requiredPoints.count { it.confidence >= MinRequiredConfidence }
        val averageConfidence = requiredPoints
            .takeIf { it.isNotEmpty() }
            ?.map { it.confidence }
            ?.average()
            ?.toFloat()
            ?: 0f

        var score = 100
        var issue = CaptureQualityIssue.Good

        if (visibleRequiredPoints < MinVisibleRequiredPoints) {
            score -= 34
            issue = CaptureQualityIssue.PartialBody
        }
        if (bounds.height < MinPersonHeight) {
            score -= 30
            issue = CaptureQualityIssue.TooFar
        }
        if (bounds.isNearFrameEdge()) {
            score -= 26
            issue = CaptureQualityIssue.EdgeClipped
        }
        if (averageConfidence < MinAverageConfidence) {
            score -= 28
            issue = CaptureQualityIssue.LowLightOrBlur
        }
        if (bounds.isLikelyShakyComparedTo(previousBounds)) {
            consecutiveShakyFrames += 1
        } else {
            consecutiveShakyFrames = 0
        }
        if (consecutiveShakyFrames >= MinConsecutiveShakyFrames) {
            score -= 24
            issue = CaptureQualityIssue.Shaky
        }

        previousBounds = bounds
        return CaptureQualityState(
            score = score.coerceIn(0, 100),
            issue = issue,
        )
    }

    private fun PoseFrame.boundsOrNull(): PoseBounds? {
        val visiblePoints = landmarks.values.filter { it.confidence >= MinBoundsConfidence }
        if (visiblePoints.size < MinBoundsPoints) {
            return null
        }
        val minX = visiblePoints.minOf { it.x }
        val maxX = visiblePoints.maxOf { it.x }
        val minY = visiblePoints.minOf { it.y }
        val maxY = visiblePoints.maxOf { it.y }
        return PoseBounds(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            centerX = (minX + maxX) / 2f,
            height = maxY - minY,
        )
    }

    private fun PoseBounds.isLikelyShakyComparedTo(previous: PoseBounds?): Boolean {
        if (previous == null || previous.height <= 0f) {
            return false
        }
        val normalizedHorizontalShift = abs(centerX - previous.centerX) / previous.height
        val normalizedScaleChange = abs(height - previous.height) / previous.height
        return normalizedHorizontalShift >= MaxNormalizedHorizontalShake ||
            normalizedScaleChange >= MaxNormalizedScaleShake
    }

    private fun PoseBounds.isNearFrameEdge(): Boolean {
        return minX <= EdgeMargin ||
            maxX >= 1f - EdgeMargin ||
            minY <= EdgeMargin ||
            maxY >= 1f - EdgeMargin
    }

    private data class PoseBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val centerX: Float,
        val height: Float,
    )

    private companion object {
        const val MinBoundsConfidence = 0.25f
        const val MinRequiredConfidence = 0.40f
        const val MinAverageConfidence = 0.55f
        const val MinBoundsPoints = 5
        const val MinVisibleRequiredPoints = 6
        const val MinPersonHeight = 0.33f
        const val EdgeMargin = 0.04f
        const val MaxNormalizedHorizontalShake = 0.095f
        const val MaxNormalizedScaleShake = 0.18f
        const val MinConsecutiveShakyFrames = 2

        val RequiredLandmarks = listOf(
            BodyLandmark.LeftShoulder,
            BodyLandmark.RightShoulder,
            BodyLandmark.LeftHip,
            BodyLandmark.RightHip,
            BodyLandmark.LeftAnkle,
            BodyLandmark.RightAnkle,
        )
    }
}
