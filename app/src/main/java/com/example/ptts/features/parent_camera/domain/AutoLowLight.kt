package com.example.ptts.features.parent_camera.domain

import kotlin.math.max

/** The source used for a brightness sample. */
enum class LightMeasurementRegion {
    Person,
    Center,
}

/** Per-analysis-frame brightness information used by the automatic low-light controller. */
data class FrameLightMetrics(
    val timestampMs: Long,
    val meanLuma: Float,
    val regionMeanLuma: Float,
    val darkPixelRatio: Float,
    val overexposedRatio: Float,
    val region: LightMeasurementRegion,
    val regionCenterX: Float = 0.5f,
    val regionCenterY: Float = 0.5f,
    val regionWidth: Float = 0.5f,
    val regionHeight: Float = 0.7f,
    val regionAgeMs: Long = 0L,
    val sampleCount: Int = 0,
)

/** Camera capabilities discovered after CameraX binds the use cases. */
data class AutoLowLightCapability(
    val lowLightBoostSupported: Boolean,
    val exposureCompensationRange: IntRange,
) {
    val exposureCompensationSupported: Boolean
        get() = exposureCompensationRange.first < exposureCompensationRange.last
}

enum class AutoLowLightState {
    Normal,
    Adjusting,
    Enhanced,
    TooDark,
}

enum class AutoLowLightMode {
    None,
    LowLightBoost,
    ExposureCompensation,
}

enum class AutoLowLightAction {
    None,
    EnableLowLightBoost,
    SetExposure,
    DisableEnhancement,
    Rollback,
}

/** A pure command for the camera layer. The strategy itself never touches Android APIs. */
data class AutoLowLightDecision(
    val action: AutoLowLightAction,
    val state: AutoLowLightState,
    val mode: AutoLowLightMode = AutoLowLightMode.None,
    val exposureLevel: Int = 0,
    val reason: String = "",
)

/** Result of an asynchronous camera command. */
data class AutoLowLightCommandResult(
    val action: AutoLowLightAction,
    val success: Boolean,
    val mode: AutoLowLightMode,
    val exposureLevel: Int,
    val actualLowLightBoostEnabled: Boolean? = null,
    val error: String? = null,
)

/**
 * Debounced, capability-aware low-light policy.
 *
 * All time windows use the timestamps supplied by analysis frames. This keeps the policy stable
 * at 8 FPS, 20 FPS, and with irregular frame delivery. Camera commands are acknowledged through
 * [onCommandResult], so a failed level is never retried during the same dark period.
 */
class AutoLowLightStrategy(
    private val darkDurationMs: Long = 1_000L,
    private val observationWindowMs: Long = 2_000L,
    private val recoveryDurationMs: Long = 3_000L,
    private val minObservationSamples: Int = 4,
    private val maxExposureLevel: Int = 2,
    private val darkLumaThreshold: Float = 0.27f,
    private val darkPixelThreshold: Float = 0.58f,
    private val recoveredLumaThreshold: Float = 0.39f,
    private val recoveredDarkPixelThreshold: Float = 0.36f,
    private val performanceFpsRatio: Float = 0.80f,
    private val performancePoseDrop: Float = 0.10f,
) {
    private data class Sample(
        val timestampMs: Long,
        val luma: Float,
        val darkRatio: Float,
        val overexposedRatio: Float,
        val validPose: Boolean,
        val fps: Float,
    )

    private data class WindowStats(
        val sampleCount: Int,
        val validPoseRatio: Float,
        val averageFps: Float,
        val averageLuma: Float,
        val averageDarkRatio: Float,
        val averageOverexposedRatio: Float,
    )

    var capability: AutoLowLightCapability = AutoLowLightCapability(
        lowLightBoostSupported = false,
        exposureCompensationRange = 0..0,
    )
        private set

    var state: AutoLowLightState = AutoLowLightState.Normal
        private set
    var mode: AutoLowLightMode = AutoLowLightMode.None
        private set
    var exposureLevel: Int = 0
        private set
    var lastReason: String = ""
        private set

    private val recentSamples = ArrayDeque<Sample>()
    private val observationSamples = ArrayDeque<Sample>()
    private val failedExposureLevels = mutableSetOf<Int>()
    private var boostFailed = false
    private var darkSinceMs: Long? = null
    private var recoverySinceMs: Long? = null
    private var observationStartedMs: Long? = null
    private var baselineStats: WindowStats? = null
    private var preCommandStats: WindowStats? = null
    private var degradedWindows = 0
    private var pendingAction: AutoLowLightAction? = null
    private var pendingMode = AutoLowLightMode.None
    private var pendingExposureLevel = 0
    private var previousMode = AutoLowLightMode.None
    private var previousExposureLevel = 0
    private var fallbackAfterDisable = false

    fun setCapability(value: AutoLowLightCapability) {
        if (pendingAction != null || mode != AutoLowLightMode.None) {
            // A capability callback is emitted after every CameraX bind. Treat it as a fresh
            // control surface so a command belonging to the old camera cannot mutate the new one.
            pendingAction = null
            mode = AutoLowLightMode.None
            exposureLevel = 0
            state = AutoLowLightState.Normal
            observationStartedMs = null
            observationSamples.clear()
            baselineStats = null
            preCommandStats = null
            degradedWindows = 0
            darkSinceMs = null
            recoverySinceMs = null
        }
        capability = value
    }

    /** Called for every analysis result. A null return means no camera command is needed. */
    fun onFrame(
        metrics: FrameLightMetrics,
        validPose: Boolean,
        analysisFps: Float,
    ): AutoLowLightDecision? {
        val sample = Sample(
            timestampMs = metrics.timestampMs,
            luma = metrics.regionMeanLuma,
            darkRatio = metrics.darkPixelRatio,
            overexposedRatio = metrics.overexposedRatio,
            validPose = validPose,
            fps = analysisFps,
        )
        appendSample(recentSamples, sample, maxAgeMs = max(darkDurationMs, observationWindowMs) * 2L)
        if (pendingAction != null) return null

        val dark = isDark(metrics)
        if (dark) {
            darkSinceMs = darkSinceMs ?: metrics.timestampMs
            recoverySinceMs = null
        } else {
            darkSinceMs = null
            if (isRecovered(metrics) && (
                mode != AutoLowLightMode.None ||
                    state == AutoLowLightState.Adjusting ||
                    state == AutoLowLightState.TooDark
                )
            ) {
                recoverySinceMs = recoverySinceMs ?: metrics.timestampMs
            } else if (mode != AutoLowLightMode.None) {
                recoverySinceMs = null
            }
        }

        if (mode != AutoLowLightMode.None && state != AutoLowLightState.TooDark) {
            if (state == AutoLowLightState.Enhanced && observationStartedMs == null) {
                observationStartedMs = metrics.timestampMs
                observationSamples.clear()
            }
            observationStartedMs?.let { started ->
                appendSample(observationSamples, sample, maxAgeMs = observationWindowMs + 250L)
                if (metrics.timestampMs - started >= observationWindowMs &&
                    observationSamples.size >= minObservationSamples
                ) {
                    val decision = finishObservation(metrics, dark)
                    if (decision != null) return decision
                }
            }
            if (!dark && recoverySinceMs != null &&
                metrics.timestampMs - recoverySinceMs!! >= recoveryDurationMs
            ) {
                return issueRestore("环境亮度已恢复")
            }
            return null
        }

        if (state == AutoLowLightState.TooDark) {
            if (!dark && recoverySinceMs != null &&
                metrics.timestampMs - recoverySinceMs!! >= recoveryDurationMs
            ) {
                return issueRestore("环境亮度已恢复")
            }
            return null
        }

        val darkSince = darkSinceMs ?: return null
        if (!dark || metrics.timestampMs - darkSince < darkDurationMs) return null
        if (state == AutoLowLightState.Normal) {
            return issueNextEnhancement("持续暗光超过 ${darkDurationMs}ms")
        }
        return null
    }

    /** Delivers the result of one serialized camera operation. */
    fun onCommandResult(result: AutoLowLightCommandResult) {
        if (pendingAction != result.action) return
        pendingAction = null
        if (!result.success) {
            lastReason = result.error ?: "相机增强命令失败"
            val fallbackDisableFailed = result.action == AutoLowLightAction.DisableEnhancement &&
                fallbackAfterDisable
            when (result.action) {
                AutoLowLightAction.EnableLowLightBoost -> boostFailed = true
                AutoLowLightAction.SetExposure -> failedExposureLevels += result.exposureLevel
                AutoLowLightAction.Rollback,
                AutoLowLightAction.DisableEnhancement,
                AutoLowLightAction.None -> Unit
            }
            mode = AutoLowLightMode.None
            exposureLevel = 0
            state = if (fallbackDisableFailed) AutoLowLightState.TooDark else AutoLowLightState.Normal
            observationStartedMs = null
            observationSamples.clear()
            fallbackAfterDisable = false
            return
        }

        when (result.action) {
            AutoLowLightAction.EnableLowLightBoost -> {
                if (result.actualLowLightBoostEnabled != true) {
                    boostFailed = true
                    mode = AutoLowLightMode.None
                    state = AutoLowLightState.Normal
                    lastReason = "设备未实际启用低光增强"
                    return
                }
                mode = AutoLowLightMode.LowLightBoost
                exposureLevel = 0
                state = AutoLowLightState.Enhanced
                lastReason = "系统低光增强已生效"
            }
            AutoLowLightAction.SetExposure -> {
                mode = AutoLowLightMode.ExposureCompensation
                exposureLevel = result.exposureLevel
                state = AutoLowLightState.Enhanced
                lastReason = "曝光补偿 +${result.exposureLevel}档已生效"
            }
            AutoLowLightAction.Rollback -> {
                mode = result.mode
                exposureLevel = result.exposureLevel
                state = if (mode == AutoLowLightMode.None) {
                    if (isCurrentlyDark()) AutoLowLightState.TooDark else AutoLowLightState.Normal
                } else {
                    AutoLowLightState.Enhanced
                }
                lastReason = "增强效果影响分析，已回退到上一档"
            }
            AutoLowLightAction.DisableEnhancement -> {
                val wasFallback = fallbackAfterDisable
                mode = AutoLowLightMode.None
                exposureLevel = 0
                state = if (wasFallback) {
                    AutoLowLightState.Normal
                } else if (isCurrentlyDark()) {
                    AutoLowLightState.TooDark
                } else {
                    AutoLowLightState.Normal
                }
                lastReason = if (wasFallback) {
                    "系统增强效果不足，准备使用曝光补偿"
                } else {
                    "环境亮度已恢复，已恢复原始曝光"
                }
                fallbackAfterDisable = false
                if (!wasFallback) {
                    failedExposureLevels.clear()
                    boostFailed = false
                    darkSinceMs = null
                }
            }
            AutoLowLightAction.None -> Unit
        }
        observationStartedMs = null
        observationSamples.clear()
        degradedWindows = 0
        baselineStats = null
    }

    fun reset() {
        state = AutoLowLightState.Normal
        mode = AutoLowLightMode.None
        exposureLevel = 0
        lastReason = ""
        recentSamples.clear()
        observationSamples.clear()
        failedExposureLevels.clear()
        boostFailed = false
        darkSinceMs = null
        recoverySinceMs = null
        observationStartedMs = null
        baselineStats = null
        preCommandStats = null
        degradedWindows = 0
        pendingAction = null
        pendingMode = AutoLowLightMode.None
        pendingExposureLevel = 0
        previousMode = AutoLowLightMode.None
        previousExposureLevel = 0
        fallbackAfterDisable = false
    }

    private fun finishObservation(
        metrics: FrameLightMetrics,
        dark: Boolean,
    ): AutoLowLightDecision? {
        val baselineCandidate = baselineStats ?: preCommandStats
        val after = stats(observationSamples)
        if (baselineCandidate == null || after == null || after.sampleCount < minObservationSamples) {
            // Without a valid baseline, more exposure is speculation. At this point the user
            // gets an actionable hint instead of an endlessly increasing exposure loop.
            state = AutoLowLightState.TooDark
            lastReason = "没有足够有效姿态比较增强效果"
            observationStartedMs = null
            observationSamples.clear()
            return null
        }
        val baseline = baselineCandidate
        val afterStats = after
        if (baseline.validPoseRatio < 0.01f && afterStats.validPoseRatio < 0.01f) {
            state = AutoLowLightState.TooDark
            lastReason = "没有有效姿态可比较增强效果"
            observationStartedMs = null
            observationSamples.clear()
            return null
        }

        val fpsDegraded = baseline.averageFps > 0f &&
            afterStats.averageFps < baseline.averageFps * performanceFpsRatio
        val poseDegraded = afterStats.validPoseRatio < baseline.validPoseRatio - performancePoseDrop
        val overexposed = afterStats.averageOverexposedRatio >= 0.25f &&
            afterStats.averageOverexposedRatio > baseline.averageOverexposedRatio + 0.10f
        if (fpsDegraded || poseDegraded || overexposed) {
            degradedWindows += 1
        } else {
            degradedWindows = 0
        }
        if (degradedWindows >= 2) {
            degradedWindows = 0
            return issueRollback("连续两个观察窗口性能下降")
        }
        if (fpsDegraded || poseDegraded || overexposed) {
            // Hold this level for the second observation window. Raising exposure while the
            // analyzer is already struggling would amplify the very failure we are measuring.
            observationStartedMs = null
            observationSamples.clear()
            baselineStats = null
            return null
        }

        val lumaImproved = !overexposed && (
            afterStats.averageLuma >= baseline.averageLuma + 0.025f ||
                afterStats.averageDarkRatio <= baseline.averageDarkRatio - 0.06f
            )
        observationStartedMs = null
        observationSamples.clear()
        baselineStats = null
        if (lumaImproved && !dark) {
            return null
        }

        if (!dark) return null
        if (mode == AutoLowLightMode.LowLightBoost) {
            // Low-light boost and exposure compensation are mutually exclusive. Always turn the
            // system feature off before asking the camera for an exposure step.
            boostFailed = true
            return issueDisable("系统增强效果不足，准备使用曝光补偿")
        }
        if (!lumaImproved) {
            return issueNextEnhancement("当前档位提升不足")
        }
        if (mode == AutoLowLightMode.ExposureCompensation && exposureLevel < maxExposureLevel) {
            return issueNextEnhancement("仍偏暗，继续观察下一档")
        }
        state = AutoLowLightState.TooDark
        lastReason = "已达到增强上限，仍缺少有效姿态"
        return null
    }

    private fun issueNextEnhancement(reason: String): AutoLowLightDecision? {
        if (capability.lowLightBoostSupported && !boostFailed && mode == AutoLowLightMode.None) {
            return issue(
                action = AutoLowLightAction.EnableLowLightBoost,
                mode = AutoLowLightMode.LowLightBoost,
                exposureLevel = 0,
                reason = reason,
            )
        }
        if (!capability.exposureCompensationSupported) {
            state = AutoLowLightState.TooDark
            lastReason = "设备不支持低光增强或曝光补偿"
            return null
        }
        val nextLevel = (exposureLevel + 1..maxExposureLevel)
            .firstOrNull { it !in failedExposureLevels }
            ?: run {
                state = AutoLowLightState.TooDark
                lastReason = "已达到曝光补偿上限"
                return null
            }
        previousMode = mode
        previousExposureLevel = exposureLevel
        return issue(
            action = AutoLowLightAction.SetExposure,
            mode = AutoLowLightMode.ExposureCompensation,
            exposureLevel = nextLevel,
            reason = reason,
        )
    }

    private fun issueRollback(reason: String): AutoLowLightDecision {
        pendingAction = AutoLowLightAction.Rollback
        pendingMode = previousMode
        pendingExposureLevel = previousExposureLevel
        preCommandStats = stats(recentSamples)
        baselineStats = preCommandStats
        observationStartedMs = null
        observationSamples.clear()
        state = AutoLowLightState.Adjusting
        lastReason = reason
        return AutoLowLightDecision(
            action = AutoLowLightAction.Rollback,
            state = AutoLowLightState.Adjusting,
            mode = previousMode,
            exposureLevel = previousExposureLevel,
            reason = reason,
        )
    }

    private fun issueDisable(reason: String): AutoLowLightDecision {
        previousMode = mode
        previousExposureLevel = exposureLevel
        fallbackAfterDisable = mode == AutoLowLightMode.LowLightBoost && boostFailed
        return issue(
            action = AutoLowLightAction.DisableEnhancement,
            mode = AutoLowLightMode.None,
            exposureLevel = 0,
            reason = reason,
        )
    }

    private fun issueRestore(reason: String): AutoLowLightDecision {
        previousMode = mode
        previousExposureLevel = exposureLevel
        return issue(
            action = AutoLowLightAction.DisableEnhancement,
            mode = AutoLowLightMode.None,
            exposureLevel = 0,
            reason = reason,
        )
    }

    private fun issue(
        action: AutoLowLightAction,
        mode: AutoLowLightMode,
        exposureLevel: Int,
        reason: String,
    ): AutoLowLightDecision {
        pendingAction = action
        pendingMode = mode
        pendingExposureLevel = exposureLevel
        preCommandStats = stats(recentSamples)
        baselineStats = preCommandStats
        observationStartedMs = null
        observationSamples.clear()
        state = AutoLowLightState.Adjusting
        lastReason = reason
        return AutoLowLightDecision(action, AutoLowLightState.Adjusting, mode, exposureLevel, reason)
    }

    private fun isDark(metrics: FrameLightMetrics): Boolean {
        if (metrics.overexposedRatio >= 0.25f) return false
        return metrics.regionMeanLuma < darkLumaThreshold || metrics.darkPixelRatio >= darkPixelThreshold
    }

    private fun isRecovered(metrics: FrameLightMetrics): Boolean {
        return metrics.regionMeanLuma >= recoveredLumaThreshold &&
            metrics.darkPixelRatio <= recoveredDarkPixelThreshold &&
            metrics.overexposedRatio < 0.25f
    }

    private fun isCurrentlyDark(): Boolean {
        val latest = recentSamples.lastOrNull() ?: return true
        if (latest.overexposedRatio >= 0.25f) return false
        return latest.luma < darkLumaThreshold || latest.darkRatio >= darkPixelThreshold
    }

    private fun appendSample(target: ArrayDeque<Sample>, sample: Sample, maxAgeMs: Long) {
        target.addLast(sample)
        val cutoff = sample.timestampMs - maxAgeMs
        while (target.firstOrNull()?.timestampMs?.let { it < cutoff } == true) target.removeFirst()
    }

    private fun stats(samples: Collection<Sample>): WindowStats? {
        if (samples.isEmpty()) return null
        val size = samples.size.toFloat()
        return WindowStats(
            sampleCount = samples.size,
            validPoseRatio = samples.count { it.validPose } / size,
            averageFps = samples.map { it.fps }.filter { it > 0f }.average().toFloatOrNull() ?: 0f,
            averageLuma = samples.map { it.luma }.average().toFloat(),
            averageDarkRatio = samples.map { it.darkRatio }.average().toFloat(),
            averageOverexposedRatio = samples.map { it.overexposedRatio }.average().toFloat(),
        )
    }

    private fun Double.toFloatOrNull(): Float? = if (isNaN()) null else toFloat()
}
