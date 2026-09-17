package com.example.ptts.features.parent_camera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun fastChildCadence_countsWithoutDoubleCounting() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 70L

        repeat(24) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.040f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 45L, footY = GroundFootY - 0.056f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 95L, footY = GroundFootY - 0.012f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 130L, footY = GroundFootY))
            cycleStartMs += 205L
        }

        assertEquals(24, result.count)
    }

    @Test
    fun childKneeDipBeforeJump_stillCountsOnePerLanding() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 90L

        repeat(16) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY, bodyOffsetY = 0.018f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 55L, footY = GroundFootY - 0.052f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 110L, footY = GroundFootY - 0.065f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 170L, footY = GroundFootY - 0.010f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 210L, footY = GroundFootY))
            cycleStartMs += 340L
        }

        assertEquals(16, result.count)
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
    fun rawPeakAfterTakeoff_isRetainedWhenSmoothingAttenuatesIt() {
        val diagnostics = mutableListOf<JumpDiagnostic>()
        val counter = JumpCounter(onDiagnostic = diagnostics::add)
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        // The first frame only crosses the rising threshold. The real peak is
        // on the following sample, so the final evidence must retain the
        // larger raw displacement instead of only the filtered value.
        result = counter.accept(frame(timestampMs = 80L, footY = GroundFootY - 0.012f))
        result = counter.accept(frame(timestampMs = 130L, footY = GroundFootY - 0.020f))
        result = counter.accept(frame(timestampMs = 205L, footY = GroundFootY))

        assertEquals(1, result.count)
        assertTrue(result.countedThisFrame)
        val counted = diagnostics.last { it.event == "counted" }
        assertTrue((counted.peakLift ?: 0f) > (counted.smoothedLift ?: 0f))
    }

    @Test
    fun thresholdCrossingIsInterpolatedForShortVisibleJump() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        // The first sample is below the rising threshold and the next sample
        // is the first visible peak. Without interpolation, the apparent air
        // time is only 60 ms and the jump is rejected as too short.
        result = counter.accept(frame(timestampMs = 80L, footY = GroundFootY - 0.011f))
        result = counter.accept(frame(timestampMs = 160L, footY = GroundFootY - 0.040f))
        result = counter.accept(frame(timestampMs = 220L, footY = GroundFootY))

        assertEquals(1, result.count)
        assertTrue(result.countedThisFrame)
    }

    @Test
    fun singleFrameFootPeak_withoutBodyLiftDoesNotUseWideLowFramePath() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        result = counter.accept(frame(timestampMs = 80L, footY = GroundFootY - 0.020f))
        result = counter.accept(frame(timestampMs = 160L, footY = GroundFootY))

        assertEquals(0, result.count)
        assertFalse(result.countedThisFrame)
    }

    @Test
    fun diagnosticsExposeSamplingAndDecisionEvidence() {
        val diagnostics = mutableListOf<JumpDiagnostic>()
        val counter = JumpCounter(onDiagnostic = diagnostics::add)

        counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        counter.accept(frame(timestampMs = 80L, footY = GroundFootY - 0.055f))
        counter.accept(frame(timestampMs = 150L, footY = GroundFootY - 0.070f))
        val result = counter.accept(frame(timestampMs = 240L, footY = GroundFootY))

        assertTrue(result.countedThisFrame)
        val counted = diagnostics.last { it.event == "counted" }
        assertEquals(90L, counted.sampleIntervalMs)
        assertTrue((counted.rawLift ?: 0f) >= 0f)
        assertTrue((counted.smoothedLift ?: 0f) >= 0f)
        assertTrue((counted.peakLift ?: 0f) > 0f)
        assertTrue((counted.jumpDurationMs ?: 0L) >= 95L)
        assertEquals(null, counted.rejectionReason)
    }

    @Test
    fun normalJumpsAtCommonSamplingIntervals_countWithoutPeakFrameAlignment() {
        for (intervalMs in listOf(33L, 50L, 67L)) {
            val counter = JumpCounter()
            var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
            var timestampMs = 47L

            repeat(12) {
                result = counter.accept(frame(timestampMs = timestampMs, footY = GroundFootY - 0.020f))
                result = counter.accept(frame(timestampMs = timestampMs + intervalMs, footY = GroundFootY - 0.060f))
                result = counter.accept(frame(timestampMs = timestampMs + intervalMs * 2, footY = GroundFootY - 0.018f))
                result = counter.accept(frame(timestampMs = timestampMs + intervalMs * 3, footY = GroundFootY))
                timestampMs += 300L
            }

            assertEquals("interval=${intervalMs}ms", 12, result.count)
        }
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
    fun distantSmallMotion_countsWithBodyScaledThresholds() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY, bodyScale = 0.55f))
        var cycleStartMs = 90L

        repeat(16) {
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs,
                    footY = GroundFootY - 0.010f,
                    bodyScale = 0.55f,
                    bodyOffsetY = -0.006f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 70L,
                    footY = GroundFootY - 0.012f,
                    bodyScale = 0.55f,
                    bodyOffsetY = -0.008f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 145L,
                    footY = GroundFootY - 0.003f,
                    bodyScale = 0.55f,
                    bodyOffsetY = -0.002f,
                ),
            )
            result = counter.accept(frame(timestampMs = cycleStartMs + 190L, footY = GroundFootY, bodyScale = 0.55f))
            cycleStartMs += 330L
        }

        assertEquals(16, result.count)
    }

    @Test
    fun bodyMotionCountsWhenFootSignalIsWeakFromSideAngle() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 90L

        repeat(14) {
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs,
                    footY = GroundFootY - 0.004f,
                    bodyOffsetY = -0.018f,
                    leftAnkleConfidence = 0.2f,
                    rightAnkleConfidence = 0.2f,
                    leftHeelConfidence = 0.2f,
                    rightHeelConfidence = 0.2f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 70L,
                    footY = GroundFootY - 0.004f,
                    bodyOffsetY = -0.024f,
                    leftAnkleConfidence = 0.2f,
                    rightAnkleConfidence = 0.2f,
                    leftHeelConfidence = 0.2f,
                    rightHeelConfidence = 0.2f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 145L,
                    footY = GroundFootY,
                    bodyOffsetY = -0.006f,
                    leftAnkleConfidence = 0.2f,
                    rightAnkleConfidence = 0.2f,
                    leftHeelConfidence = 0.2f,
                    rightHeelConfidence = 0.2f,
                ),
            )
            result = counter.accept(frame(timestampMs = cycleStartMs + 190L, footY = GroundFootY))
            cycleStartMs += 330L
        }

        assertEquals(14, result.count)
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
    fun poseLossThenRecovery_doesNotCreateGhostCounts() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        result = counter.accept(frame(timestampMs = 120L, footY = GroundFootY - 0.06f))
        result = counter.accept(frame(timestampMs = 190L, footY = GroundFootY - 0.07f))
        result = counter.accept(frame(timestampMs = 260L, footY = GroundFootY))
        assertEquals(1, result.count)

        result = counter.accept(PoseFrame(timestampMs = 1200L, landmarks = emptyMap()))
        result = counter.accept(frame(timestampMs = 1600L, footY = GroundFootY))
        result = counter.accept(frame(timestampMs = 1720L, footY = GroundFootY))
        result = counter.accept(frame(timestampMs = 1840L, footY = GroundFootY))
        assertEquals(1, result.count)

        var cycleStartMs = 2050L
        repeat(6) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.055f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 65L, footY = GroundFootY - 0.070f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 140L, footY = GroundFootY - 0.010f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 185L, footY = GroundFootY))
            cycleStartMs += 330L
        }

        assertEquals(7, result.count)
    }

    @Test
    fun repositionAfterManyCounts_recoversAndContinuesCounting() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L

        repeat(55) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.055f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 50L, footY = GroundFootY - 0.075f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 120L, footY = GroundFootY - 0.015f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 160L, footY = GroundFootY))
            cycleStartMs += 300L
        }
        assertEquals(55, result.count)

        result = counter.accept(PoseFrame(timestampMs = cycleStartMs, landmarks = emptyMap()))
        result = counter.accept(PoseFrame(timestampMs = cycleStartMs + 80L, landmarks = emptyMap()))

        val shiftedGroundY = GroundFootY - 0.060f
        repeat(5) { index ->
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 160L + index * 50L,
                    footY = shiftedGroundY,
                    bodyScale = 0.82f,
                    baseGroundY = shiftedGroundY,
                    centerX = 0.57f,
                ),
            )
        }

        cycleStartMs += 520L
        repeat(10) {
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs,
                    footY = shiftedGroundY - 0.045f,
                    bodyScale = 0.82f,
                    baseGroundY = shiftedGroundY,
                    centerX = 0.57f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 55L,
                    footY = shiftedGroundY - 0.060f,
                    bodyScale = 0.82f,
                    baseGroundY = shiftedGroundY,
                    centerX = 0.57f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 130L,
                    footY = shiftedGroundY - 0.010f,
                    bodyScale = 0.82f,
                    baseGroundY = shiftedGroundY,
                    centerX = 0.57f,
                ),
            )
            result = counter.accept(
                frame(
                    timestampMs = cycleStartMs + 180L,
                    footY = shiftedGroundY,
                    bodyScale = 0.82f,
                    baseGroundY = shiftedGroundY,
                    centerX = 0.57f,
                ),
            )
            cycleStartMs += 330L
        }

        assertEquals(65, result.count)
    }

    @Test
    fun shortPoseLoss_recoversQuicklyWithoutGhostCounts() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        result = counter.accept(frame(timestampMs = 100L, footY = GroundFootY - 0.060f))
        result = counter.accept(frame(timestampMs = 165L, footY = GroundFootY - 0.072f))
        result = counter.accept(frame(timestampMs = 235L, footY = GroundFootY))
        assertEquals(1, result.count)

        result = counter.accept(PoseFrame(timestampMs = 520L, landmarks = emptyMap()))
        result = counter.accept(frame(timestampMs = 600L, footY = GroundFootY))
        result = counter.accept(frame(timestampMs = 680L, footY = GroundFootY))
        result = counter.accept(frame(timestampMs = 760L, footY = GroundFootY))
        assertEquals(1, result.count)

        result = counter.accept(frame(timestampMs = 900L, footY = GroundFootY - 0.055f))
        result = counter.accept(frame(timestampMs = 960L, footY = GroundFootY - 0.070f))
        result = counter.accept(frame(timestampMs = 1035L, footY = GroundFootY - 0.010f))
        result = counter.accept(frame(timestampMs = 1080L, footY = GroundFootY))

        assertEquals(2, result.count)
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

    @Test
    fun landingPhase_timeoutReturnsToGrounded() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))

        result = counter.accept(frame(timestampMs = 100L, footY = GroundFootY - 0.060f))
        result = counter.accept(frame(timestampMs = 170L, footY = GroundFootY - 0.075f))
        result = counter.accept(frame(timestampMs = 250L, footY = GroundFootY - 0.010f))
        assertEquals(JumpPhase.Landing, result.phase)

        result = counter.accept(frame(timestampMs = 600L, footY = GroundFootY - 0.026f))

        assertEquals(JumpPhase.Grounded, result.phase)
        assertEquals(0, result.count)
    }

    @Test
    fun duplicateTimestamp_isIgnored() {
        val counter = JumpCounter()
        val first = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        val duplicate = counter.accept(frame(timestampMs = 0L, footY = GroundFootY - 0.080f))

        assertEquals(first.count, duplicate.count)
        assertEquals(first.phase, duplicate.phase)
    }

    @Test
    fun longValidFrameGap_dropsExpiredCandidate() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        result = counter.accept(frame(timestampMs = 100L, footY = GroundFootY - 0.070f))

        result = counter.accept(frame(timestampMs = 500L, footY = GroundFootY))

        assertEquals(0, result.count)
        assertEquals(JumpPhase.Grounded, result.phase)
        assertFalse(result.recovering)
    }

    @Test
    fun shortPoseLoss_preservesCandidateAndRecoversOnLanding() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        result = counter.accept(frame(timestampMs = 100L, footY = GroundFootY - 0.060f))
        result = counter.accept(frame(timestampMs = 165L, footY = GroundFootY - 0.072f))
        assertEquals(JumpPhase.Airborne, result.phase)

        result = counter.accept(PoseFrame(timestampMs = 210L, landmarks = emptyMap()))
        assertEquals(JumpPhase.Airborne, result.phase)
        assertTrue(result.recovering)

        result = counter.accept(frame(timestampMs = 260L, footY = GroundFootY))

        assertEquals(1, result.count)
        assertFalse(result.recovering)
    }

    @Test
    fun stableCadence_estimatesBoundedGapAndReconcilesLanding() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L
        repeat(6) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.055f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 50L, footY = GroundFootY - 0.075f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 120L, footY = GroundFootY - 0.015f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 160L, footY = GroundFootY))
            cycleStartMs += 300L
        }
        assertEquals(6, result.confirmedCount)

        result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.055f))
        result = counter.accept(PoseFrame(timestampMs = cycleStartMs + 200L, landmarks = emptyMap()))
        assertTrue(result.estimatedCount <= 2)
        assertTrue(result.estimatedThisFrame)

        result = counter.accept(frame(timestampMs = cycleStartMs + 240L, footY = GroundFootY))
        assertEquals(7, result.count)
        assertEquals(7, result.confirmedCount)
        assertEquals(0, result.estimatedCount)
    }

    @Test
    fun cameraMoveDuringJump_recoversWithinStableWindowAndCountsNextJump() {
        for (shift in listOf(-0.12f, 0.08f)) {
            val counter = JumpCounter()
            counter.accept(frame(0L))
            counter.accept(frame(100L, footY = GroundFootY - 0.06f))
            counter.accept(frame(165L, footY = GroundFootY - 0.075f))
            val ground = GroundFootY + shift
            val moving = frame(200L, footY = ground, baseGroundY = ground).copy(
                cameraMotion = CameraMotion(available = true, magnitude = 0.08f),
            )
            val interrupted = counter.accept(moving)
            assertTrue(interrupted.recovering)
            assertEquals(0, interrupted.count)
            // 连续移动不能被误当作已经稳定，也不能保留移动前的腾空候选。
            assertTrue(counter.accept(moving.copy(timestampMs = 400L)).recovering)
            for (time in listOf(450L, 500L, 550L)) {
                val recovering = counter.accept(frame(time, footY = ground, baseGroundY = ground))
                assertTrue(recovering.recovering)
                assertEquals(0, recovering.count)
            }
            assertFalse(counter.accept(frame(600L, footY = ground, baseGroundY = ground)).recovering)
            counter.accept(frame(650L, footY = ground - 0.055f, baseGroundY = ground))
            counter.accept(frame(705L, footY = ground - 0.075f, baseGroundY = ground))
            counter.accept(frame(780L, footY = ground - 0.010f, baseGroundY = ground))
            val landed = counter.accept(frame(830L, footY = ground, baseGroundY = ground))
            assertEquals(1, landed.confirmedCount)
        }
    }

    @Test
    fun cameraRecovery_poseLossRestartsStableWindow() {
        val counter = JumpCounter()
        counter.accept(frame(0L))
        counter.accept(frame(100L).copy(
            cameraMotion = CameraMotion(available = true, magnitude = 0.08f),
        ))
        assertTrue(counter.accept(frame(150L)).recovering)
        counter.accept(PoseFrame(200L, emptyMap()))
        assertTrue(counter.accept(frame(250L)).recovering)
        assertTrue(counter.accept(frame(350L)).recovering)
        assertFalse(counter.accept(frame(400L)).recovering)
        assertEquals(0, counter.accept(frame(450L)).count)
    }

    @Test
    fun groundedPoseRecovery_countsNextJumpWithoutStartupDelay() {
        val counter = JumpCounter()
        counter.accept(frame(0L))
        counter.accept(PoseFrame(200L, emptyMap()))
        counter.accept(frame(250L))
        counter.accept(frame(300L, footY = GroundFootY - 0.055f))
        counter.accept(frame(355L, footY = GroundFootY - 0.075f))
        counter.accept(frame(430L, footY = GroundFootY - 0.010f))
        assertEquals(1, counter.accept(frame(480L)).count)
    }

    @Test
    fun unreliableMotion_keepsAccumulatedCompensationWithoutGhostJump() {
        val counter = JumpCounter()
        val motion = CameraMotion(offsetY = 0.1f, available = true, reliable = true)
        val standing = frame(0L).let { pose ->
            pose.copy(
                landmarks = pose.landmarks.mapValues { (_, point) -> point.copy(y = point.y + 0.1f) },
                cameraMotion = motion,
            )
        }
        counter.accept(standing)
        for (index in 1..20) {
            val result = counter.accept(
                standing.copy(
                    timestampMs = index * 50L,
                    cameraMotion = motion.copy(reliable = index % 4 < 2),
                ),
            )
            assertEquals(0, result.count)
            assertEquals(JumpPhase.Grounded, result.phase)
        }
    }

    @Test
    fun landmarkStreamIgnoresAccumulatedCameraOffset() {
        // A background matcher only measures camera translation. The counter must not move the
        // child's landmarks by it: doing so subtracts exactly the body displacement a jump is
        // made of and doubles any residual error.
        val counter = JumpCounter()
        val motion = CameraMotion(offsetX = 0.2f, offsetY = -0.15f, available = true, reliable = true)
        val standing = frame(0L).let { pose -> pose.copy(cameraMotion = motion) }
        counter.accept(standing)
        for (index in 1..20) {
            val result = counter.accept(standing.copy(timestampMs = index * 50L))
            assertEquals(0, result.count)
            assertEquals(JumpPhase.Grounded, result.phase)
        }
    }

    @Test
    fun scoreNeverDecreasesAcrossALongPoseLoss() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L
        repeat(6) {
            result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.055f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 50L, footY = GroundFootY - 0.075f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 120L, footY = GroundFootY - 0.015f))
            result = counter.accept(frame(timestampMs = cycleStartMs + 160L, footY = GroundFootY))
            cycleStartMs += 300L
        }
        result = counter.accept(frame(timestampMs = cycleStartMs, footY = GroundFootY - 0.055f))
        result = counter.accept(PoseFrame(timestampMs = cycleStartMs + 200L, landmarks = emptyMap()))
        val countWithEstimate = result.count
        assertTrue(countWithEstimate >= 6)

        // The person leaves the frame long enough to discard the standing baseline. The score
        // that was already shown to the user must survive that reset.
        result = counter.accept(PoseFrame(timestampMs = cycleStartMs + 8000L, landmarks = emptyMap()))
        assertTrue(result.count >= countWithEstimate)
        assertEquals(TrackingQuality.NoPose, result.trackingQuality)
    }

    @Test
    fun sustainedCameraMotionStopsBlockingTheCounterForever() {
        val counter = JumpCounter()
        counter.accept(frame(0L))
        // Handheld capture keeps the background matcher unreliable; before the recovery window
        // had a hard limit the counter stayed in "recovering" and never counted again. The
        // window must also keep re-anchoring instead of waiting for a stillness that never comes.
        for (index in 1..40) {
            counter.accept(
                frame(index * 100L).copy(
                    cameraMotion = CameraMotion(available = true, magnitude = 0.09f),
                ),
            )
        }
        // Motion stops: the counter needs the stable window, then resumes counting.
        counter.accept(frame(4100L))
        counter.accept(frame(4200L))
        assertFalse(counter.accept(frame(4400L)).recovering)

        counter.accept(frame(4500L, footY = GroundFootY - 0.055f))
        counter.accept(frame(4600L, footY = GroundFootY - 0.075f))
        counter.accept(frame(4800L, footY = GroundFootY - 0.010f))
        val landed = counter.accept(frame(5000L, footY = GroundFootY))
        assertEquals(1, landed.count)
    }

    @Test
    fun sustainedCameraMotionEventuallyRebuildsTheBaselineWhileStillMoving() {
        val counter = JumpCounter()
        counter.accept(frame(0L))
        val results = (1..40).map { index ->
            counter.accept(
                frame(index * 100L).copy(
                    cameraMotion = CameraMotion(available = true, magnitude = 0.09f),
                ),
            )
        }
        // The escape hatch has to fire at least once, otherwise counting stays disabled for as
        // long as the background matcher keeps reporting unreliable motion.
        assertTrue(results.any { !it.recovering })
    }

    @Test
    fun slowFrameDeliveryDoesNotDiscardJumpCandidates() {
        // 350 ms delivery is a realistic analysis rate once pose inference and video recording
        // share a mid-range phone. It used to exceed the fixed lost-pose tolerance, so every
        // frame discarded the jump candidate and the child was never counted.
        val counter = JumpCounter()
        counter.accept(frame(0L))
        counter.accept(frame(350L))
        counter.accept(frame(700L, footY = GroundFootY - 0.055f))
        counter.accept(frame(1050L, footY = GroundFootY - 0.075f))
        counter.accept(frame(1400L, footY = GroundFootY - 0.010f))
        val landed = counter.accept(frame(1750L, footY = GroundFootY))
        assertEquals(1, landed.count)
    }

    @Test
    fun bouncingDuringCalibration_isRejectedUntilTheChildStandsStill() {
        val counter = JumpCounter()
        // The child is already bouncing while the counter is supposed to learn the standing
        // reference. Those samples must never become a valid calibration, otherwise every
        // later lift is measured against a moving reference.
        var timeMs = 0L
        repeat(30) {
            val footY = if (it % 2 == 0) GroundFootY - 0.055f else GroundFootY
            counter.calibrate(frame(timestampMs = timeMs, footY = footY))
            timeMs += 50L
        }
        assertFalse(counter.isCalibrationReady())

        // Standing still is enough to calibrate, and it takes the full window to be trusted.
        repeat(9) {
            counter.calibrate(frame(timestampMs = timeMs, footY = GroundFootY))
            timeMs += 50L
        }
        assertFalse("a handful of still frames is not a window", counter.isCalibrationReady())
        repeat(3) {
            counter.calibrate(frame(timestampMs = timeMs, footY = GroundFootY))
            timeMs += 50L
        }
        assertTrue(counter.isCalibrationReady())
    }

    @Test
    fun cameraInterruption_continuousJumpsRebuildBaselineAndResumeSensitivity() {
        for (intervalMs in listOf(33L, 50L, 67L)) {
            val counter = JumpCounter()
            var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
            var cycleStartMs = 80L
            repeat(8) {
                result = counter.accept(frame(cycleStartMs, footY = GroundFootY - 0.055f))
                result = counter.accept(frame(cycleStartMs + intervalMs, footY = GroundFootY - 0.075f))
                result = counter.accept(frame(cycleStartMs + intervalMs * 2, footY = GroundFootY - 0.015f))
                result = counter.accept(frame(cycleStartMs + intervalMs * 3, footY = GroundFootY))
                cycleStartMs += 300L
            }
            val confirmedBefore = result.confirmedCount
            val shiftedGround = GroundFootY - 0.08f
            val motion = CameraMotion(available = true, magnitude = 0.09f)
            result = counter.accept(
                frame(cycleStartMs + intervalMs, footY = shiftedGround - 0.055f, baseGroundY = shiftedGround)
                    .copy(cameraMotion = motion),
            )
            assertTrue(result.recovering)
            // A jump during recovery prevents a stationary shortcut and makes
            // the counter collect a complete post-motion cycle.
            counter.accept(frame(cycleStartMs + 180L, footY = shiftedGround, baseGroundY = shiftedGround))
            counter.accept(frame(cycleStartMs + 230L, footY = shiftedGround - 0.055f, baseGroundY = shiftedGround))
            counter.accept(frame(cycleStartMs + 280L, footY = shiftedGround - 0.075f, baseGroundY = shiftedGround))
            counter.accept(frame(cycleStartMs + 350L, footY = shiftedGround - 0.015f, baseGroundY = shiftedGround))
            counter.accept(frame(cycleStartMs + 410L, footY = shiftedGround, baseGroundY = shiftedGround))
            counter.accept(frame(cycleStartMs + 500L, footY = shiftedGround, baseGroundY = shiftedGround))
            counter.accept(frame(cycleStartMs + 650L, footY = shiftedGround, baseGroundY = shiftedGround))

            var postStart = cycleStartMs + 700L
            repeat(20) {
                result = counter.accept(frame(postStart, footY = shiftedGround - 0.055f, baseGroundY = shiftedGround))
                result = counter.accept(frame(postStart + intervalMs, footY = shiftedGround - 0.075f, baseGroundY = shiftedGround))
                result = counter.accept(frame(postStart + intervalMs * 2, footY = shiftedGround - 0.015f, baseGroundY = shiftedGround))
                result = counter.accept(frame(postStart + intervalMs * 3, footY = shiftedGround, baseGroundY = shiftedGround))
                postStart += 300L
            }
            assertEquals("interval=${intervalMs}ms", confirmedBefore + 20, result.confirmedCount)
        }
    }

    @Test
    fun cameraInterruption_repeatedMovementDoesNotResetEstimateQuota() {
        val counter = JumpCounter()
        var result = counter.accept(frame(timestampMs = 0L, footY = GroundFootY))
        var cycleStartMs = 80L
        repeat(8) {
            result = counter.accept(frame(cycleStartMs, footY = GroundFootY - 0.055f))
            result = counter.accept(frame(cycleStartMs + 50L, footY = GroundFootY - 0.075f))
            result = counter.accept(frame(cycleStartMs + 120L, footY = GroundFootY - 0.015f))
            result = counter.accept(frame(cycleStartMs + 160L, footY = GroundFootY))
            cycleStartMs += 300L
        }
        val moving = CameraMotion(available = true, magnitude = 0.09f)
        repeat(10) { index ->
            result = counter.accept(
                frame(cycleStartMs + index * 90L, footY = GroundFootY, baseGroundY = GroundFootY)
                    .copy(cameraMotion = moving),
            )
        }
        assertTrue(result.estimatedCount <= 2)
    }

    @Test
    fun cameraInterruption_withoutTrustedCadenceDoesNotInventCounts() {
        val counter = JumpCounter()
        counter.accept(frame(0L))
        val motion = CameraMotion(available = true, magnitude = 0.09f)
        var result = counter.accept(frame(100L).copy(cameraMotion = motion))
        result = counter.accept(frame(250L))
        result = counter.accept(frame(350L))
        result = counter.accept(frame(450L))
        assertEquals(0, result.count)
        assertEquals(0, result.estimatedCount)
    }

    @Test
    fun cameraInterruption_staticRecoveryDoesNotConfirmAndNextJumpCounts() {
        val counter = JumpCounter()
        var result = counter.accept(frame(0L))
        var start = 80L
        repeat(8) {
            result = counter.accept(frame(start, footY = GroundFootY - 0.055f))
            result = counter.accept(frame(start + 50L, footY = GroundFootY - 0.075f))
            result = counter.accept(frame(start + 120L, footY = GroundFootY - 0.015f))
            result = counter.accept(frame(start + 160L))
            start += 300L
        }
        val before = result.confirmedCount
        val shifted = GroundFootY - 0.07f
        val moving = CameraMotion(available = true, magnitude = 0.09f)
        counter.accept(frame(start + 20L, footY = shifted, baseGroundY = shifted).copy(cameraMotion = moving))
        result = counter.accept(frame(start + 170L, footY = shifted, baseGroundY = shifted))
        result = counter.accept(frame(start + 320L, footY = shifted, baseGroundY = shifted))
        result = counter.accept(frame(start + 370L, footY = shifted, baseGroundY = shifted))
        assertFalse(result.recovering)
        assertEquals(before, result.confirmedCount)

        result = counter.accept(frame(start + 420L, footY = shifted - 0.055f, baseGroundY = shifted))
        result = counter.accept(frame(start + 470L, footY = shifted - 0.075f, baseGroundY = shifted))
        result = counter.accept(frame(start + 540L, footY = shifted - 0.015f, baseGroundY = shifted))
        result = counter.accept(frame(start + 580L, footY = shifted, baseGroundY = shifted))
        assertEquals(before + 1, result.confirmedCount)
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
        bodyOffsetY: Float = 0f,
        bodyScale: Float = 1f,
        baseGroundY: Float = GroundFootY,
        centerX: Float = 0.50f,
    ): PoseFrame {
        val landmarks = mutableMapOf<BodyLandmark, PosePoint>()
        val hipY = baseGroundY - 0.35f * bodyScale + bodyOffsetY
        val shoulderY = hipY - 0.27f * bodyScale
        val kneeY = baseGroundY - 0.18f * bodyScale + bodyOffsetY
        if (includeTorso) {
            landmarks[BodyLandmark.LeftShoulder] = point(centerX - 0.08f, shoulderY, confidence)
            landmarks[BodyLandmark.RightShoulder] = point(centerX + 0.08f, shoulderY, confidence)
            landmarks[BodyLandmark.LeftHip] = point(centerX - 0.06f, hipY, confidence)
            landmarks[BodyLandmark.RightHip] = point(centerX + 0.06f, hipY, confidence)
        }
        if (includeKnees) {
            landmarks[BodyLandmark.LeftKnee] = point(centerX - 0.05f, kneeY, confidence)
            landmarks[BodyLandmark.RightKnee] = point(centerX + 0.05f, kneeY, confidence)
        }
        if (includeLeftFoot) {
            landmarks[BodyLandmark.LeftAnkle] = point(centerX - 0.04f, leftFootY, leftAnkleConfidence)
            landmarks[BodyLandmark.LeftHeel] = point(centerX - 0.05f, leftFootY, leftHeelConfidence)
        }
        if (includeRightFoot) {
            landmarks[BodyLandmark.RightAnkle] = point(centerX + 0.04f, rightFootY, rightAnkleConfidence)
            landmarks[BodyLandmark.RightHeel] = point(centerX + 0.05f, rightFootY, rightHeelConfidence)
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
