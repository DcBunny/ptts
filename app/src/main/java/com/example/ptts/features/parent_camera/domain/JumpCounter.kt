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
    private var lastFrameSeenMs: Long? = null
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
    private var recoveryWindowDurationMs = DefaultRecoveryCycleMs
    private var recoveryMaxBodyY: Float? = null
    private var recoveryMaxFootY: Float? = null
    private var recoveryMinBodyY: Float? = null
    private var recoveryMinFootY: Float? = null
    private var recoveryStableSampleCount = 0
    private val recoveryScales = mutableListOf<Float>()
    private var recoveryEstimateEnabled = false
    private var poseLossStartedInJump = false
    private var suppressNextCadenceSample = false
    private var jumpHasPairedEvidence = false
    private var recoveryWindowStartedMs = 0L
    private var hasObservedPeakLift = false
    private var lastObservedPeakLift = 0f

    // Observed sampling cadence. Every phase timeout used to be a fixed millisecond
    // constant, which silently turned into a detection gate on slow devices: at 8 fps a
    // single dropped frame looked like a lost pose and the whole jump candidate was
    // discarded. These fields let the counter scale its windows to the actual stream.
    private val frameIntervals = ArrayDeque<Long>()
    private var medianFrameMs: Long? = null
    private var intervalSinceMedianRefresh = 0
    private var typicalWorstFrameMs: Long? = null

    // Calibration must observe a still person. A child that is already jumping during the
    // countdown used to be averaged into the standing baseline, which biases every later
    // measurement.
    private val calibrationSamples = ArrayDeque<CalibrationSample>()

    // Per-frame values retained for the bounded diagnostic stream. These are
    // deliberately reset at the beginning of each accepted frame so a lost
    // pose cannot make an old lift look like current evidence.
    private var diagnosticSampleIntervalMs: Long? = null
    private var diagnosticRawLift: Float? = null
    private var diagnosticSmoothedLift: Float? = null
    private var diagnosticPeakLift: Float? = null
    private var diagnosticJumpDurationMs: Long? = null
    private var diagnosticRejectionReason: String? = null
    private var diagnosticRecoveryStage: String? = null
    private var diagnosticRecoveryMatchedEstimate = false
    private var diagnosticLandmarks: Map<BodyLandmark, PosePoint> = emptyMap()
    private var diagnosticCameraMotion = CameraMotion()
    private var diagnosticTimestampMs = 0L
    private var diagnosticInputQuality: String? = null
    private var diagnosticBodySignal: String? = null
    private var diagnosticFootSignal: String? = null
    private var diagnosticSessionStage: String? = null
    private var diagnosticLightMetrics: FrameLightMetrics? = null
    private var diagnosticAutoLowLightState: AutoLowLightState? = null
    private var diagnosticAutoLowLightLevel: Int? = null
    private var diagnosticAutoLowLightReason: String? = null

    // A detector can switch from a paired ankle/hip signal to a single side or heel signal for
    // one dark frame. Keep the previous signal around so that a source switch cannot turn a
    // coordinate discontinuity into a fake take-off.
    private var previousBodySignal: String? = null
    private var previousBodySignalY: Float? = null
    private var previousFootSignal: String? = null
    private var previousFootSignalY: Float? = null

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
            calibrationSamples.clear()
            lastFrameSeenMs = null
            frameIntervals.clear()
            medianFrameMs = null
            typicalWorstFrameMs = null
            intervalSinceMedianRefresh = 0
        }
        log("计数器已重置")
    }

    /** Collects stable standing samples while the camera is in framing/countdown. */
    fun calibrate(frame: PoseFrame) {
        val seenAt = lastFrameSeenMs
        if (seenAt != null && frame.timestampMs <= seenAt) return
        val sample = frame.toSample() ?: return
        if (sample.quality == SampleQuality.Unusable) return
        diagnosticSampleIntervalMs = seenAt?.let { frame.timestampMs - it }
        diagnosticRawLift = null
        diagnosticSmoothedLift = null
        diagnosticPeakLift = null
        diagnosticJumpDurationMs = null
        diagnosticRejectionReason = null
        diagnosticRecoveryStage = null
        diagnosticRecoveryMatchedEstimate = false
        diagnosticLandmarks = frame.landmarks.toMap()
        diagnosticCameraMotion = frame.cameraMotion
        diagnosticTimestampMs = frame.timestampMs
        diagnosticInputQuality = sample.quality.name
        diagnosticBodySignal = sample.bodySignal
        diagnosticFootSignal = sample.footSignal
        diagnosticSessionStage = frame.sessionStage ?: "Calibration"
        diagnosticLightMetrics = frame.lightMetrics
        diagnosticAutoLowLightState = frame.autoLowLightState
        diagnosticAutoLowLightLevel = frame.autoLowLightLevel
        diagnosticAutoLowLightReason = frame.autoLowLightReason
        recordFrameInterval(frame.timestampMs)
        lastFrameSeenMs = frame.timestampMs
        if (calibrationStartedMs == null) calibrationStartedMs = frame.timestampMs
        calibrationLastMs = frame.timestampMs

        // The reference is only meaningful if the person actually stood still while it was
        // collected. Without this guard a child who starts jumping during the countdown
        // poisons the standing baseline and every later lift is measured against noise.
        val windowStart = frame.timestampMs - MaxCalibrationDriftWindowMs
        while (calibrationSamples.isNotEmpty() &&
            calibrationSamples.first().timestampMs < windowStart
        ) {
            calibrationSamples.removeFirst()
        }
        if (!calibrationIsStationary()) {
            calibrationSamples.clear()
            calibrationStartedMs = frame.timestampMs
            calibrationSampleCount = 0
            calibrationReady = false
            log("校准期间检测到移动，重新开始站立采样")
            return
        }

        calibrationSamples += CalibrationSample(
            timestampMs = frame.timestampMs,
            bodyY = sample.bodyY,
            footY = sample.footY,
        )
        calibrationSampleCount += 1
        sample.bodyY?.let { calibrationBodyY = smooth(calibrationBodyY ?: it, it, CalibrationSmoothing) }
        sample.footY?.let { calibrationFootY = smooth(calibrationFootY ?: it, it, CalibrationSmoothing) }
        calibrationScale = smooth(calibrationScale ?: sample.scale, sample.scale, CalibrationSmoothing)
        // Readiness requires a window that actually spans the calibration duration. Marking it
        // ready after a handful of frames let a burst of jump frames count as a valid standing
        // reference before the drift guard had anything to compare against.
        val newest = calibrationSamples.last().timestampMs
        val oldest = calibrationSamples.first().timestampMs
        val coveredMs = newest - oldest
        calibrationReady = calibrationSamples.size >= MinCalibrationSamples &&
            coveredMs >= CalibrationWindowMs
        emitDiagnostic(event = "calibration", recovering = false, timestampMs = frame.timestampMs)
    }

    private fun calibrationIsStationary(): Boolean {
        if (calibrationSamples.size < MinCalibrationStationarySamples) return true
        val scale = calibrationScale ?: FallbackBodyScale
        val bodyYs = calibrationSamples.mapNotNull { it.bodyY }
        val footYs = calibrationSamples.mapNotNull { it.footY }
        val bodyRange = if (bodyYs.isEmpty()) 0f else bodyYs.max() - bodyYs.min()
        val footRange = if (footYs.isEmpty()) 0f else footYs.max() - footYs.min()
        val allowedRange = (scale * MaxCalibrationDriftRatio).coerceAtLeast(MinCalibrationDriftRange)
        return maxOf(bodyRange, footRange) <= allowedRange
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
        recoveryWindowDurationMs = DefaultRecoveryCycleMs
        recoveryMaxBodyY = null
        recoveryMaxFootY = null
        recoveryMinBodyY = null
        recoveryMinFootY = null
        recoveryStableSampleCount = 0
        recoveryScales.clear()
        recoveryEstimateEnabled = false
        poseLossStartedInJump = false
        suppressNextCadenceSample = false
        jumpHasPairedEvidence = false
        recoveryWindowStartedMs = 0L
        hasObservedPeakLift = false
        lastObservedPeakLift = 0f
        diagnosticSampleIntervalMs = null
        diagnosticRawLift = null
        diagnosticSmoothedLift = null
        diagnosticPeakLift = null
        diagnosticJumpDurationMs = null
        diagnosticRejectionReason = null
        diagnosticRecoveryStage = null
        diagnosticRecoveryMatchedEstimate = false
        diagnosticLandmarks = emptyMap()
        diagnosticCameraMotion = CameraMotion()
        diagnosticTimestampMs = 0L
        diagnosticInputQuality = null
        diagnosticBodySignal = null
        diagnosticFootSignal = null
        diagnosticSessionStage = null
        diagnosticLightMetrics = null
        diagnosticAutoLowLightState = null
        diagnosticAutoLowLightLevel = null
        diagnosticAutoLowLightReason = null
        previousBodySignal = null
        previousBodySignalY = null
        previousFootSignal = null
        previousFootSignalY = null
    }

    fun accept(frame: PoseFrame): JumpCounterResult {
        log("accept: timestamp=${frame.timestampMs} landmarks=${frame.landmarks.size}")
        diagnosticLandmarks = frame.landmarks.toMap()
        diagnosticCameraMotion = frame.cameraMotion
        diagnosticTimestampMs = frame.timestampMs
        diagnosticSessionStage = frame.sessionStage
        diagnosticLightMetrics = frame.lightMetrics
        diagnosticAutoLowLightState = frame.autoLowLightState
        diagnosticAutoLowLightLevel = frame.autoLowLightLevel
        diagnosticAutoLowLightReason = frame.autoLowLightReason
        if (lastInputMs != null && frame.timestampMs <= lastInputMs!!) {
            log("忽略重复或倒序时间戳: ${frame.timestampMs}")
            diagnosticSampleIntervalMs = null
            diagnosticRawLift = null
            diagnosticSmoothedLift = null
            diagnosticPeakLift = null
            diagnosticJumpDurationMs = null
            diagnosticRejectionReason = null
            diagnosticInputQuality = "out_of_order"
            diagnosticBodySignal = null
            diagnosticFootSignal = null
            return result(emptyPoseTrackingQuality(frame), countedThisFrame = false)
        }
        recordFrameInterval(frame.timestampMs)
        lastFrameSeenMs = frame.timestampMs
        diagnosticSampleIntervalMs = lastInputMs?.let { frame.timestampMs - it }
        diagnosticRawLift = null
        diagnosticSmoothedLift = null
        diagnosticPeakLift = null
        diagnosticJumpDurationMs = null
        diagnosticRejectionReason = null
        diagnosticRecoveryStage = null
        diagnosticRecoveryMatchedEstimate = false
        diagnosticInputQuality = null
        diagnosticBodySignal = null
        diagnosticFootSignal = null
        lastInputMs = frame.timestampMs
        val cameraUnstable = frame.cameraMotion.available && !frame.cameraMotion.reliable &&
            frame.cameraMotion.magnitude >= CameraMotionRecoveryThreshold
        if (cameraUnstable) {
            beginRecovery(frame.timestampMs, "camera_motion")
        }
        var sample = frame.toSample()
        diagnosticInputQuality = sample?.quality?.name ?: SampleQuality.Unusable.name
        sample = sample?.let { stabilizeSignalSwitch(it) }
        diagnosticBodySignal = sample?.bodySignal
        diagnosticFootSignal = sample?.footSignal
        if (sample == null || sample.quality == SampleQuality.Unusable) {
            if (cameraRecoveryPending) {
                val lostMs = lastValidMs?.let { frame.timestampMs - it } ?: Long.MAX_VALUE
                val maxLostMs = computeMaxLostPoseMs()
                if (lostMs > maxLostMs) {
                    expireRecovery(frame.timestampMs)
                    return result(
                        emptyPoseTrackingQuality(frame),
                        countedThisFrame = false,
                        recovering = false,
                    )
                }
                noteRecoveryLoss(frame.timestampMs)
                estimateDuringLoss(frame.timestampMs)
                return result(
                    emptyPoseTrackingQuality(frame),
                    countedThisFrame = false,
                    estimatedThisFrame = false,
                    recovering = true,
                )
            }
            val estimated = handleLostFrame(frame.timestampMs)
            return result(
                emptyPoseTrackingQuality(frame),
                countedThisFrame = false,
                estimatedThisFrame = estimated,
            )
        }

        if (cameraRecoveryPending) {
            lastValidMs = frame.timestampMs
            return processRecoverySample(frame.timestampMs, sample, cameraUnstable)
        }

        val gapSinceValid = lastValidMs?.let { frame.timestampMs - it } ?: 0L
        if (gapSinceValid > computeTransientLostMs() && recoveringSinceMs == null &&
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
        val featuresAtRecoveryForMerge = sample.toFeatures(baselineBodyY, baselineFootY)
        val recoveredOnGround = phase == JumpPhase.Grounded ||
            featuresAtRecoveryForMerge.combinedLift <= GroundThreshold
        if (!cameraRecoveryPending && wasRecovering && recoveredOnGround) {
            val featuresAtRecovery = featuresAtRecoveryForMerge
            val hasLandingEvidence = poseLossStartedInJump &&
                featuresAtRecovery.combinedLift <= GroundThreshold
            // A short dropout can swallow the landing frame itself. The candidate jump is
            // resolved here instead of being thrown away, so a jump the child demonstrably
            // completed is still counted.
            var countedLanding = false
            if (hasLandingEvidence) {
                countedLanding = maybeCountLanding(
                    timestampMs = frame.timestampMs,
                    sample = sample,
                    lift = featuresAtRecovery.combinedLift.coerceAtMost(GroundThreshold),
                    thresholds = thresholds(),
                    reason = "姿态恢复落地",
                )
            }
            if (hasLandingEvidence && !countedLanding) {
                val reconciledEstimate = findReconciliableEstimate(frame.timestampMs)
                diagnosticRecoveryMatchedEstimate = reconciledEstimate != null
                if (reconciledEstimate != null) {
                    estimatedEventTimes.remove(reconciledEstimate)
                    estimatedCount = (estimatedCount - 1).coerceAtLeast(0)
                    confirmedCount += 1
                    lastConfirmedMs = frame.timestampMs
                    lastEventMs = frame.timestampMs
                    log("恢复帧匹配节奏估算: timestamp=${frame.timestampMs}")
                }
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
            recoveryEstimateEnabled = false
            poseLossStartedInJump = false
            resetJumpTracking()
            log("姿态恢复后重新建立站立基线")
            return result(
                sample.trackingQuality,
                countedThisFrame = countedLanding || diagnosticRecoveryMatchedEstimate,
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
                recoveryEstimateEnabled = false
                poseLossStartedInJump = false
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

    private fun beginRecovery(timestampMs: Long, reason: String) {
        if (!cameraRecoveryPending) {
            cameraRecoveryPending = true
            recoveryEstimateEnabled = true
            recoveryWindowDurationMs = (averageCycleMs ?: DefaultRecoveryCycleMs)
                .toLong()
                .coerceIn(MinRecoveryCycleMs, MaxRecoveryCycleMs)
            if (estimateWindowStartMs == null) estimateWindowStartMs = timestampMs
            recoveryWindowStartedMs = timestampMs
            // Only the recovery window is reset here. Cadence, learned peak,
            // and already estimated events must survive repeated movement.
            resetRecoverySampling()
        } else {
            // A second movement belongs to the same interruption. Restart the
            // stable sample window, but keep the estimate quota untouched.
            resetRecoverySampling()
        }
        resetJumpTracking()
        poseLossStartedInJump = false
        phase = JumpPhase.Grounded
        phaseStartMs = timestampMs
        stableSinceMs = null
        recoveringSinceMs = timestampMs
        suspendedAfterLoss = true
        diagnosticRecoveryStage = reason
        log("进入恢复窗口: reason=$reason cycle=${recoveryWindowDurationMs}ms")
    }

    private fun resetRecoverySampling() {
        cameraStableSinceMs = null
        recoveryMaxBodyY = null
        recoveryMaxFootY = null
        recoveryMinBodyY = null
        recoveryMinFootY = null
        recoveryStableSampleCount = 0
        recoveryScales.clear()
    }

    private fun noteRecoveryLoss(timestampMs: Long) {
        resetRecoverySampling()
        diagnosticRecoveryStage = "lost"
        log("恢复窗口丢失姿态: timestamp=$timestampMs")
    }

    private fun expireRecovery(timestampMs: Long) {
        cameraRecoveryPending = false
        recoveringSinceMs = null
        recoveryEstimateEnabled = false
        poseLossStartedInJump = false
        resetRecoverySampling()
        resetJumpTracking()
        baselineBodyY = null
        baselineFootY = null
        phase = JumpPhase.Searching
        stableSinceMs = null
        estimateWindowStartMs = null
        estimatedInCurrentGap = 0
        // Already estimated events are kept: a score that has been shown to the user must
        // never shrink because the camera kept moving afterwards.
        suspendedAfterLoss = false
        diagnosticRecoveryStage = "expired"
        log("恢复窗口超时(${timestampMs}ms)，等待重新建立基线")
    }

    private fun processRecoverySample(
        timestampMs: Long,
        sample: PoseSample,
        cameraUnstable: Boolean,
    ): JumpCounterResult {
        if (cameraUnstable && timestampMs - recoveryWindowStartedMs > computeMaxRecoveryMs()) {
            // Handheld capture can keep the background matcher "unreliable" forever. Without
            // this escape hatch the counter stops counting permanently after any camera move.
            log("恢复窗口持续抖动超过上限，按当前姿态重建基线")
            finalizeRecovery(timestampMs, sample)
            return result(sample.trackingQuality, countedThisFrame = false, recovering = false)
        }
        if (cameraUnstable) {
            resetRecoverySampling()
            diagnosticRecoveryStage = "moving"
            estimateDuringLoss(timestampMs)
            return result(sample.trackingQuality, countedThisFrame = false, recovering = true)
        }

        val stableStart = cameraStableSinceMs ?: timestampMs.also {
            cameraStableSinceMs = it
            diagnosticRecoveryStage = "stabilizing"
        }
        recoveryMaxBodyY = maxOfNullable(recoveryMaxBodyY, sample.bodyY)
        recoveryMaxFootY = maxOfNullable(recoveryMaxFootY, sample.footY)
        recoveryMinBodyY = minOfNullable(recoveryMinBodyY, sample.bodyY)
        recoveryMinFootY = minOfNullable(recoveryMinFootY, sample.footY)
        recoveryStableSampleCount += 1
        if (sample.measuredScaleReliable) recoveryScales += sample.scale
        if (recoveryScales.size > MaxRecoveryScaleSamples) recoveryScales.removeAt(0)
        estimateDuringLoss(timestampMs)

        val stableForMs = timestampMs - stableStart
        if (stableForMs < RecoveryStableBeforeCountingMs) {
            diagnosticRecoveryStage = "stabilizing"
            return result(sample.trackingQuality, countedThisFrame = false, recovering = true)
        }

        val windowStart = stableStart
        val windowForMs = timestampMs - windowStart
        if (stableForMs >= RecoveryStableBeforeCountingMs &&
            recoveryStableSampleCount >= MinRecoverySamples &&
            recoveryRangeIsStationary()
        ) {
            finalizeRecovery(timestampMs, sample)
            return result(sample.trackingQuality, countedThisFrame = false, recovering = false)
        }
        if (windowForMs < recoveryWindowDurationMs) {
            diagnosticRecoveryStage = "sampling"
            return result(sample.trackingQuality, countedThisFrame = false, recovering = true)
        }

        finalizeRecovery(timestampMs, sample)
        return result(sample.trackingQuality, countedThisFrame = false, recovering = false)
    }

    private fun finalizeRecovery(timestampMs: Long, sample: PoseSample) {
        baselineBodyY = recoveryMaxBodyY ?: sample.bodyY ?: baselineBodyY
        baselineFootY = recoveryMaxFootY ?: sample.footY ?: baselineFootY
        medianOrNull(recoveryScales)?.let { lastStableScale = it }
        if (lastStableScale == null) lastStableScale = sample.scale

        cameraRecoveryPending = false
        cameraStableSinceMs = null
        recoveringSinceMs = null
        recoveryEstimateEnabled = false
        poseLossStartedInJump = false
        stableSinceMs = timestampMs - MinStableBeforeCountingMs
        phase = JumpPhase.Grounded
        phaseStartMs = timestampMs
        resetJumpTracking()
        val initialLift = sample.toFeatures(baselineBodyY, baselineFootY).combinedLift
        smoothedLift = initialLift.coerceAtMost(GroundThreshold)
        previousLift = smoothedLift
        previousRawLift = smoothedLift
        previousSampleMs = timestampMs
        lastFilterMs = timestampMs
        suspendedAfterLoss = false
        suppressNextCadenceSample = true
        recoveryMaxBodyY = null
        recoveryMaxFootY = null
        recoveryMinBodyY = null
        recoveryMinFootY = null
        recoveryStableSampleCount = 0
        recoveryScales.clear()
        diagnosticRecoveryStage = "ready"
        log(
            "恢复基线完成: body=${baselineBodyY?.fmt} foot=${baselineFootY?.fmt} " +
                "scale=${lastStableScale?.fmt} window=${recoveryWindowDurationMs}ms",
        )
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
                    timestampMs - phaseStartMs > computeMaxRisingMs() -> {
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
                    timestampMs - phaseStartMs > computeMaxAirborneMs() -> {
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
                } else if (timestampMs - phaseStartMs > computeMaxLandingMs()) {
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
        if (lastValid == null || lostMs > computeMaxLostPoseMs()) {
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
            cameraRecoveryPending = false
            resetRecoverySampling()
            recoveryEstimateEnabled = false
            poseLossStartedInJump = false
            previousBodySignal = null
            previousBodySignalY = null
            previousFootSignal = null
            previousFootSignalY = null
            estimateWindowStartMs = null
            estimatedInCurrentGap = 0
            // Estimated events are intentionally preserved so the displayed score never
            // decreases after the fact.
            return false
        } else if (lostMs > computeTransientLostMs()) {
            // A longer gap may still produce bounded cadence estimates, but
            // the old rising/airborne candidate is no longer trusted. Pose
            // loss without camera motion keeps the existing fast re-anchor;
            // camera motion uses the longer cycle-based recovery window.
            val wasInJump = poseLossStartedInJump ||
                (phase != JumpPhase.Grounded && phase != JumpPhase.Searching)
            recoveryEstimateEnabled = recoveryEstimateEnabled || wasInJump
            poseLossStartedInJump = poseLossStartedInJump || wasInJump
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
            // A short gap still interrupts an in-flight jump. Previously only the longer-gap
            // branch armed cadence estimation, so a one-frame drop in the middle of a jump
            // silently disabled gap filling.
            val wasInJump = poseLossStartedInJump ||
                (phase != JumpPhase.Grounded && phase != JumpPhase.Searching)
            recoveryEstimateEnabled = recoveryEstimateEnabled || wasInJump
            poseLossStartedInJump = poseLossStartedInJump || wasInJump
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
        // Building the diagnostic snapshot allocates several objects and formats nothing
        // cheaply; when no sink is attached (release builds) the work is skipped entirely.
        emitDiagnostic(
            event = when {
                estimatedThisFrame -> "estimated"
                countedThisFrame -> "counted"
                else -> "sample"
            },
            recovering = recovering,
        )
        return result
    }

    private fun emitDiagnostic(
        event: String,
        recovering: Boolean,
        timestampMs: Long? = null,
    ) {
        onDiagnostic?.invoke(
            JumpDiagnostic(
                timestampMs = timestampMs ?: diagnosticTimestampMs.takeIf { it > 0L } ?: lastInputMs ?: 0L,
                phase = phase,
                count = count,
                confirmedCount = confirmedCount,
                estimatedCount = estimatedCount,
                recovering = recovering,
                event = event,
                sampleIntervalMs = diagnosticSampleIntervalMs,
                rawLift = diagnosticRawLift,
                smoothedLift = diagnosticSmoothedLift,
                peakLift = diagnosticPeakLift,
                jumpDurationMs = diagnosticJumpDurationMs,
                rejectionReason = diagnosticRejectionReason,
                recoveryStage = diagnosticRecoveryStage,
                recoveryBaselineBodyY = recoveryMaxBodyY ?: baselineBodyY,
                recoveryBaselineFootY = recoveryMaxFootY ?: baselineFootY,
                recoveryScale = medianOrNull(recoveryScales) ?: lastStableScale,
                recoveryCycleMs = recoveryWindowDurationMs.takeIf {
                    diagnosticRecoveryStage != null || cameraRecoveryPending
                },
                recoveryValidPeakThreshold = thresholds().validPeak.takeIf {
                    diagnosticRecoveryStage != null || cameraRecoveryPending
                },
                recoveryMatchedEstimate = diagnosticRecoveryMatchedEstimate,
                medianFrameIntervalMs = medianFrameMs,
                typicalWorstFrameIntervalMs = typicalWorstFrameMs,
                adaptivePeakLift = adaptivePeakLift,
                landmarks = diagnosticLandmarks,
                cameraMotion = diagnosticCameraMotion,
                inputQuality = diagnosticInputQuality,
                bodySignal = diagnosticBodySignal,
                footSignal = diagnosticFootSignal,
                sessionStage = diagnosticSessionStage,
                lightMetrics = diagnosticLightMetrics,
                autoLowLightState = diagnosticAutoLowLightState,
                autoLowLightLevel = diagnosticAutoLowLightLevel,
                autoLowLightReason = diagnosticAutoLowLightReason,
            ),
        )
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
        diagnosticRecoveryMatchedEstimate = reconciledEstimate != null
        val timeSinceLastCount = lastEventMs?.let { timestampMs - it } ?: Long.MAX_VALUE
        val canCountAgain = reconciledEstimate != null || timeSinceLastCount >= minRefractoryMs()
        val lowFrameEligible = jumpSampleCount >= 1 && jumpMaxLift >= thresholds.validPeak && jumpHasPairedEvidence
        val isStandardJump = jumpMaxLift >= thresholds.validPeak &&
            (jumpSampleCount >= MinStandardJumpSamples || lowFrameEligible)
        val isWeakJump = jumpMaxLift >= thresholds.weakPeak && jumpSampleCount >= MinWeakJumpSamples
        val enoughLift = isStandardJump || isWeakJump
        // A jump cannot be shorter than the sampling interval that produced it. On a slow
        // analysis stream the fixed minimum air time rejects genuine but sparsely sampled
        // jumps, which is exactly the situation where a child's counter silently under-counts.
        val samplingFloorMs = frameIntervalBudget()
        val minAirTime = if (isStandardJump) {
            MinAirTimeMs.coerceAtLeast(samplingFloorMs)
        } else {
            MinWeakAirTimeMs.coerceAtLeast(samplingFloorMs)
        }
        val reasonableAirTime = airTimeMs in minAirTime..computeMaxJumpDurationMs()
        val counted = enoughLift && reasonableAirTime && canCountAgain
        observeJumpPeak(thresholds)
        val maxAirTime = computeMaxJumpDurationMs()

        if (counted) {
            if (reconciledEstimate != null) {
                estimatedEventTimes.remove(reconciledEstimate)
                estimatedCount = (estimatedCount - 1).coerceAtLeast(0)
                confirmedCount += 1
            } else {
                confirmedCount += 1
            }
            val cadenceEligible = !suppressNextCadenceSample && reconciledEstimate == null
            if (cadenceEligible) updateCadence(timestampMs)
            if (suppressNextCadenceSample) {
                suppressNextCadenceSample = false
                estimateWindowStartMs = null
                estimatedInCurrentGap = 0
            }
            updateAdaptivePeak()
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
                            "range=${minAirTime}..${maxAirTime}ms), lift=${lift.fmt}",
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

    /**
     * Learns the typical jump amplitude from every resolved jump candidate, not only from the
     * ones that already passed the gate. Learning only from counted jumps is a deadlock: a
     * child whose jumps are below the bootstrap threshold can never teach the counter to
     * accept them, so the profile stays conservative forever.
     */
    private fun observeJumpPeak(thresholds: JumpThresholds) {
        if (jumpSampleCount < MinWeakJumpSamples) return
        val observed = jumpMaxLift
        if (observed < thresholds.weakPeak) return
        // Ignore implausible amplitudes: they are almost always a landmark jump caused by a
        // tracking glitch rather than a jump-rope cycle, and they would distort the profile.
        if (observed > thresholds.weakPeak * MaxPlausiblePeakRatio) return
        lastObservedPeakLift = observed
        hasObservedPeakLift = true
    }

    private fun updateAdaptivePeak() {
        if (!hasObservedPeakLift) return
        val observed = lastObservedPeakLift
        adaptivePeakLift = adaptivePeakLift
            ?.let { smooth(it, observed, AdaptivePeakSmoothing) }
            ?: observed
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
        val airborne = minOf(AirborneThreshold, validPeak * AirborneThresholdRatio)
        val landing = LandingThreshold
            .coerceIn(GroundThreshold + MinLandingBand, airborne - MinLandingBand)
        return JumpThresholds(
            rising = minOf(RisingThreshold, weakPeak * RisingThresholdRatio),
            airborne = airborne,
            landing = landing,
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
        if (!recoveryEstimateEnabled || !cadenceStable()) return
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
        // The background matcher estimates camera translation only. Applying it to the pose
        // would move the child's landmarks too, which cancels exactly the body displacement a
        // jump is made of, and the accumulated offset has no meaningful relationship with the
        // absolute normalized landmark positions returned by ML Kit. Camera repositioning is
        // handled by the recovery window instead, so the raw landmarks are used as-is.
        val points = landmarks
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
        val bodySignal = when {
            hipMid != null && shoulderMid != null -> "hip+shoulder"
            hipMid != null && leftHip != null && rightHip != null -> "hip_pair"
            hipMid != null -> "hip_single"
            else -> null
        }

        return PoseSample(
            bodyY = bodyY,
            footY = footY,
            scale = scaleResult.scale,
            quality = quality,
            measuredScaleReliable = scaleResult.reliable,
            bodySignal = bodySignal,
            footSignal = footPair?.source,
        )
    }

    private fun stabilizeSignalSwitch(sample: PoseSample): PoseSample {
        var bodyY = sample.bodyY
        var footY = sample.footY
        var reanchored = false
        if (sample.bodyY != null && previousBodySignalY != null &&
            sample.bodySignal != null && previousBodySignal != null &&
            sample.bodySignal != previousBodySignal &&
            abs(sample.bodyY - previousBodySignalY!!) > MaxSignalSwitchDelta &&
            phase == JumpPhase.Grounded && !cameraRecoveryPending
        ) {
            bodyY = smooth(previousBodySignalY!!, sample.bodyY, SignalSwitchSmoothing)
            reanchored = true
        }
        if (sample.footY != null && previousFootSignalY != null &&
            sample.footSignal != null && previousFootSignal != null &&
            sample.footSignal != previousFootSignal &&
            abs(sample.footY - previousFootSignalY!!) > MaxSignalSwitchDelta &&
            phase == JumpPhase.Grounded && !cameraRecoveryPending
        ) {
            footY = smooth(previousFootSignalY!!, sample.footY, SignalSwitchSmoothing)
            reanchored = true
        }
        if (reanchored) {
            diagnosticRejectionReason = "signal_switch_reanchored"
        }
        bodyY?.let {
            previousBodySignal = sample.bodySignal
            previousBodySignalY = it
        }
        footY?.let {
            previousFootSignal = sample.footSignal
            previousFootSignalY = it
        }
        return if (bodyY == sample.bodyY && footY == sample.footY) {
            sample
        } else {
            sample.copy(bodyY = bodyY, footY = footY)
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
            source = footSignalSource(leftAnkle, leftHeel, rightAnkle, rightHeel),
        )
    }

    private fun footSignalSource(
        leftAnkle: PosePoint?,
        leftHeel: PosePoint?,
        rightAnkle: PosePoint?,
        rightHeel: PosePoint?,
    ): String {
        val left = when {
            leftAnkle.isUsableFoot() && leftHeel.isUsableFoot() -> "ankle+heel"
            leftAnkle.isUsableFoot() -> "ankle"
            leftHeel.isUsableFoot() -> "heel"
            else -> null
        }
        val right = when {
            rightAnkle.isUsableFoot() && rightHeel.isUsableFoot() -> "ankle+heel"
            rightAnkle.isUsableFoot() -> "ankle"
            rightHeel.isUsableFoot() -> "heel"
            else -> null
        }
        return when {
            left != null && right != null -> "left_$left|right_$right"
            left != null -> "left_$left"
            right != null -> "right_$right"
            else -> "none"
        }
    }

    private fun PosePoint?.isUsableFoot(): Boolean = this?.confidence?.let { it >= MinFootConfidence } == true

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

    private fun maxOfNullable(first: Float?, second: Float?): Float? {
        return when {
            first == null -> second
            second == null -> first
            else -> maxOf(first, second)
        }
    }

    private fun minOfNullable(first: Float?, second: Float?): Float? {
        return when {
            first == null -> second
            second == null -> first
            else -> minOf(first, second)
        }
    }

    private fun recoveryRangeIsStationary(): Boolean {
        val bodyRange = if (recoveryMaxBodyY != null && recoveryMinBodyY != null) {
            recoveryMaxBodyY!! - recoveryMinBodyY!!
        } else {
            0f
        }
        val footRange = if (recoveryMaxFootY != null && recoveryMinFootY != null) {
            recoveryMaxFootY!! - recoveryMinFootY!!
        } else {
            0f
        }
        return maxOf(bodyRange, footRange) <= MaxStationaryRecoveryRange
    }

    private fun medianOrNull(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }

    /**
     * Tracks the real delivery cadence of pose frames. Pose inference while recording runs
     * well below 30 fps on most phones, so every millisecond-based window has to follow the
     * observed stream instead of the nominal frame rate.
     */
    private fun recordFrameInterval(timestampMs: Long) {
        val previous = lastInputMs ?: return
        val interval = timestampMs - previous
        if (interval <= 0L || interval > MaxTrackedFrameIntervalMs) return
        frameIntervals.addLast(interval)
        if (frameIntervals.size > FrameIntervalWindow) frameIntervals.removeFirst()
        intervalSinceMedianRefresh += 1
        if (intervalSinceMedianRefresh >= FrameIntervalRefreshEvery || medianFrameMs == null) {
            refreshFrameIntervalStats()
        }
    }

    private fun refreshFrameIntervalStats() {
        intervalSinceMedianRefresh = 0
        if (frameIntervals.isEmpty()) return
        val sorted = frameIntervals.toList().sorted()
        medianFrameMs = sorted[sorted.size / 2]
        val p90Index = ((sorted.size - 1) * 0.9f).toInt().coerceIn(0, sorted.lastIndex)
        typicalWorstFrameMs = sorted[p90Index]
    }

    private fun frameIntervalBudget(): Long = medianFrameMs ?: NominalFrameMs

    private fun computeTransientLostMs(): Long {
        val worst = typicalWorstFrameMs ?: NominalFrameMs
        return maxOf(MaxTransientLostMs, worst * FrameGapSlackRatio)
            .coerceAtMost(MaxAdaptiveTransientLostMs)
    }

    private fun computeMaxLostPoseMs(): Long {
        val worst = typicalWorstFrameMs ?: NominalFrameMs
        return maxOf(MaxLostPoseMs, worst * FrameGapSlackRatio * 2L)
            .coerceAtMost(MaxAdaptiveLostPoseMs)
    }

    private fun computeMaxRisingMs(): Long {
        val budget = frameIntervalBudget()
        return maxOf(MaxRisingMs, PhaseSamples * budget).coerceAtMost(MaxAdaptiveRisingMs)
    }

    private fun computeMaxAirborneMs(): Long {
        val budget = frameIntervalBudget()
        return maxOf(MaxAirborneMs, PhaseSamples * budget * 2L).coerceAtMost(MaxAdaptiveAirborneMs)
    }

    private fun computeMaxLandingMs(): Long {
        val budget = frameIntervalBudget()
        return maxOf(MaxLandingMs, PhaseSamples * budget).coerceAtMost(MaxAdaptiveLandingMs)
    }

    private fun computeMaxJumpDurationMs(): Long {
        val budget = frameIntervalBudget()
        return maxOf(MaxJumpDurationMs, budget * 6L).coerceAtMost(MaxAdaptiveJumpDurationMs)
    }

    private fun computeMaxRecoveryMs(): Long =
        maxOf(MaxRecoveryDurationMs, recoveryWindowDurationMs * 2L)

    private fun emptyPoseTrackingQuality(frame: PoseFrame): TrackingQuality =
        if (frame.landmarks.isEmpty()) TrackingQuality.NoPose else TrackingQuality.UnreliablePose

    private fun log(message: String) {
        onLog?.invoke("[JumpCounter] $message")
    }

    private data class PoseSample(
        val bodyY: Float?,
        val footY: Float?,
        val scale: Float,
        val quality: SampleQuality,
        val measuredScaleReliable: Boolean,
        val bodySignal: String? = null,
        val footSignal: String? = null,
    ) {
        val trackingQuality: TrackingQuality
            get() = when (quality) {
                SampleQuality.Good -> TrackingQuality.Tracking
                SampleQuality.Poor, SampleQuality.Unusable -> TrackingQuality.UnreliablePose
            }
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
        val source: String,
    )

    private data class ScaleResult(
        val scale: Float,
        val reliable: Boolean,
    )

    private data class CalibrationSample(
        val timestampMs: Long,
        val bodyY: Float?,
        val footY: Float?,
    )

    private companion object {
        const val MinLandmarkConfidence = 0.40f
        const val MinFootConfidence = 0.25f
        const val MinBodyScale = 0.08f
        const val FallbackBodyScale = 0.27f
        const val MaxFootDisagreement = 0.20f
        const val MaxSignalSwitchDelta = 0.08f
        const val SignalSwitchSmoothing = 0.35f

        const val RisingThreshold = 0.030f
        const val AirborneThreshold = 0.044f
        const val ValidJumpThreshold = 0.046f
        // Bootstrap gate for a child whose jumps are small. The adaptive profile raises it as
        // soon as real amplitudes have been observed, so a permissive start no longer stays
        // permissive for the whole session.
        const val WeakJumpThreshold = 0.024f
        const val LandingThreshold = 0.034f
        const val GroundThreshold = 0.018f
        const val GroundedBaselineLift = 0.014f

        const val MinAirTimeMs = 95L
        const val MinWeakAirTimeMs = 105L
        const val BaseRefractoryMs = 160L
        const val MinAdaptiveRefractoryMs = 120L
        const val MaxLostPoseMs = 1500L
        const val MaxAdaptiveLostPoseMs = 2500L
        const val MaxTransientLostMs = 150L
        const val MaxAdaptiveTransientLostMs = 450L
        const val MaxInterpolationGapMs = 150L
        const val MaxRisingMs = 300L
        const val MaxAdaptiveRisingMs = 700L
        const val MaxAirborneMs = 800L
        const val MaxAdaptiveAirborneMs = 1500L
        const val MaxLandingMs = 300L
        const val MaxAdaptiveLandingMs = 700L
        const val MaxJumpDurationMs = 900L
        const val MaxAdaptiveJumpDurationMs = 1300L
        const val MaxRecoveryDurationMs = 1200L

        // Sampling-cadence adaptation. NominalFrameMs stays the design frame rate for the
        // filter; the phase windows follow whatever the analyzer actually delivers.
        const val FrameIntervalWindow = 16
        const val FrameIntervalRefreshEvery = 4
        const val FrameGapSlackRatio = 2L
        const val PhaseSamples = 2L
        const val MaxTrackedFrameIntervalMs = 2000L

        const val MinStandardJumpSamples = 2
        const val MinWeakJumpSamples = 2
        const val MinStableBeforeCountingMs = 300L
        const val RecoveryStableBeforeCountingMs = 150L
        const val DefaultRecoveryCycleMs = 450L
        const val MinRecoveryCycleMs = 180L
        const val MaxRecoveryCycleMs = 900L
        const val MinRecoverySamples = 3
        const val MaxStationaryRecoveryRange = 0.012f
        const val MaxRecoveryScaleSamples = 30
        const val MinCycleMs = 180L
        const val MaxCycleMs = 900L
        const val NominalFrameMs = 33L
        const val MinCalibrationSamples = 5
        const val CalibrationWindowMs = 450L
        const val CalibrationSmoothing = 0.18f
        const val MaxCalibrationDriftWindowMs = 1200L
        const val MaxCalibrationDriftRatio = 0.035f
        const val MinCalibrationDriftRange = 0.006f
        const val MinCalibrationStationarySamples = 4
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
        const val MaxPlausiblePeakRatio = 4f
        const val MinLandingBand = 0.004f
        const val LearnedWeakPeakRatio = 0.45f
        const val LearnedValidPeakRatio = 0.60f
        const val RisingThresholdRatio = 0.82f
        const val AirborneThresholdRatio = 0.96f
        const val LandingThresholdRatio = 0.60f
        const val RefractoryCadenceRatio = 0.55f
        const val MinWeakPeak = 0.022f
        const val MinValidPeak = 0.036f
        const val MinPairedEvidenceLift = 0.003f

        private val Float.fmt: String
            get() = String.format("%.3f", this)
    }
}
