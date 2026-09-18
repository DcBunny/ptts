package com.example.ptts.features.parent_camera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLowLightStrategyTest {
    @Test
    fun darkFrames_areDebouncedBeforeFirstCommand() {
        val strategy = strategy()

        assertNull(strategy.onFrame(metrics(0L), validPose = true, analysisFps = 20f))
        assertNull(strategy.onFrame(metrics(500L), validPose = true, analysisFps = 20f))
        val decision = strategy.onFrame(metrics(1_001L), validPose = true, analysisFps = 20f)

        assertEquals(AutoLowLightAction.EnableLowLightBoost, decision?.action)
        strategy.onCommandResult(
            AutoLowLightCommandResult(
                action = AutoLowLightAction.EnableLowLightBoost,
                success = true,
                mode = AutoLowLightMode.LowLightBoost,
                exposureLevel = 0,
                actualLowLightBoostEnabled = true,
            ),
        )
        assertEquals(AutoLowLightState.Enhanced, strategy.state)
    }

    @Test
    fun alternatingLight_doesNotCrossDarkDebounce() {
        val strategy = strategy()
        assertNull(strategy.onFrame(metrics(0L), validPose = true, analysisFps = 20f))
        assertNull(strategy.onFrame(metrics(400L, luma = 0.50f, darkRatio = 0.10f), true, 20f))
        assertNull(strategy.onFrame(metrics(800L), true, 20f))
        assertNull(strategy.onFrame(metrics(1_200L, luma = 0.50f, darkRatio = 0.10f), true, 20f))
    }

    @Test
    fun overexposedRegion_doesNotTriggerMoreExposure() {
        val strategy = strategy()
        val clipped = { timestampMs: Long ->
            FrameLightMetrics(
                timestampMs = timestampMs,
                meanLuma = 0.20f,
                regionMeanLuma = 0.10f,
                darkPixelRatio = 0.80f,
                overexposedRatio = 0.60f,
                region = LightMeasurementRegion.Person,
                sampleCount = 20,
            )
        }
        assertNull(strategy.onFrame(clipped(0L), true, 20f))
        assertNull(strategy.onFrame(clipped(1_500L), true, 20f))
        assertEquals(AutoLowLightState.Normal, strategy.state)
    }

    @Test
    fun capabilityRebind_clearsActiveModeAndAllowsFreshCommand() {
        val strategy = strategy()
        strategy.onFrame(metrics(0L), true, 20f)
        val enable = strategy.onFrame(metrics(1_001L), true, 20f)
        assertEquals(AutoLowLightAction.EnableLowLightBoost, enable?.action)
        strategy.onCommandResult(
            AutoLowLightCommandResult(
                action = AutoLowLightAction.EnableLowLightBoost,
                success = true,
                mode = AutoLowLightMode.LowLightBoost,
                exposureLevel = 0,
                actualLowLightBoostEnabled = true,
            ),
        )

        strategy.setCapability(AutoLowLightCapability(false, 0..4))

        assertEquals(AutoLowLightMode.None, strategy.mode)
        assertEquals(AutoLowLightState.Normal, strategy.state)
        val fresh = strategy.onFrame(metrics(1_100L), true, 20f)
        assertNull(fresh)
    }

    @Test
    fun recoveredEnvironment_restoresOriginalExposureAfterDebounce() {
        val strategy = AutoLowLightStrategy(minObservationSamples = 3)
            .also { it.setCapability(AutoLowLightCapability(true, 0..4)) }
        strategy.onFrame(metrics(0L), true, 20f)
        strategy.onFrame(metrics(1_001L), true, 20f)?.let { decision ->
            strategy.onCommandResult(
                AutoLowLightCommandResult(
                    action = decision.action,
                    success = true,
                    mode = decision.mode,
                    exposureLevel = decision.exposureLevel,
                    actualLowLightBoostEnabled = true,
                ),
            )
        }
        val bright = { timestampMs: Long -> metrics(timestampMs, luma = 0.50f, darkRatio = 0.10f) }
        strategy.onFrame(bright(1_100L), true, 20f)
        strategy.onFrame(bright(1_800L), true, 20f)
        strategy.onFrame(bright(2_500L), true, 20f)
        strategy.onFrame(bright(3_100L), true, 20f)
        val restore = strategy.onFrame(bright(4_100L), true, 20f)

        assertEquals(AutoLowLightAction.DisableEnhancement, restore?.action)
    }

    @Test
    fun unsupportedBoost_fallsBackToFirstExposureLevel() {
        val strategy = strategy()
        strategy.setCapability(AutoLowLightCapability(false, 0..4))

        strategy.onFrame(metrics(0L), validPose = true, analysisFps = 20f)
        strategy.onFrame(metrics(1_001L), validPose = true, analysisFps = 20f).let { decision ->
            assertEquals(AutoLowLightAction.SetExposure, decision?.action)
            assertEquals(1, decision?.exposureLevel)
        }
    }

    @Test
    fun commandFailure_doesNotRetryFailedExposureLevel() {
        val strategy = strategy()
        strategy.setCapability(AutoLowLightCapability(false, 0..4))
        strategy.onFrame(metrics(0L), validPose = true, analysisFps = 20f)
        val first = strategy.onFrame(metrics(1_001L), validPose = true, analysisFps = 20f)
        assertEquals(1, first?.exposureLevel)

        strategy.onCommandResult(
            AutoLowLightCommandResult(
                action = AutoLowLightAction.SetExposure,
                success = false,
                mode = AutoLowLightMode.ExposureCompensation,
                exposureLevel = 1,
                error = "unsupported",
            ),
        )
        val next = strategy.onFrame(metrics(1_050L), validPose = true, analysisFps = 20f)

        assertEquals(AutoLowLightAction.SetExposure, next?.action)
        assertEquals(2, next?.exposureLevel)
    }

    @Test
    fun noPoseAfterAdjustment_stopsAtTooDarkInsteadOfRaisingForever() {
        val strategy = strategy()
        strategy.setCapability(AutoLowLightCapability(false, 0..4))
        strategy.onFrame(metrics(0L), validPose = false, analysisFps = 20f)
        val first = strategy.onFrame(metrics(1_001L), validPose = false, analysisFps = 20f)
        assertEquals(AutoLowLightAction.SetExposure, first?.action)
        strategy.onCommandResult(
            AutoLowLightCommandResult(
                action = AutoLowLightAction.SetExposure,
                success = true,
                mode = AutoLowLightMode.ExposureCompensation,
                exposureLevel = 1,
            ),
        )

        strategy.onFrame(metrics(1_100L), validPose = false, analysisFps = 20f)
        strategy.onFrame(metrics(1_800L), validPose = false, analysisFps = 20f)
        strategy.onFrame(metrics(2_500L), validPose = false, analysisFps = 20f)
        strategy.onFrame(metrics(3_100L), validPose = false, analysisFps = 20f)

        assertEquals(AutoLowLightState.TooDark, strategy.state)
        assertTrue(strategy.lastReason.contains("姿态"))
    }

    @Test
    fun tooDarkState_exitsAfterEnvironmentRecovers() {
        val strategy = strategy()
        strategy.setCapability(AutoLowLightCapability(false, 0..0))
        strategy.onFrame(metrics(0L), false, 20f)
        strategy.onFrame(metrics(1_001L), false, 20f)
        assertEquals(AutoLowLightState.TooDark, strategy.state)

        val bright = { timestampMs: Long -> metrics(timestampMs, luma = 0.50f, darkRatio = 0.10f) }
        assertNull(strategy.onFrame(bright(1_100L), true, 20f))
        assertNull(strategy.onFrame(bright(2_500L), true, 20f))
        val restore = strategy.onFrame(bright(11_200L), true, 20f)

        assertEquals(AutoLowLightAction.DisableEnhancement, restore?.action)
    }

    @Test
    fun ineffectiveBoost_isDisabledBeforeExposureFallback() {
        val strategy = strategy()
        strategy.onFrame(metrics(0L), validPose = true, analysisFps = 20f)
        val enable = strategy.onFrame(metrics(1_001L), validPose = true, analysisFps = 20f)
        assertEquals(AutoLowLightAction.EnableLowLightBoost, enable?.action)
        strategy.onCommandResult(
            AutoLowLightCommandResult(
                action = AutoLowLightAction.EnableLowLightBoost,
                success = true,
                mode = AutoLowLightMode.LowLightBoost,
                exposureLevel = 0,
                actualLowLightBoostEnabled = true,
            ),
        )

        strategy.onFrame(metrics(1_100L), validPose = true, analysisFps = 20f)
        strategy.onFrame(metrics(1_800L), validPose = true, analysisFps = 20f)
        strategy.onFrame(metrics(2_500L), validPose = true, analysisFps = 20f)
        val disable = strategy.onFrame(metrics(3_100L), validPose = true, analysisFps = 20f)

        assertEquals(AutoLowLightAction.DisableEnhancement, disable?.action)
        strategy.onCommandResult(
            AutoLowLightCommandResult(
                action = AutoLowLightAction.DisableEnhancement,
                success = true,
                mode = AutoLowLightMode.None,
                exposureLevel = 0,
            ),
        )
        val exposure = strategy.onFrame(metrics(3_200L), validPose = true, analysisFps = 20f)
        assertEquals(AutoLowLightAction.SetExposure, exposure?.action)
        assertEquals(1, exposure?.exposureLevel)
    }

    @Test
    fun twoPerformanceWindows_triggerRollback() {
        val strategy = strategy()
        strategy.setCapability(AutoLowLightCapability(false, 0..4))
        strategy.onFrame(metrics(0L), validPose = true, analysisFps = 20f)
        val first = strategy.onFrame(metrics(1_001L), validPose = true, analysisFps = 20f)
        assertEquals(AutoLowLightAction.SetExposure, first?.action)
        strategy.onCommandResult(
            AutoLowLightCommandResult(
                action = AutoLowLightAction.SetExposure,
                success = true,
                mode = AutoLowLightMode.ExposureCompensation,
                exposureLevel = 1,
            ),
        )

        // Start the first observation window after the command has completed.
        val bright = { timestampMs: Long -> metrics(timestampMs, luma = 0.50f, darkRatio = 0.10f) }
        listOf(1_100L, 1_700L, 2_300L, 3_100L, 3_200L, 3_900L, 4_600L).forEach { time ->
            strategy.onFrame(bright(time), validPose = true, analysisFps = 10f)
        }
        val rollback = strategy.onFrame(bright(5_300L), validPose = true, analysisFps = 10f)

        assertEquals(AutoLowLightAction.Rollback, rollback?.action)
        assertEquals(AutoLowLightMode.None, rollback?.mode)
    }

    private fun strategy(): AutoLowLightStrategy = AutoLowLightStrategy(
        darkDurationMs = 1_000L,
        observationWindowMs = 2_000L,
        recoveryDurationMs = 10_000L,
        minObservationSamples = 3,
    ).also {
        it.setCapability(AutoLowLightCapability(true, 0..4))
    }

    private fun metrics(
        timestampMs: Long,
        luma: Float = 0.12f,
        darkRatio: Float = 0.8f,
    ): FrameLightMetrics {
        return FrameLightMetrics(
            timestampMs = timestampMs,
            meanLuma = luma,
            regionMeanLuma = luma,
            darkPixelRatio = darkRatio,
            overexposedRatio = 0f,
            region = LightMeasurementRegion.Person,
            sampleCount = 20,
        )
    }
}
