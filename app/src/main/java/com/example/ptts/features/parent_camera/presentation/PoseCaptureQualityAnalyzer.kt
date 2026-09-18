package com.example.ptts.features.parent_camera.presentation

import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.PoseFrame
import kotlin.math.abs

class PoseCaptureQualityAnalyzer {
    private var previousBounds: PoseBounds? = null
    private var consecutiveShakyFrames = 0
    private var stableIssue: CaptureQualityIssue? = null
    private var pendingIssue: CaptureQualityIssue? = null
    private var pendingIssueSinceMs: Long? = null
    private var pendingIssueFrames = 0
    private var previousTimestampMs: Long? = null
    private val frameIntervals = ArrayDeque<Long>()
    private var medianFrameMs = DefaultFrameIntervalMs

    fun reset() {
        previousBounds = null
        consecutiveShakyFrames = 0
        stableIssue = null
        pendingIssue = null
        pendingIssueSinceMs = null
        pendingIssueFrames = 0
        previousTimestampMs = null
        frameIntervals.clear()
        medianFrameMs = DefaultFrameIntervalMs
    }

    fun analyze(frame: PoseFrame): CaptureQualityState {
        observeFrameInterval(frame.timestampMs)
        if (frame.landmarks.isEmpty()) {
            previousBounds = null
            return stabilizedState(score = 0, rawIssue = CaptureQualityIssue.NoPose, timestampMs = frame.timestampMs)
        }

        // Bounds used for framing feedback must be made from reliable points. A low-
        // confidence point near the edge is not evidence that the child is actually clipped.
        val bounds = frame.boundsOrNull(MinBoundsConfidence)
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

        if (requiredPoints.size < MinPresentRequiredPoints) {
            score -= 34
            issue = CaptureQualityIssue.PartialBody
        }
        if (bounds == null && requiredPoints.size >= MinPresentRequiredPoints) {
            score -= 36
            issue = if (averageConfidence < MinAverageConfidence) {
                CaptureQualityIssue.LowLightOrBlur
            } else if (requiredPoints.size < MinVisibleRequiredPoints) {
                CaptureQualityIssue.PartialBody
            } else {
                CaptureQualityIssue.UnreliablePose
            }
        }
        if (visibleRequiredPoints < MinVisibleRequiredPoints && requiredPoints.size >= MinPresentRequiredPoints) {
            score -= 24
            issue = if (averageConfidence < MinAverageConfidence) {
                CaptureQualityIssue.LowLightOrBlur
            } else if (requiredPoints.size < MinVisibleRequiredPoints) {
                CaptureQualityIssue.PartialBody
            } else {
                CaptureQualityIssue.UnreliablePose
            }
        }
        if (bounds != null && bounds.height < MinPersonHeight) {
            score -= 30
            issue = CaptureQualityIssue.TooFar
        }
        if (bounds != null && bounds.isNearFrameEdge()) {
            score -= 26
            issue = CaptureQualityIssue.EdgeClipped
        }
        if (requiredPoints.size >= MinPresentRequiredPoints && averageConfidence < MinAverageConfidence) {
            score -= 28
            issue = CaptureQualityIssue.LowLightOrBlur
        }
        if (bounds?.isLikelyShakyComparedTo(previousBounds) == true) {
            consecutiveShakyFrames += 1
        } else {
            consecutiveShakyFrames = 0
        }
        if (consecutiveShakyFrames >= MinConsecutiveShakyFrames) {
            score -= 24
            issue = CaptureQualityIssue.Shaky
        }

        previousBounds = bounds
        return stabilizedState(
            score = score.coerceIn(0, 100),
            rawIssue = issue,
            timestampMs = frame.timestampMs,
        )
    }

    private fun stabilizedState(
        score: Int,
        rawIssue: CaptureQualityIssue,
        timestampMs: Long,
    ): CaptureQualityState {
        val current = stableIssue
        if (current == null) {
            stableIssue = rawIssue
            pendingIssue = null
            pendingIssueSinceMs = null
            pendingIssueFrames = 0
            return CaptureQualityState(score = score, issue = rawIssue)
        }
        if (rawIssue == current) {
            pendingIssue = null
            pendingIssueSinceMs = null
            pendingIssueFrames = 0
            return CaptureQualityState(score = score, issue = current)
        }

        // Shaky already requires consecutive frame evidence above, so it can be surfaced
        // immediately. Other transitions need a short observed-frame window to avoid flashing
        // an out-of-frame/low-light instruction on a single bad ML Kit result.
        if (rawIssue == CaptureQualityIssue.Shaky) {
            stableIssue = rawIssue
            pendingIssue = null
            pendingIssueSinceMs = null
            pendingIssueFrames = 0
            return CaptureQualityState(score = score, issue = rawIssue)
        }
        if (pendingIssue != rawIssue) {
            pendingIssue = rawIssue
            pendingIssueSinceMs = timestampMs
            pendingIssueFrames = 1
        } else {
            pendingIssueFrames += 1
        }
        val elapsedMs = timestampMs - (pendingIssueSinceMs ?: timestampMs)
        val debounceMs = maxOf(MinIssueDebounceMs, medianFrameMs * IssueDebounceFrames)
        if (pendingIssueFrames >= IssueDebounceFrames || elapsedMs >= debounceMs) {
            stableIssue = rawIssue
            pendingIssue = null
            pendingIssueSinceMs = null
            pendingIssueFrames = 0
        }
        return CaptureQualityState(score = score, issue = stableIssue ?: rawIssue)
    }

    private fun observeFrameInterval(timestampMs: Long) {
        val previous = previousTimestampMs
        previousTimestampMs = timestampMs
        if (previous == null) return
        val interval = timestampMs - previous
        if (interval <= 0L || interval > MaxTrackedFrameIntervalMs) return
        frameIntervals.addLast(interval)
        if (frameIntervals.size > FrameIntervalWindow) frameIntervals.removeFirst()
        val sorted = frameIntervals.toList().sorted()
        medianFrameMs = sorted[sorted.size / 2]
    }

    private fun PoseFrame.boundsOrNull(minConfidence: Float): PoseBounds? {
        val visiblePoints = landmarks.values.filter { it.confidence >= minConfidence }
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
        const val MinBoundsConfidence = 0.40f
        const val MinRequiredConfidence = 0.40f
        const val MinAverageConfidence = 0.55f
        const val MinBoundsPoints = 5
        const val MinVisibleRequiredPoints = 6
        const val MinPresentRequiredPoints = 4
        const val MinPersonHeight = 0.33f
        const val EdgeMargin = 0.04f
        const val MaxNormalizedHorizontalShake = 0.095f
        const val MaxNormalizedScaleShake = 0.18f
        const val MinConsecutiveShakyFrames = 2
        const val DefaultFrameIntervalMs = 33L
        const val FrameIntervalWindow = 12
        const val MaxTrackedFrameIntervalMs = 2000L
        const val IssueDebounceFrames = 3
        const val MinIssueDebounceMs = 120L

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
