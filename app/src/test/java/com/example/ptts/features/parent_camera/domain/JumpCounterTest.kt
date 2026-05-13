package com.example.ptts.features.parent_camera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JumpCounterTest {
    @Test
    fun standingStill_doesNotCount() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        repeat(300) { index ->
            result = counter.accept(frame(timestampMs = (index + 1) * 33L, footY = GroundFootY))
        }

        assertEquals(0, result.count)
        assertEquals(JumpPhase.Grounded, result.phase)
    }

    @Test
    fun standardJumpCycles_countEveryLanding() {
        assertEquals(10, runStandardJumps(10))
        assertEquals(50, runStandardJumps(50))
        assertEquals(100, runStandardJumps(100))
    }

    @Test
    fun smallBounces_doNotCount() {
        val counter = JumpCounter()
        var timeMs = 0L
        var result = counter.accept(frame(timestampMs = timeMs, footY = GroundFootY))

        repeat(60) {
            val footY = if (it % 2 == 0) GroundFootY - 0.012f else GroundFootY
            timeMs += 50L
            result = counter.accept(frame(timestampMs = timeMs, footY = footY))
        }

        assertEquals(0, result.count)
    }

    @Test
    fun lowConfidenceFrames_doNotCreateGhostCounts() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        repeat(30) { index ->
            result = counter.accept(
                frame(
                    timestampMs = (index + 1) * 40L,
                    footY = GroundFootY - 0.08f,
                    confidence = 0.2f,
                ),
            )
        }

        assertEquals(0, result.count)
        assertFalse(result.countedThisFrame)
    }

    @Test
    fun quickConsecutiveJumps_areCountedWithoutDoubleCounting() {
        assertEquals(20, runStandardJumps(20, cycleSpacingMs = 230L))
    }

    @Test
    fun lowFrameRateJumps_countWhenPeakFrameIsMissed() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 90L

        repeat(20) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.018f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 120L, footY = GroundFootY - 0.018f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 190L, footY = GroundFootY))
            cycleStartMs += 330L
        }

        assertEquals(20, result.count)
    }

    @Test
    fun toeBounceStyleJumps_countWithLowAmplitudeWhenMotionPersists() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 90L

        repeat(18) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.012f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 70L, footY = GroundFootY - 0.013f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 145L, footY = GroundFootY - 0.004f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 190L, footY = GroundFootY))
            cycleStartMs += 330L
        }

        assertEquals(18, result.count)
    }

    @Test
    fun asymmetricFeet_stillCountAsOneJump() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L

        repeat(12) {
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs,
                    leftFootY = GroundFootY - 0.060f,
                    rightFootY = GroundFootY - 0.035f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 60L,
                    leftFootY = GroundFootY - 0.070f,
                    rightFootY = GroundFootY - 0.040f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 130L,
                    leftFootY = GroundFootY - 0.012f,
                    rightFootY = GroundFootY - 0.008f,
                ),
            )
            result = counter.accept(frame(timestampMs = cycleStartMs + 180L, footY = GroundFootY))
            cycleStartMs += 320L
        }

        assertEquals(12, result.count)
    }

    @Test
    fun baselineDrift_doesNotCauseMissedCounts() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L
        var groundFootY = GroundFootY

        repeat(50) {
            groundFootY -= 0.001f
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = groundFootY - 0.055f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 50L, footY = groundFootY - 0.075f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 120L, footY = groundFootY - 0.015f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 160L, footY = groundFootY))
            cycleStartMs += 300L
        }

        assertEquals(50, result.count)
    }

    @Test
    fun fatigueJumping_countsDespiteDecreasingHeight() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L
        var peakOffset = 0.070f

        repeat(15) {
            peakOffset -= 0.0025f
            val peakFootY = GroundFootY - peakOffset
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = peakFootY + 0.008f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 50L, footY = peakFootY))
            result = counter.accept(frame(timestampMs = cycleStartMs + 120L, footY = GroundFootY - 0.015f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 160L, footY = GroundFootY))
            cycleStartMs += 300L
        }

        assertEquals(15, result.count)
    }

    @Test
    fun lowHeelConfidence_usesAnklesAndStillCounts() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L

        repeat(12) {
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs,
                    footY = GroundFootY - 0.06f,
                    leftHeelConfidence = 0.1f,
                    rightHeelConfidence = 0.1f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 55L,
                    footY = GroundFootY - 0.075f,
                    leftHeelConfidence = 0.1f,
                    rightHeelConfidence = 0.1f,
                ),
            )
            result = counter.accept(frame(timestampMs = cycleStartMs + 125L, footY = GroundFootY - 0.012f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 170L, footY = GroundFootY))
            cycleStartMs += 310L
        }

        assertEquals(12, result.count)
    }

    @Test
    fun oneVisibleFoot_stillCounts() {
        val counter = JumpCounter()
        var result = counter.accept(
            frame(
                timestampMs = 0L,
                footY = GroundFootY,
                includeRightFoot = false,
            ),
        )
        var cycleStartMs = 80L

        repeat(12) {
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs,
                    footY = GroundFootY - 0.055f,
                    includeRightFoot = false,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 50L,
                    footY = GroundFootY - 0.070f,
                    includeRightFoot = false,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 125L,
                    footY = GroundFootY - 0.012f,
                    includeRightFoot = false,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 170L,
                    footY = GroundFootY,
                    includeRightFoot = false,
                ),
            )
            cycleStartMs += 310L
        }

        assertEquals(12, result.count)
    }

    @Test
    fun missingKnees_doNotBlockCounting() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY, includeKnees = false))
        var cycleStartMs = 80L

        repeat(10) {
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs,
                    footY = GroundFootY - 0.055f,
                    includeKnees = false,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 65L,
                    footY = GroundFootY - 0.070f,
                    includeKnees = false,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 140L,
                    footY = GroundFootY - 0.010f,
                    includeKnees = false,
                ),
            )
            result = counter.accept(frame(timestampMs = cycleStartMs + 185L, footY = GroundFootY, includeKnees = false))
            cycleStartMs += 330L
        }

        assertEquals(10, result.count)
    }

    @Test
    fun missingTorsoDuringJump_usesFallbackScaleAndStillCounts() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 90L

        repeat(8) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.050f))
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 70L,
                    footY = GroundFootY - 0.060f,
                    includeTorso = false,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 145L,
                    footY = GroundFootY - 0.010f,
                    includeTorso = false,
                ),
            )
            result = counter.accept(frame(timestampMs = cycleStartMs + 190L, footY = GroundFootY))
            cycleStartMs += 340L
        }

        assertEquals(8, result.count)
    }

    @Test
    fun singleFrameFootNoise_doesNotCount() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var timeMs = 80L

        repeat(30) {
            result = counter.accept(frame(timestampMs = timeMs, footY = GroundFootY - 0.018f))
            timeMs += 45L
            result = counter.accept(frame(timestampMs = timeMs, footY = GroundFootY))
            timeMs += 220L
        }

        assertEquals(0, result.count)
    }

    @Test
    fun slowBodyFloat_doesNotCountAsJumping() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        result = counter.accept(frame(timestampMs = 200L, footY = GroundFootY - 0.012f))
        result = counter.accept(frame(timestampMs = 700L, footY = GroundFootY - 0.013f))
        result = counter.accept(frame(timestampMs = 1200L, footY = GroundFootY - 0.012f))
        result = counter.accept(frame(timestampMs = 1600L, footY = GroundFootY))

        assertEquals(0, result.count)
    }

    @Test
    fun stuckInRising_resetsAfterTimeout() {
        val counter = JumpCounter()
        var timeMs = 0L

        counter.accept(frame(timestampMs = timeMs, footY = GroundFootY))

        timeMs += 80L
        var result = counter.accept(frame(timestampMs = timeMs, footY = 0.886f))
        assertEquals(JumpPhase.Rising, result.phase)

        repeat(7) {
            timeMs += 45L
            result = counter.accept(frame(timestampMs = timeMs, footY = 0.886f))
        }

        assertEquals(JumpPhase.Grounded, result.phase)
        assertEquals(0, result.count)
    }

    private fun runStandardJumps(
        jumps: Int,
        cycleSpacingMs: Long = 300L,
    ): Int {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L

        repeat(jumps) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.055f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 50L, footY = GroundFootY - 0.075f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 120L, footY = GroundFootY - 0.015f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 160L, footY = GroundFootY))
            cycleStartMs += cycleSpacingMs
        }

        return result.count
    }

    private fun frame(
        timestampMs: Long,
        footY: Float = GroundFootY,
        leftFootY: Float = footY,
        rightFootY: Float = footY,
        confidence: Float = 0.95f,
        leftAnkleConfidence: Float = confidence,
        rightAnkleConfidence: Float = confidence,
        leftHeelConfidence: Float = confidence,
        rightHeelConfidence: Float = confidence,
        includeTorso: Boolean = true,
        includeKnees: Boolean = true,
        includeLeftFoot: Boolean = true,
        includeRightFoot: Boolean = true,
    ): PoseFrame {
        val landmarks = mutableMapOf<BodyLandmark, PosePoint>()
        if (includeTorso) {
            landmarks[BodyLandmark.LeftShoulder] = point(0.42f, 0.28f, confidence)
            landmarks[BodyLandmark.RightShoulder] = point(0.58f, 0.28f, confidence)
            landmarks[BodyLandmark.LeftHip] = point(0.44f, 0.55f, confidence)
            landmarks[BodyLandmark.RightHip] = point(0.56f, 0.55f, confidence)
        }
        if (includeKnees) {
            landmarks[BodyLandmark.LeftKnee] = point(0.45f, 0.72f, confidence)
            landmarks[BodyLandmark.RightKnee] = point(0.55f, 0.72f, confidence)
        }
        if (includeLeftFoot) {
            landmarks[BodyLandmark.LeftAnkle] = point(0.46f, leftFootY, leftAnkleConfidence)
            landmarks[BodyLandmark.LeftHeel] = point(0.45f, leftFootY, leftHeelConfidence)
        }
        if (includeRightFoot) {
            landmarks[BodyLandmark.RightAnkle] = point(0.54f, rightFootY, rightAnkleConfidence)
            landmarks[BodyLandmark.RightHeel] = point(0.55f, rightFootY, rightHeelConfidence)
        }
        return PoseFrame(timestampMs = timestampMs, landmarks = landmarks)
    }

    private fun point(
        x: Float,
        y: Float,
        confidence: Float,
    ) = PosePoint(x = x, y = y, confidence = confidence)

    private companion object {
        const val GroundFootY = 0.9f
    }
}
