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
    fun moderatelyDistantFullBody_reportsGood() {
        val result = PoseCaptureQualityAnalyzer().analyze(frame(bodyScale = 0.55f))

        assertEquals(CaptureQualityIssue.Good, result.issue)
    }

    @Test
    fun lowConfidencePose_reportsLowLightOrBlur() {
        val result = PoseCaptureQualityAnalyzer().analyze(frame(confidence = 0.45f))

        assertEquals(CaptureQualityIssue.LowLightOrBlur, result.issue)
    }

    @Test
    fun lowConfidencePointsNearEdge_doNotReportEdgeClipped() {
        val result = PoseCaptureQualityAnalyzer().analyze(
            frame(centerX = 0.03f, confidence = 0.25f),
        )

        assertEquals(CaptureQualityIssue.LowLightOrBlur, result.issue)
    }

    @Test
    fun missingFeet_reportsPartialBodyInsteadOfLowLight() {
        val base = frame().landmarks
        val result = PoseCaptureQualityAnalyzer().analyze(
            PoseFrame(
                timestampMs = 0L,
                landmarks = base - BodyLandmark.LeftAnkle - BodyLandmark.RightAnkle,
            ),
        )

        assertEquals(CaptureQualityIssue.PartialBody, result.issue)
    }

    @Test
    fun oneUnreliableRequiredPoint_reportsUnreliablePose() {
        val landmarks = frame().landmarks.toMutableMap()
        val leftAnkle = landmarks.getValue(BodyLandmark.LeftAnkle)
        landmarks[BodyLandmark.LeftAnkle] = leftAnkle.copy(confidence = 0.10f)

        val result = PoseCaptureQualityAnalyzer().analyze(
            PoseFrame(timestampMs = 0L, landmarks = landmarks),
        )

        assertEquals(CaptureQualityIssue.UnreliablePose, result.issue)
    }

    @Test
    fun framingIssue_isDebouncedAcrossObservedFrames() {
        val analyzer = PoseCaptureQualityAnalyzer()
        assertEquals(CaptureQualityIssue.Good, analyzer.analyze(frame(timestampMs = 0L)).issue)

        assertEquals(
            CaptureQualityIssue.Good,
            analyzer.analyze(frame(timestampMs = 33L, centerY = 0.29f)).issue,
        )
        assertEquals(
            CaptureQualityIssue.Good,
            analyzer.analyze(frame(timestampMs = 66L, centerY = 0.29f)).issue,
        )
        assertEquals(
            CaptureQualityIssue.EdgeClipped,
            analyzer.analyze(frame(timestampMs = 99L, centerY = 0.29f)).issue,
        )

        assertEquals(
            CaptureQualityIssue.EdgeClipped,
            analyzer.analyze(frame(timestampMs = 132L)).issue,
        )
        assertEquals(
            CaptureQualityIssue.EdgeClipped,
            analyzer.analyze(frame(timestampMs = 165L)).issue,
        )
        assertEquals(
            CaptureQualityIssue.Good,
            analyzer.analyze(frame(timestampMs = 198L)).issue,
        )
    }

    @Test
    fun poseNearFrameEdge_reportsEdgeClipped() {
        val result = PoseCaptureQualityAnalyzer().analyze(frame(centerY = 0.29f))

        assertEquals(CaptureQualityIssue.EdgeClipped, result.issue)
    }

    @Test
    fun singleHorizontalShift_doesNotImmediatelyReportShaky() {
        val analyzer = PoseCaptureQualityAnalyzer()
        analyzer.analyze(frame(centerX = 0.50f))

        val result = analyzer.analyze(frame(centerX = 0.56f))

        assertEquals(CaptureQualityIssue.Good, result.issue)
    }

    @Test
    fun consecutiveHorizontalShifts_reportShaky() {
        val analyzer = PoseCaptureQualityAnalyzer()
        analyzer.analyze(frame(centerX = 0.50f))
        analyzer.analyze(frame(centerX = 0.60f))

        val result = analyzer.analyze(frame(centerX = 0.70f))

        assertEquals(CaptureQualityIssue.Shaky, result.issue)
    }

    @Test
    fun fullStablePose_reportsGood() {
        val result = PoseCaptureQualityAnalyzer().analyze(frame())

        assertEquals(CaptureQualityIssue.Good, result.issue)
        assertEquals(100, result.score)
    }

    private fun frame(
        timestampMs: Long = 0L,
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
        return PoseFrame(timestampMs = timestampMs, landmarks = scaled)
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
