package com.example.ptts.features.parent_camera.domain

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

class JumpCounter(
    private val onLog: ((String) -> Unit)? = null,
    private val onDiagnostic: ((JumpDiagnostic) -> Unit)? = null,
) {
    private var confirmedCount = 0
    private var estimatedCount = 0
    private var phase = JumpPhase.Searching
    private var baselineBodyY: Float? = null
    private var baselineFootY: Float? = null
    private var jumpStartMs = 0L
    private var lastValidMs: Long? = null
    private var lastInputMs: Long? = null
    private var phaseStartMs = 0L
    private var jumpMaxLift = 0f
    private var jumpSampleCount = 0
    private var lastStableScale: Float? = null
    private var smoothedLift: Float? = null
    private var previousLift: Float? = null
    private var previousRawLift: Float? = null
    private var previousSampleMs: Long? = null
    private var lastFilterMs: Long? = null
    private var averageCycleMs: Float? = null
    private val cycleSamples = mutableListOf<Float>()
    private var lastConfirmedMs: Long? = null
    private var lastEventMs: Long? = null
    private var estimateWindowStartMs: Long? = null
    private var estimatedInCurrentGap = 0
    private val estimatedEventTimes = mutableListOf<Long>()
    private var adaptivePeakLift: Float? = null
    private var stableSinceMs: Long? = null
    private var recoveringSinceMs: Long? = null
    private var suspendedAfterLoss = false
    private var cameraRecoveryPending = false
    private var cameraStableSinceMs: Long? = null
    private var jumpHasPairedEvidence = false

    // Per-frame values retained for the bounded diagnostic stream. These are
    // deliberately reset at the beginning of each accepted frame so a lost
    // pose cannot make an old lift look like current evidence.
    private var diagnosticSampleIntervalMs: Long? = null
    private var diagnosticRawLift: Float? = null
    private var diagnosticSmoothedLift: Float? = null
    private var diagnosticPeakLift: Float? = null
    private var diagnosticJumpDurationMs: Long? = null
    private var diagnosticRejectionReason: String? = null

    private var calibrationBodyY: Float? = null
    private var calibrationFootY: Float? = null
    private var calibrationScale: Float? = null
    private var calibrationStartedMs: Long? = null
    private var calibrationLastMs: Long? = null
    private var calibrationSampleCount = 0
    private var calibrationReady = false

    private val count: Int
        get() = confirmedCount + estimatedCount

    fun reset(clearCalibration: Boolean = true) {
        resetRuntime()
        if (clearCalibration) {
            calibrationBodyY = null
            calibrationFootY = null
            calibrationScale = null
            calibrationStartedMs = null
            calibrationLastMs = null
            calibrationSampleCount = 0
            calibrationReady = false
        }
        log("计数器已重置")
    }

    /** Collects stable standing samples while the camera is in framing/countdown. */
    fun calibrate(frame: PoseFrame) {
        if (lastInputMs != null && frame.timestampMs <= lastInputMs!!) return
        val sample = frame.toSample() ?: return
        if (sample.quality == SampleQuality.Unusable) return
        if (calibrationStartedMs == null) calibrationStartedMs = frame.timestampMs
        calibrationLastMs = frame.timestampMs
        calibrationSampleCount += 1
        sample.bodyY?.let { calibrationBodyY = smooth(calibrationBodyY ?: it, it, CalibrationSmoothing) }
        sample.footY?.let { calibrationFootY = smooth(calibrationFootY ?: it, it, CalibrationSmoothing) }
        calibrationScale = smooth(calibrationScale ?: sample.scale, sample.scale, CalibrationSmoothing)
        calibrationReady = calibrationSampleCount >= MinCalibrationSamples &&
            frame.timestampMs - (calibrationStartedMs ?: frame.timestampMs) >= CalibrationWindowMs
    }

    /** Starts a recording session, preserving the standing calibration collected before recording. */
    fun startSession() {
        resetRuntime()
        if (calibrationReady) {
            baselineBodyY = calibrationBodyY
            baselineFootY = calibrationFootY
            lastStableScale = calibrationScale
            phase = JumpPhase.Grounded
            stableSinceMs = (calibrationLastMs ?: 0L) - MinStableBeforeCountingMs
        }
    }

    fun isCalibrationReady(): Boolean = calibrationReady

    private fun resetRuntime() {
        confirmedCount = 0
        estimatedCount = 0
        phase = JumpPhase.Searching
        baselineBodyY = null
        baselineFootY = null
        jumpStartMs = 0L
        lastValidMs = null
        lastInputMs = null
        phaseStartMs = 0L
        jumpMaxLift = 0f
        jumpSampleCount = 0
        lastStableScale = null
        smoothedLift = null
        previousLift = null
        previousRawLift = null
        previousSampleMs = null
        lastFilterMs = null
        averageCycleMs = null
        cycleSamples.clear()
        lastConfirmedMs = null
        lastEventMs = null
        estimateWindowStartMs = null
        estimatedInCurrentGap = 0
        estimatedEventTimes.clear()
        adaptivePeakLift = null
        stableSinceMs = null
        recoveringSinceMs = null
        suspendedAfterLoss = false
        cameraRecoveryPending = false
        cameraStableSinceMs = null
        jumpHasPairedEvidence = false
        diagnosticSampleIntervalMs = null
        diagnosticRawLift = null
        diagnosticSmoothedLift = null
        diagnosticPeakLift = null
        diagnosticJumpDurationMs = null
        diagnosticRejectionReason = null
    }

    fun accept(frame: PoseFrame): JumpCounterResult {
        log("accept: timestamp=${frame.timestampMs} landmarks=${frame.landmarks.size}")
        if (lastInputMs != null && frame.timestampMs <= lastInputMs!!) {
            log("忽略重复或倒序时间戳: ${frame.timestampMs}")
            diagnosticSampleIntervalMs = null
            diagnosticRawLift = null
            diagnosticSmoothedLift = null
            diagnosticPeakLift = null
            diagnosticJumpDurationMs = null
            diagnosticRejectionReason = null
            return result(TrackingQuality.PartialBody, countedThisFrame = false)
        }
        diagnosticSampleIntervalMs = lastInputMs?.let { frame.timestampMs - it }
        diagnosticRawLift = null
        diagnosticSmoothedLift = null
        diagnosticPeakLift = null
        diagnosticJumpDurationMs = null
        diagnosticRejectionReason = null
        lastInputMs = frame.timestampMs
        val cameraUnstable = frame.cameraMotion.available && !frame.cameraMotion.reliable &&
            frame.cameraMotion.magnitude >= CameraMotionRecoveryThreshold
        if (cameraUnstable) {
            cameraRecoveryPending = true
            cameraStableSinceMs = null
            resetJumpTracking()
            phase = JumpPhase.Grounded
            recoveringSinceMs = frame.timestampMs
        }
        val sample = frame.toSample()
        if (sample == null || sample.quality == SampleQuality.Unusable) {
            cameraStableSinceMs = null
            val estimated = handleLostFrame(frame.timestampMs)
            return result(
                TrackingQuality.PartialBody,
                countedThisFrame = false,
                estimatedThisFrame = estimated,
            )
        }

        if (cameraRecoveryPending) {
            if (lastValidMs?.let { frame.timestampMs - it > MaxTransientLostMs } == true) {
                cameraStableSinceMs = null
            }
            lastValidMs = frame.timestampMs
            return recoverAfterCameraMotion(frame.timestampMs, sample, cameraUnstable)
        }

        val gapSinceValid = lastValidMs?.let { frame.timestampMs - it } ?: 0L
        if (gapSinceValid > MaxTransientLostMs && recoveringSinceMs == null &&
            phase != JumpPhase.Grounded && phase != JumpPhase.Searching
        ) {
            handleLostFrame(frame.timestampMs)
        }

        lastValidMs = frame.timestampMs
        val wasRecovering = recoveringSinceMs != null
        updateStableScale(sample)

        val stableForMs = updateStableWindow(frame.timestampMs, sample.quality)
        val recovering = recoveringSinceMs != null
        val requiredStableMs = if (recovering) RecoveryStableBeforeCountingMs else MinStableBeforeCountingMs
        val bodyBaseline = baselineBodyY
        val footBaseline = baselineFootY
        if (bodyBaseline == null && footBaseline == null) {
            baselineBodyY = sample.bodyY
            baselineFootY = sample.footY
            phase = JumpPhase.Grounded
            phaseStartMs = frame.timestampMs
            smoothedLift = 0f
            previousLift = 0f
            previousRawLift = 0f
            previousSampleMs = frame.timestampMs
            lastFilterMs = frame.timestampMs
            stableSinceMs = frame.timestampMs - MinStableBeforeCountingMs
            recoveringSinceMs = null
            log(
                "建立基线: body=${sample.bodyY?.fmt} foot=${sample.footY?.fmt} " +
                    "scale=${sample.scale.fmt} quality=${sample.quality}",
            )
            return result(sample.trackingQuality, countedThisFrame = false)
        }

        // A grounded person can legitimately reappear at a new image position
        // after a short occlusion or camera reposition. Re-anchor before using
        // the displacement as jump evidence.
        if (wasRecovering && phase == JumpPhase.Grounded) {
            val reconciledEstimate = findReconciliableEstimate(frame.timestampMs)
            if (reconciledEstimate != null) {
                estimatedEventTimes.remove(reconciledEstimate)
                estimatedCount = (estimatedCount - 1).coerceAtLeast(0)
                confirmedCount += 1
                lastConfirmedMs = frame.timestampMs
                lastEventMs = frame.timestampMs
                log("恢复帧匹配节奏估算: timestamp=${frame.timestampMs}")
            }
            baselineBodyY = sample.bodyY ?: baselineBodyY
            baselineFootY = sample.footY ?: baselineFootY
            stableSinceMs = frame.timestampMs - MinStableBeforeCountingMs
            smoothedLift = 0f
            previousLift = 0f
            previousRawLift = 0f
            previousSampleMs = frame.timestampMs
            lastFilterMs = frame.timestampMs
            suspendedAfterLoss = false
            recoveringSinceMs = null
            estimateWindowStartMs = null
            estimatedInCurrentGap = 0
            log("姿态恢复后重新建立站立基线")
            return result(
                sample.trackingQuality,
                countedThisFrame = reconciledEstimate != null,
                recovering = false,
            )
        }

        val features = sample.toFeatures(
            bodyBaseline = bodyBaseline,
            footBaseline = footBaseline,
        )
        diagnosticRawLift = features.combinedLift
        val filteredLift = filterLift(features.combinedLift, frame.timestampMs)
        diagnosticSmoothedLift = filteredLift
        val landingLift = minOf(filteredLift, features.combinedLift)
        val velocity = if (suspendedAfterLoss) 0f else velocityPerSecond(frame.timestampMs, filteredLift)
        val thresholds = thresholds()

        val oldPhase = phase
        var counted = false
        log(
            "sample: phase=$phase lift=${filteredLift.fmt} raw=${features.combinedLift.fmt} " +
                "body=${features.bodyLift?.fmt} foot=${features.footLift?.fmt} " +
                "velocity=${velocity.fmt} quality=${sample.quality}",
        )

        val candidateRecovery = wasRecovering && phase != JumpPhase.Grounded && phase != JumpPhase.Searching
        val newPhase = if (stableForMs < requiredStableMs && !candidateRecovery) {
            updateGroundBaseline(sample, filteredLift, allowFastUpdate = true)
            JumpPhase.Grounded
        } else {
            if (recovering) {
                recoveringSinceMs = null
                log("姿态恢复稳定: stableFor=${stableForMs}ms")
            }
            nextPhase(
                timestampMs = frame.timestampMs,
                sample = sample,
                lift = filteredLift,
                rawLift = features.combinedLift,
                triggerLift = maxOf(filteredLift, features.combinedLift),
                landingLift = landingLift,
                velocity = velocity,
                thresholds = thresholds,
                pairedEvidence = hasPairedLiftEvidence(features),
                onCount = { reason ->
                    counted = maybeCountLanding(
                        timestampMs = frame.timestampMs,
                        sample = sample,
                        lift = landingLift,
                        thresholds = thresholds,
                        reason = reason,
                    )
                },
            )
        }

        if (newPhase != phase) {
            phaseStartMs = frame.timestampMs
            log("phase 转换: $phase -> $newPhase")
        }
        phase = newPhase
        previousLift = filteredLift
        previousRawLift = features.combinedLift
        previousSampleMs = frame.timestampMs
        suspendedAfterLoss = false
        if (wasRecovering) {
            recoveringSinceMs = null
            estimateWindowStartMs = null
            estimatedInCurrentGap = 0
        }

        if (oldPhase != phase || phase == JumpPhase.Airborne || phase == JumpPhase.Rising) {
            log(
                "帧@${frame.timestampMs}: phase=$phase lift=${filteredLift.fmt} " +
                    "threshold=${thresholds.rising.fmt}/${thresholds.validPeak.fmt}",
            )
        }

        return result(sample.trackingQuality, counted, recovering = recoveringSinceMs != null)
    }

    private fun recoverAfterCameraMotion(
        timestampMs: Long,
        sample: PoseSample,
        cameraUnstable: Boolean,
    ): JumpCounterResult {
        // 移动期间丢弃旧跳跃；稳定窗口内取最低站位，避免将腾空位置当作地面。
        val firstStableSample = cameraStableSinceMs == null
        baselineBodyY = sample.bodyY?.let {
            if (cameraUnstable || firstStableSample) it else maxOf(baselineBodyY ?: it, it)
        }
        baselineFootY = sample.footY?.let {
            if (cameraUnstable || firstStableSample) it else maxOf(baselineFootY ?: it, it)
        }
        lastStableScale = sample.scale
        resetJumpTracking()
        previousSampleMs = null
        previousRawLift = null
        phase = JumpPhase.Grounded
        phaseStartMs = timestampMs
        estimateWindowStartMs = null
        estimatedInCurrentGap = 0
        suspendedAfterLoss = false
        if (!cameraUnstable) {
            val stableStart = cameraStableSinceMs ?: timestampMs.also { cameraStableSinceMs = it }
            if (timestampMs - stableStart >= RecoveryStableBeforeCountingMs) {
                cameraRecoveryPending = false
                cameraStableSinceMs = null
                recoveringSinceMs = null
                // 稳定窗口已完成，下一帧直接检测起跳，不再叠加启动等待。
                stableSinceMs = timestampMs - MinStableBeforeCountingMs
                smoothedLift = sample.toFeatures(baselineBodyY, baselineFootY).combinedLift
                previousLift = smoothedLift
                previousRawLift = smoothedLift
                previousSampleMs = timestampMs
                lastFilterMs = timestampMs
            }
        }
        return result(sample.trackingQuality, countedThisFrame = false, recovering = cameraRecoveryPending)
    }

    private fun nextPhase(
        timestampMs: Long,
        sample: PoseSample,
        lift: Float,
        rawLift: Float,
        triggerLift: Float,
        landingLift: Float,
        velocity: Float,
        thresholds: JumpThresholds,
        pairedEvidence: Boolean,
        onCount: (String) -> Unit,
    ): JumpPhase {
        return when (phase) {
            JumpPhase.Searching -> JumpPhase.Grounded
            JumpPhase.Grounded -> {
                if (triggerLift >= thresholds.rising && velocity >= MinRisingVelocity) {
                    jumpStartMs = estimateJumpStartMs(
                        timestampMs = timestampMs,
                        threshold = thresholds.rising,
                        currentLift = triggerLift,
                    )
                    jumpMaxLift = rawLift
                    diagnosticPeakLift = jumpMaxLift
                    jumpSampleCount = 1
                    jumpHasPairedEvidence = pairedEvidence
                    log(
                        "起跳: lift=${triggerLift.fmt} raw=${rawLift.fmt} " +
                            "start=${jumpStartMs} velocity=${velocity.fmt}",
                    )
                    JumpPhase.Rising
                } else {
                    // Only a grounded sample may move the standing reference.
                    // Once the threshold is crossed, the reference is frozen
                    // until landing is resolved.
                    updateGroundBaseline(sample, lift, allowFastUpdate = false)
                    JumpPhase.Grounded
                }
            }
            JumpPhase.Rising -> {
                // Capture the sample before checking for an immediate landing;
                // a sparse stream can put the only visible peak on the frame
                // that already falls below the ground threshold after filtering.
                updateJumpPeak(rawLift)
                when {
                    landingLift <= thresholds.ground -> {
                        onCount("低帧率回落")
                        JumpPhase.Grounded
                    }
                    lift >= thresholds.airborne -> {
                        jumpSampleCount += 1
                        jumpHasPairedEvidence = jumpHasPairedEvidence || pairedEvidence
                        log("进入空中: lift=${lift.fmt} velocity=${velocity.fmt}")
                        JumpPhase.Airborne
                    }
                    timestampMs - phaseStartMs > MaxRisingMs -> {
                        log("Rising 超时(${timestampMs - phaseStartMs}ms)，重置到 Grounded")
                        resetJumpTracking()
                        updateGroundBaseline(sample, lift, allowFastUpdate = true)
                        JumpPhase.Grounded
                    }
                    else -> {
                        jumpSampleCount += 1
                        jumpHasPairedEvidence = jumpHasPairedEvidence || pairedEvidence
                        JumpPhase.Rising
                    }
                }
            }
            JumpPhase.Airborne -> {
                recordJumpSample(rawLift, pairedEvidence)
                when {
                    landingLift <= thresholds.ground -> {
                        onCount("空中直接落地")
                        JumpPhase.Grounded
                    }
                    landingLift <= thresholds.landing && velocity <= LandingVelocity -> {
                        log("开始落地: lift=${lift.fmt} velocity=${velocity.fmt}")
                        JumpPhase.Landing
                    }
                    timestampMs - phaseStartMs > MaxAirborneMs -> {
                        log("Airborne 超时(${timestampMs - phaseStartMs}ms)，重置到 Grounded")
                        resetJumpTracking()
                        updateGroundBaseline(sample, lift, allowFastUpdate = true)
                        JumpPhase.Grounded
                    }
                    else -> JumpPhase.Airborne
                }
            }
            JumpPhase.Landing -> {
                recordJumpSample(rawLift, pairedEvidence)
                if (landingLift <= thresholds.ground) {
                    onCount("落地")
                    JumpPhase.Grounded
                } else if (timestampMs - phaseStartMs > MaxLandingMs) {
                    log("Landing 超时(${timestampMs - phaseStartMs}ms)，重置到 Grounded")
                    resetJumpTracking()
                    updateGroundBaseline(sample, lift, allowFastUpdate = true)
                    JumpPhase.Grounded
                } else {
                    JumpPhase.Landing
                }
            }
        }
    }

    private fun handleLostFrame(timestampMs: Long): Boolean {
        val lastValid = lastValidMs
        val lostMs = if (lastValid == null) 0L else (timestampMs - lastValid).coerceAtLeast(0L)
        if (lastValid == null || lostMs > MaxLostPoseMs) {
            resetJumpTracking()
            stableSinceMs = null
            smoothedLift = null
            previousLift = null
            previousSampleMs = null
            previousRawLift = null
            lastFilterMs = null
            log("姿态丢失过长(${lostMs}ms)，重置状态")
            baselineBodyY = null
            baselineFootY = null
            phase = JumpPhase.Searching
            recoveringSinceMs = null
            estimateWindowStartMs = null
            estimatedInCurrentGap = 0
            estimatedEventTimes.clear()
            return false
        } else if (lostMs > MaxTransientLostMs) {
            // A longer gap may still produce bounded cadence estimates, but
            // the old rising/airborne candidate is no longer trusted.
            if (estimateWindowStartMs == null) estimateWindowStartMs = timestampMs
            val estimateBefore = estimatedCount
            estimateDuringLoss(timestampMs)
            resetJumpTracking()
            smoothedLift = null
            previousLift = null
            previousSampleMs = null
            previousRawLift = null
            lastFilterMs = null
            phase = if (baselineBodyY != null || baselineFootY != null) {
                JumpPhase.Grounded
            } else {
                JumpPhase.Searching
            }
            stableSinceMs = null
            recoveringSinceMs = recoveringSinceMs ?: timestampMs
            suspendedAfterLoss = true
            log("姿态丢失超过短暂容错(${lostMs}ms)，丢弃当前跳跃候选")
            return estimatedCount > estimateBefore
        } else {
            if (estimateWindowStartMs == null) estimateWindowStartMs = timestampMs
            val estimateBefore = estimatedCount
            estimateDuringLoss(timestampMs)
            recoveringSinceMs = recoveringSinceMs ?: timestampMs
            suspendedAfterLoss = true
            log("帧丢弃: timestamp=$timestampMs, 已丢失=${lostMs}ms")
            return estimatedCount > estimateBefore
        }
    }

    private fun result(
        trackingQuality: TrackingQuality,
        countedThisFrame: Boolean,
        estimatedThisFrame: Boolean = false,
        recovering: Boolean = recoveringSinceMs != null,
    ): JumpCounterResult {
        val result = JumpCounterResult(
            count = count,
            phase = phase,
            trackingQuality = trackingQuality,
            countedThisFrame = countedThisFrame,
            confirmedCount = confirmedCount,
            estimatedCount = estimatedCount,
            estimatedThisFrame = estimatedThisFrame,
            recovering = recovering,
        )
        onDiagnostic?.invoke(
            JumpDiagnostic(
                timestampMs = lastInputMs ?: 0L,
                phase = phase,
                count = count,
                confirmedCount = confirmedCount,
                estimatedCount = estimatedCount,
                recovering = recovering,
                event = when {
                    estimatedThisFrame -> "estimated"
                    countedThisFrame -> "counted"
                    else -> "sample"
                },
                sampleIntervalMs = diagnosticSampleIntervalMs,
                rawLift = diagnosticRawLift,
                smoothedLift = diagnosticSmoothedLift,
                peakLift = diagnosticPeakLift,
                jumpDurationMs = diagnosticJumpDurationMs,
                rejectionReason = diagnosticRejectionReason,
            ),
        )
        return result
    }

    private fun updateGroundBaseline(
        sample: PoseSample,
        lift: Float,
        allowFastUpdate: Boolean,
    ) {
        val factor = when {
            allowFastUpdate -> RecoveryBaselineSmoothing
            lift <= GroundedBaselineLift -> GroundedBaselineSmoothing
            else -> AirborneBaselineSmoothing
        }
        sample.bodyY?.let { bodyY ->
            baselineBodyY = smooth(baselineBodyY ?: bodyY, bodyY, factor)
        }
        sample.footY?.let { footY ->
            baselineFootY = smooth(baselineFootY ?: footY, footY, factor)
        }
    }

    private fun maybeCountLanding(
        timestampMs: Long,
        sample: PoseSample,
        lift: Float,
        thresholds: JumpThresholds,
        reason: String,
    ): Boolean {
        val airTimeMs = timestampMs - jumpStartMs
        diagnosticJumpDurationMs = airTimeMs
        diagnosticPeakLift = jumpMaxLift
        val reconciledEstimate = findReconciliableEstimate(timestampMs)
        val timeSinceLastCount = lastEventMs?.let { timestampMs - it } ?: Long.MAX_VALUE
        val canCountAgain = reconciledEstimate != null || timeSinceLastCount >= minRefractoryMs()
        val lowFrameEligible = jumpSampleCount >= 1 && jumpMaxLift >= thresholds.validPeak && jumpHasPairedEvidence
        val isStandardJump = jumpMaxLift >= thresholds.validPeak &&
            (jumpSampleCount >= MinStandardJumpSamples || lowFrameEligible)
        val isWeakJump = jumpMaxLift >= thresholds.weakPeak && jumpSampleCount >= MinWeakJumpSamples
        val enoughLift = isStandardJump || isWeakJump
        val minAirTime = if (isStandardJump) MinAirTimeMs else MinWeakAirTimeMs
        val reasonableAirTime = airTimeMs in minAirTime..MaxJumpDurationMs
        val counted = enoughLift && reasonableAirTime && canCountAgain

        if (counted) {
            if (reconciledEstimate != null) {
                estimatedEventTimes.remove(reconciledEstimate)
                estimatedCount = (estimatedCount - 1).coerceAtLeast(0)
            } else {
                confirmedCount += 1
            }
            updateCadence(timestampMs)
            adaptivePeakLift = adaptivePeakLift
                ?.let { smooth(it, jumpMaxLift, AdaptivePeakSmoothing) }
                ?: jumpMaxLift
            lastEventMs = timestampMs
            lastConfirmedMs = timestampMs
            log(
                "计数成功($reason)! count=$count, airTime=${airTimeMs}ms, " +
                    "maxLift=${jumpMaxLift.fmt}, samples=$jumpSampleCount, sinceLast=${timeSinceLastCount}ms",
            )
        } else {
            diagnosticRejectionReason = when {
                !enoughLift -> {
                    log(
                        "拒绝计数($reason): 跳跃幅度不足(maxLift=${jumpMaxLift.fmt} < " +
                            "${thresholds.weakPeak.fmt}), samples=$jumpSampleCount, lift=${lift.fmt}",
                    )
                    "insufficient_lift"
                }
                !reasonableAirTime -> {
                    log(
                        "拒绝计数($reason): 持续时间不合理(${airTimeMs}ms, " +
                            "range=${minAirTime}..${MaxJumpDurationMs}ms), lift=${lift.fmt}",
                    )
                    "air_time"
                }
                !canCountAgain -> {
                    log("拒绝计数($reason): 自适应不应期内(${timeSinceLastCount}ms)")
                    "refractory"
                }
                else -> "unknown"
            }
        }

        updateGroundBaseline(sample, lift, allowFastUpdate = counted)
        resetJumpTracking()
        return counted
    }

    private fun recordJumpSample(rawLift: Float) {
        // Use the filtered value for phase transitions, but retain the raw
        // displacement peak for the final evidence check. A real peak can be
        // attenuated by the low-pass filter when the camera samples it once.
        updateJumpPeak(rawLift)
        jumpSampleCount += 1
    }

    private fun updateJumpPeak(rawLift: Float) {
        jumpMaxLift = maxOf(jumpMaxLift, rawLift)
        diagnosticPeakLift = jumpMaxLift
    }

    private fun recordJumpSample(rawLift: Float, pairedEvidence: Boolean) {
        recordJumpSample(rawLift)
        jumpHasPairedEvidence = jumpHasPairedEvidence || pairedEvidence
    }

    private fun estimateJumpStartMs(
        timestampMs: Long,
        threshold: Float,
        currentLift: Float,
    ): Long {
        val previousMs = previousSampleMs
        val previousEffectiveLift = maxOf(previousLift ?: 0f, previousRawLift ?: 0f)
        if (previousMs == null || previousMs >= timestampMs || suspendedAfterLoss) return timestampMs
        val intervalMs = timestampMs - previousMs
        if (intervalMs > MaxInterpolationGapMs || previousEffectiveLift >= threshold || currentLift <= threshold) {
            return timestampMs
        }
        val denominator = currentLift - previousEffectiveLift
        if (denominator <= 0f) return timestampMs
        val ratio = ((threshold - previousEffectiveLift) / denominator).coerceIn(0f, 1f)
        return previousMs + (intervalMs * ratio).toLong()
    }

    private fun resetJumpTracking() {
        jumpMaxLift = 0f
        jumpSampleCount = 0
        smoothedLift = null
        previousLift = null
        previousRawLift = null
        lastFilterMs = null
        jumpHasPairedEvidence = false
    }

    private fun filterLift(rawLift: Float, timestampMs: Long): Float {
        val previous = smoothedLift
        val filtered = if (previous == null) {
            rawLift
        } else {
            val deltaMs = (timestampMs - (lastFilterMs ?: timestampMs)).coerceIn(1L, 200L)
            val normalized = deltaMs / NominalFrameMs.toFloat()
            val factor = 1f - (1f - LiftSmoothing).pow(normalized)
            smooth(previous, rawLift, factor.coerceIn(0.15f, 0.95f))
        }
        smoothedLift = filtered
        lastFilterMs = timestampMs
        return filtered
    }

    private fun velocityPerSecond(
        timestampMs: Long,
        lift: Float,
    ): Float {
        val previousMs = previousSampleMs ?: return 0f
        val previous = previousLift ?: return 0f
        val deltaMs = (timestampMs - previousMs).coerceAtLeast(1L)
        return (lift - previous) * 1000f / deltaMs
    }

    private fun thresholds(): JumpThresholds {
        val learnedPeak = adaptivePeakLift
        val learnedWeakPeak = learnedPeak?.let { (it * LearnedWeakPeakRatio).coerceIn(MinWeakPeak, WeakJumpThreshold) }
        val learnedValidPeak = learnedPeak?.let { (it * LearnedValidPeakRatio).coerceIn(MinValidPeak, ValidJumpThreshold) }
        val weakPeak = learnedWeakPeak ?: WeakJumpThreshold
        val validPeak = learnedValidPeak ?: ValidJumpThreshold
        return JumpThresholds(
            rising = minOf(RisingThreshold, weakPeak * RisingThresholdRatio),
            airborne = minOf(AirborneThreshold, validPeak * AirborneThresholdRatio),
            landing = minOf(LandingThreshold, weakPeak * LandingThresholdRatio),
            ground = GroundThreshold,
            weakPeak = weakPeak,
            validPeak = validPeak,
        )
    }

    private fun minRefractoryMs(): Long {
        val cadence = averageCycleMs ?: return BaseRefractoryMs
        return (cadence * RefractoryCadenceRatio)
            .toLong()
            .coerceIn(MinAdaptiveRefractoryMs, BaseRefractoryMs)
    }

    private fun updateCadence(timestampMs: Long) {
        val previous = lastConfirmedMs
        if (previous == null || timestampMs <= previous) {
            return
        }
        val cycleMs = (timestampMs - previous).coerceIn(MinCycleMs, MaxCycleMs).toFloat()
        cycleSamples += cycleMs
        if (cycleSamples.size > MaxCadenceSamples) cycleSamples.removeAt(0)
        averageCycleMs = averageCycleMs
            ?.let { smooth(it, cycleMs, CadenceSmoothing) }
            ?: cycleMs
    }

    private fun updateStableWindow(
        timestampMs: Long,
        quality: SampleQuality,
    ): Long {
        if (quality == SampleQuality.Unusable) {
            return 0L
        }
        val start = stableSinceMs ?: timestampMs.also { stableSinceMs = it }
        return timestampMs - start
    }

    private fun updateStableScale(sample: PoseSample) {
        if (sample.measuredScaleReliable) {
            lastStableScale = lastStableScale
                ?.let { smooth(it, sample.scale, ScaleSmoothing) }
                ?: sample.scale
        }
    }

    private fun cadenceStable(): Boolean {
        if (confirmedCount < MinConfirmedForEstimation || cycleSamples.size < MinCadenceSamples) return false
        val mean = cycleSamples.average().toFloat()
        if (mean <= 0f) return false
        val variance = cycleSamples
            .map { (it - mean).toDouble().pow(2.0) }
            .average()
        return sqrt(variance).toFloat() / mean <= MaxCadenceCoefficientOfVariation
    }

    private fun estimateDuringLoss(timestampMs: Long) {
        if (phase == JumpPhase.Grounded || phase == JumpPhase.Searching || !cadenceStable()) return
        val cycle = averageCycleMs ?: return
        val windowStart = estimateWindowStartMs ?: timestampMs
        if (timestampMs - windowStart > MaxEstimationWindowMs) return
        var lastEvent = lastEventMs ?: return
        while (estimatedInCurrentGap < MaxEstimatedPerGap &&
            timestampMs - lastEvent >= (cycle * EstimationDueRatio)
        ) {
            lastEvent += cycle.toLong()
            estimatedEventTimes += lastEvent
            estimatedCount += 1
            estimatedInCurrentGap += 1
            lastEventMs = lastEvent
            log("节奏估算一次: timestamp=$lastEvent count=$count")
        }
    }

    private fun findReconciliableEstimate(timestampMs: Long): Long? {
        val cycle = averageCycleMs ?: return null
        val window = minOf(MaxReconcileWindowMs, (cycle / 2f).toLong())
        return estimatedEventTimes.minByOrNull { abs(it - timestampMs) }
            ?.takeIf { abs(it - timestampMs) <= window }
    }

    private fun PoseFrame.toSample(): PoseSample? {
        val points = correctedLandmarks()
        val leftShoulder = points.required(BodyLandmark.LeftShoulder)
        val rightShoulder = points.required(BodyLandmark.RightShoulder)
        val leftHip = points.required(BodyLandmark.LeftHip)
        val rightHip = points.required(BodyLandmark.RightHip)
        val leftAnkle = points[BodyLandmark.LeftAnkle]
        val rightAnkle = points[BodyLandmark.RightAnkle]
        val leftHeel = points[BodyLandmark.LeftHeel]
        val rightHeel = points[BodyLandmark.RightHeel]

        val shoulderMid = midpointOrNull(leftShoulder, rightShoulder)
        val hipMid = midpointOrNull(leftHip, rightHip)
        val footPair = footPairOrNull(leftAnkle, leftHeel, rightAnkle, rightHeel)
        val footY = footPair?.y
        val bodyY = when {
            hipMid != null && shoulderMid != null -> hipMid.y * HipSignalWeight + shoulderMid.y * ShoulderSignalWeight
            hipMid != null -> hipMid.y
            else -> null
        }

        if (bodyY == null && footY == null) {
            log(
                "关键点置信度不足: hip=${leftHip?.confidence?.fmt}/${rightHip?.confidence?.fmt}, " +
                    "foot=${leftAnkle?.confidence?.fmt}/${rightAnkle?.confidence?.fmt}",
            )
            return null
        }

        val scaleResult = estimateScale(
            shoulderMid = shoulderMid,
            hipMid = hipMid,
            leftHip = leftHip,
            rightHip = rightHip,
            leftFootY = footPair?.leftY,
            rightFootY = footPair?.rightY,
        )
        val quality = qualityFor(
            bodyY = bodyY,
            footPair = footPair,
            scale = scaleResult.scale,
            measuredScaleReliable = scaleResult.reliable,
        )

        return PoseSample(
            bodyY = bodyY,
            footY = footY,
            scale = scaleResult.scale,
            quality = quality,
            measuredScaleReliable = scaleResult.reliable,
        )
    }

    private fun PoseFrame.correctedLandmarks(): Map<BodyLandmark, PosePoint> {
        val motion = cameraMotion
        // offset 是累计的已确认位移；匹配暂时失败时仍保留，防止坐标系来回切换。
        if (!motion.available) return landmarks
        return landmarks.mapValues { (_, point) ->
            point.copy(
                x = point.x - motion.offsetX,
                y = point.y - motion.offsetY,
            )
        }
    }

    private fun PoseSample.toFeatures(
        bodyBaseline: Float?,
        footBaseline: Float?,
    ): JumpFeatures {
        val normalizationScale = lastStableScale ?: scale
        val bodyLift = if (bodyY != null && bodyBaseline != null) {
            ((bodyBaseline - bodyY) / normalizationScale).coerceAtLeast(0f)
        } else {
            null
        }
        val footLift = if (footY != null && footBaseline != null) {
            ((footBaseline - footY) / normalizationScale).coerceAtLeast(0f)
        } else {
            null
        }
        val combinedLift = when {
            bodyLift != null && footLift != null -> maxOf(
                bodyLift * BodyLiftBoost,
                footLift * FootLiftBoost,
                bodyLift * BodyLiftBlend + footLift * FootLiftBlend,
            )
            bodyLift != null -> bodyLift * BodyOnlyBoost
            footLift != null -> footLift * FootOnlyBoost
            else -> 0f
        }
        return JumpFeatures(
            bodyLift = bodyLift,
            footLift = footLift,
            combinedLift = combinedLift,
        )
    }

    private fun hasPairedLiftEvidence(features: JumpFeatures): Boolean {
        val bodyLift = features.bodyLift ?: return false
        val footLift = features.footLift ?: return false
        // Both signals must show a meaningful upward displacement. Because
        // individual lifts are clipped at zero, checking positivity alone
        // would let a stationary body paired with a noisy foot qualify.
        return bodyLift >= MinPairedEvidenceLift && footLift >= MinPairedEvidenceLift
    }

    private fun estimateScale(
        shoulderMid: PosePoint?,
        hipMid: PosePoint?,
        leftHip: PosePoint?,
        rightHip: PosePoint?,
        leftFootY: Float?,
        rightFootY: Float?,
    ): ScaleResult {
        val torsoLength = if (shoulderMid != null && hipMid != null) {
            distance(shoulderMid, hipMid)
        } else {
            null
        }
        val leftLegLength = if (leftHip != null && leftFootY != null) abs(leftFootY - leftHip.y) else null
        val rightLegLength = if (rightHip != null && rightFootY != null) abs(rightFootY - rightHip.y) else null
        val legLength = averageOf(leftLegLength, rightLegLength)

        val measured = when {
            torsoLength != null && legLength != null -> torsoLength * TorsoScaleWeight + legLength * LegScaleWeight
            torsoLength != null -> torsoLength
            legLength != null -> legLength * LegOnlyScaleRatio
            else -> null
        }
        val reliable = measured != null && measured >= MinBodyScale
        val scale = when {
            reliable -> measured
            lastStableScale != null -> lastStableScale ?: FallbackBodyScale
            else -> FallbackBodyScale
        }
        return ScaleResult(scale = scale.coerceAtLeast(MinBodyScale), reliable = reliable)
    }

    private fun qualityFor(
        bodyY: Float?,
        footPair: FootPair?,
        scale: Float,
        measuredScaleReliable: Boolean,
    ): SampleQuality {
        val hasStableBody = bodyY != null
        val hasFoot = footPair != null
        val footDisagreement = footPair?.spread ?: 0f
        return when {
            scale < MinBodyScale || (!hasStableBody && !hasFoot) -> SampleQuality.Unusable
            !measuredScaleReliable || (!hasStableBody && hasFoot) -> SampleQuality.Poor
            footDisagreement > MaxFootDisagreement && bodyY == null -> SampleQuality.Poor
            else -> SampleQuality.Good
        }
    }

    private fun footPairOrNull(
        leftAnkle: PosePoint?,
        leftHeel: PosePoint?,
        rightAnkle: PosePoint?,
        rightHeel: PosePoint?,
    ): FootPair? {
        val leftFootY = footYOrNull(leftAnkle, leftHeel)
        val rightFootY = footYOrNull(rightAnkle, rightHeel)
        val footY = when {
            leftFootY != null && rightFootY != null -> (leftFootY + rightFootY) / 2f
            leftFootY != null -> leftFootY
            rightFootY != null -> rightFootY
            else -> return null
        }
        return FootPair(
            y = footY,
            leftY = leftFootY,
            rightY = rightFootY,
            spread = if (leftFootY != null && rightFootY != null) abs(leftFootY - rightFootY) else 0f,
        )
    }

    private fun footYOrNull(
        ankle: PosePoint?,
        heel: PosePoint?,
    ): Float? {
        val ankleY = ankle?.takeIf { it.confidence >= MinFootConfidence }?.y
        val heelY = heel?.takeIf { it.confidence >= MinFootConfidence }?.y

        return when {
            ankleY != null && heelY != null -> ankleY * AnkleWeight + heelY * HeelWeight
            ankleY != null -> ankleY
            heelY != null -> heelY
            else -> null
        }
    }

    private fun Map<BodyLandmark, PosePoint>.required(landmark: BodyLandmark): PosePoint? {
        val point = this[landmark] ?: return null
        return point.takeIf { it.confidence >= MinLandmarkConfidence }
    }

    private fun midpointOrNull(first: PosePoint?, second: PosePoint?): PosePoint? {
        return when {
            first != null && second != null -> midpoint(first, second)
            first != null -> first
            second != null -> second
            else -> null
        }
    }

    private fun midpoint(first: PosePoint, second: PosePoint) = PosePoint(
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f,
        confidence = minOf(first.confidence, second.confidence),
    )

    private fun distance(first: PosePoint, second: PosePoint): Float {
        return hypot(first.x - second.x, first.y - second.y)
    }

    private fun averageOf(first: Float?, second: Float?): Float? {
        return when {
            first != null && second != null -> (first + second) / 2f
            first != null -> first
            else -> second
        }
    }

    private fun smooth(
        previous: Float,
        current: Float,
        factor: Float,
    ): Float = previous * (1f - factor) + current * factor

    private fun log(message: String) {
        onLog?.invoke("[JumpCounter] $message")
    }

    private data class PoseSample(
        val bodyY: Float?,
        val footY: Float?,
        val scale: Float,
        val quality: SampleQuality,
        val measuredScaleReliable: Boolean,
    ) {
        val trackingQuality: TrackingQuality
            get() = if (quality == SampleQuality.Good) TrackingQuality.Tracking else TrackingQuality.PartialBody
    }

    private enum class SampleQuality {
        Good,
        Poor,
        Unusable,
    }

    private data class JumpFeatures(
        val bodyLift: Float?,
        val footLift: Float?,
        val combinedLift: Float,
    )

    private data class JumpThresholds(
        val rising: Float,
        val airborne: Float,
        val landing: Float,
        val ground: Float,
        val weakPeak: Float,
        val validPeak: Float,
    )

    private data class FootPair(
        val y: Float,
        val leftY: Float?,
        val rightY: Float?,
        val spread: Float,
    )

    private data class ScaleResult(
        val scale: Float,
        val reliable: Boolean,
    )

    private companion object {
        const val MinLandmarkConfidence = 0.40f
        const val MinFootConfidence = 0.25f
        const val MinBodyScale = 0.08f
        const val FallbackBodyScale = 0.27f
        const val MaxFootDisagreement = 0.20f

        const val RisingThreshold = 0.030f
        const val AirborneThreshold = 0.044f
        const val ValidJumpThreshold = 0.046f
        const val WeakJumpThreshold = 0.031f
        const val LandingThreshold = 0.034f
        const val GroundThreshold = 0.018f
        const val GroundedBaselineLift = 0.014f

        const val MinAirTimeMs = 95L
        const val MinWeakAirTimeMs = 105L
        const val BaseRefractoryMs = 160L
        const val MinAdaptiveRefractoryMs = 120L
        const val MaxLostPoseMs = 1500L
        const val MaxTransientLostMs = 150L
        const val MaxInterpolationGapMs = MaxTransientLostMs
        const val MaxRisingMs = 300L
        const val MaxAirborneMs = 800L
        const val MaxLandingMs = 300L
        const val MaxJumpDurationMs = 900L
        const val MinStandardJumpSamples = 2
        const val MinWeakJumpSamples = 2
        const val MinStableBeforeCountingMs = 300L
        const val RecoveryStableBeforeCountingMs = 150L
        const val MinCycleMs = 180L
        const val MaxCycleMs = 900L
        const val NominalFrameMs = 33L
        const val MinCalibrationSamples = 5
        const val CalibrationWindowMs = 450L
        const val CalibrationSmoothing = 0.18f
        const val MinConfirmedForEstimation = 5
        const val MinCadenceSamples = 4
        const val MaxCadenceSamples = 8
        const val MaxCadenceCoefficientOfVariation = 0.20f
        const val MaxEstimatedPerGap = 2
        const val MaxEstimationWindowMs = 600L
        const val EstimationDueRatio = 0.82f
        const val MaxReconcileWindowMs = 150L
        const val CameraMotionRecoveryThreshold = 0.02f

        const val MinRisingVelocity = 0.035f
        const val LandingVelocity = -0.030f

        const val BodyLiftBoost = 1.15f
        const val FootLiftBoost = 0.78f
        const val BodyOnlyBoost = 1.12f
        const val FootOnlyBoost = 0.78f
        const val BodyLiftBlend = 0.60f
        const val FootLiftBlend = 0.40f

        const val HipSignalWeight = 0.82f
        const val ShoulderSignalWeight = 0.18f
        const val TorsoScaleWeight = 0.70f
        const val LegScaleWeight = 0.30f
        const val LegOnlyScaleRatio = 0.78f
        const val AnkleWeight = 0.7f
        const val HeelWeight = 0.3f

        const val LiftSmoothing = 0.62f
        const val ScaleSmoothing = 0.12f
        const val GroundedBaselineSmoothing = 0.06f
        const val AirborneBaselineSmoothing = 0.006f
        const val RecoveryBaselineSmoothing = 0.45f
        const val CadenceSmoothing = 0.24f
        const val AdaptivePeakSmoothing = 0.18f
        const val LearnedWeakPeakRatio = 0.58f
        const val LearnedValidPeakRatio = 0.72f
        const val RisingThresholdRatio = 0.82f
        const val AirborneThresholdRatio = 0.96f
        const val LandingThresholdRatio = 0.88f
        const val RefractoryCadenceRatio = 0.55f
        const val MinWeakPeak = 0.022f
        const val MinValidPeak = 0.036f
        const val MinPairedEvidenceLift = 0.004f

        private val Float.fmt: String
            get() = String.format("%.3f", this)
    }
}
