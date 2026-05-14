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
        bodyScale: Float = 1f,
        confidence: Float = 0.95f,
    ): PoseFrame {
        val shoulderY = 0.28f
        val hipY = 0.55f
        val kneeY = 0.72f
        val footY = 0.90f
        val scaled = mapOf(
            BodyLandmark.LeftShoulder to point(centerX - 0.08f, scaleY(shoulderY, bodyScale), confidence),
            BodyLandmark.RightShoulder to point(centerX + 0.08f, scaleY(shoulderY, bodyScale), confidence),
            BodyLandmark.LeftHip to point(centerX - 0.06f, scaleY(hipY, bodyScale), confidence),
            BodyLandmark.RightHip to point(centerX + 0.06f, scaleY(hipY, bodyScale), confidence),
            BodyLandmark.LeftKnee to point(centerX - 0.05f, scaleY(kneeY, bodyScale), confidence),
            BodyLandmark.RightKnee to point(centerX + 0.05f, scaleY(kneeY, bodyScale), confidence),
            BodyLandmark.LeftAnkle to point(centerX - 0.04f, scaleY(footY, bodyScale), confidence),
            BodyLandmark.RightAnkle to point(centerX + 0.04f, scaleY(footY, bodyScale), confidence),
        )
        return PoseFrame(timestampMs = 0L, landmarks = scaled)
    }

    private fun scaleY(
        y: Float,
        bodyScale: Float,
    ): Float {
        val centerY = 0.59f
        return centerY + (y - centerY) * bodyScale
    }

    private fun point(
        x: Float,
        y: Float,
        confidence: Float,
    ) = PosePoint(x = x, y = y, confidence = confidence)
}
