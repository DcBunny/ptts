package com.example.ptts.features.parent_camera.presentation

import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.PoseFrame
import com.example.ptts.features.parent_camera.domain.PosePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PoseCaptureQualityAnalyzerTest {
    @Test
    fun emptyPose_reportsNoPose() {
        val result = PoseCaptureQualityAnalyzer().analyze(PoseFrame(timestampMs = 0L, landmarks = emptyMap()))

        assertEquals(CaptureQualityIssue.NoPose, result.issue)
        assertEquals(0, result.score)
    }

    @Test
    fun smallBodyInFrame_reportsTooFar() {
        val result = PoseCaptureQualityAnalyzer().analyze(frame(bodyScale = 0.45f))

        assertEquals(CaptureQualityIssue.TooFar, result.issue)
    }

    @Test
    fun lowConfidencePose_reportsLowLightOrBlur() {
        val result = PoseCaptureQualityAnalyzer().analyze(frame(confidence = 0.45f))

        assertEquals(CaptureQualityIssue.LowLightOrBlur, result.issue)
    }

    @Test
    fun poseNearFrameEdge_reportsEdgeClipped() {
        val result = PoseCaptureQualityAnalyzer().analyze(frame(centerY = 0.29f))

        assertEquals(CaptureQualityIssue.EdgeClipped, result.issue)
    }

    @Test
    fun suddenHorizontalShift_reportsShaky() {
        val analyzer = PoseCaptureQualityAnalyzer()
        analyzer.analyze(frame(centerX = 0.50f))

        val result = analyzer.analyze(frame(centerX = 0.56f))

        assertEquals(CaptureQualityIssue.Shaky, result.issue)
    }

    @Test
    fun fullStablePose_reportsGood() {
        val result = PoseCaptureQualityAnalyzer().analyze(frame())

        assertEquals(CaptureQualityIssue.Good, result.issue)
        assertEquals(100, result.score)
    }

    private fun frame(
        centerX: Float = 0.50f,
        centerY: Float = 0.59f,
        bodyScale: Float = 1f,
        confidence: Float = 0.95f,
    ): PoseFrame {
        val shoulderY = 0.28f
        val hipY = 0.55f
        val kneeY = 0.72f
        val footY = 0.90f
        val scaled = mapOf(
            BodyLandmark.LeftShoulder to point(centerX - 0.08f, scaleY(shoulderY, centerY, bodyScale), confidence),
            BodyLandmark.RightShoulder to point(centerX + 0.08f, scaleY(shoulderY, centerY, bodyScale), confidence),
            BodyLandmark.LeftHip to point(centerX - 0.06f, scaleY(hipY, centerY, bodyScale), confidence),
            BodyLandmark.RightHip to point(centerX + 0.06f, scaleY(hipY, centerY, bodyScale), confidence),
            BodyLandmark.LeftKnee to point(centerX - 0.05f, scaleY(kneeY, centerY, bodyScale), confidence),
            BodyLandmark.RightKnee to point(centerX + 0.05f, scaleY(kneeY, centerY, bodyScale), confidence),
            BodyLandmark.LeftAnkle to point(centerX - 0.04f, scaleY(footY, centerY, bodyScale), confidence),
            BodyLandmark.RightAnkle to point(centerX + 0.04f, scaleY(footY, centerY, bodyScale), confidence),
        )
        return PoseFrame(timestampMs = 0L, landmarks = scaled)
    }

    private fun scaleY(
        y: Float,
        centerY: Float,
        bodyScale: Float,
    ): Float {
        return centerY + (y - BaseCenterY) * bodyScale
    }

    private fun point(
        x: Float,
        y: Float,
        confidence: Float,
    ) = PosePoint(x = x, y = y, confidence = confidence)

    private companion object {
        const val BaseCenterY = 0.59f
    }
}
