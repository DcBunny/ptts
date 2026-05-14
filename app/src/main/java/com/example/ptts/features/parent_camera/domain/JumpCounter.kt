package com.example.ptts.features.parent_camera.domain

import kotlin.math.abs
import kotlin.math.hypot

class JumpCounter(
    private val onLog: ((String) -> Unit)? = null,
) {
    private var count = 0
    private var phase = JumpPhase.Searching
    private var baselineBodyY: Float? = null
    private var baselineFootY: Float? = null
    private var jumpStartMs = 0L
    private var lastCountMs = Long.MIN_VALUE / 2
    private var lastValidMs = 0L
    private var phaseStartMs = 0L
    private var jumpMaxLift = 0f
    private var jumpSampleCount = 0
    private var lastStableScale: Float? = null
    private var smoothedLift: Float? = null
    private var previousLift: Float? = null
    private var previousSampleMs: Long? = null
    private var averageCycleMs: Float? = null
    private var adaptivePeakLift: Float? = null
    private var stableSinceMs: Long? = null

    fun reset() {
        count = 0
        phase = JumpPhase.Searching
        baselineBodyY = null
        baselineFootY = null
        jumpStartMs = 0L
        lastCountMs = Long.MIN_VALUE / 2
        lastValidMs = 0L
        phaseStartMs = 0L
        jumpMaxLift = 0f
        jumpSampleCount = 0
        lastStableScale = null
        smoothedLift = null
        previousLift = null
        previousSampleMs = null
        averageCycleMs = null
        adaptivePeakLift = null
        stableSinceMs = null
        log("计数器已重置")
    }

    fun accept(frame: PoseFrame): JumpCounterResult {
        log("accept: timestamp=${frame.timestampMs} landmarks=${frame.landmarks.size}")
        val sample = frame.toSample()
        if (sample == null || sample.quality == SampleQuality.Unusable) {
            handleLostFrame(frame.timestampMs)
            return result(TrackingQuality.PartialBody, countedThisFrame = false)
        }

        lastValidMs = frame.timestampMs
        updateStableScale(sample)

        val stableForMs = updateStableWindow(frame.timestampMs, sample.quality)
        val bodyBaseline = baselineBodyY
        val footBaseline = baselineFootY
        if (bodyBaseline == null && footBaseline == null) {
            baselineBodyY = sample.bodyY
            baselineFootY = sample.footY
            phase = JumpPhase.Grounded
            phaseStartMs = frame.timestampMs
            smoothedLift = 0f
            previousLift = 0f
            previousSampleMs = frame.timestampMs
            stableSinceMs = frame.timestampMs - MinStableBeforeCountingMs
            log(
                "建立基线: body=${sample.bodyY?.fmt} foot=${sample.footY?.fmt} " +
                    "scale=${sample.scale.fmt} quality=${sample.quality}",
            )
            return result(sample.trackingQuality, countedThisFrame = false)
        }

        val features = sample.toFeatures(
            bodyBaseline = bodyBaseline,
            footBaseline = footBaseline,
        )
        val filteredLift = filterLift(features.combinedLift)
        val landingLift = minOf(filteredLift, features.combinedLift)
        val velocity = velocityPerSecond(frame.timestampMs, filteredLift)
        val thresholds = thresholds()

        val oldPhase = phase
        var counted = false
        log(
            "sample: phase=$phase lift=${filteredLift.fmt} raw=${features.combinedLift.fmt} " +
                "body=${features.bodyLift?.fmt} foot=${features.footLift?.fmt} " +
                "velocity=${velocity.fmt} quality=${sample.quality}",
        )

        val newPhase = if (stableForMs < MinStableBeforeCountingMs) {
            updateGroundBaseline(sample, filteredLift, allowFastUpdate = stableForMs < MinStableBeforeCountingMs)
            JumpPhase.Grounded
        } else {
            nextPhase(
                timestampMs = frame.timestampMs,
                sample = sample,
                lift = filteredLift,
                triggerLift = maxOf(filteredLift, features.combinedLift),
                landingLift = landingLift,
                velocity = velocity,
                thresholds = thresholds,
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
        previousSampleMs = frame.timestampMs

        if (oldPhase != phase || phase == JumpPhase.Airborne || phase == JumpPhase.Rising) {
            log(
                "帧@${frame.timestampMs}: phase=$phase lift=${filteredLift.fmt} " +
                    "threshold=${thresholds.rising.fmt}/${thresholds.validPeak.fmt}",
            )
        }

        return result(sample.trackingQuality, counted)
    }

    private fun nextPhase(
        timestampMs: Long,
        sample: PoseSample,
        lift: Float,
        triggerLift: Float,
        landingLift: Float,
        velocity: Float,
        thresholds: JumpThresholds,
        onCount: (String) -> Unit,
    ): JumpPhase {
        return when (phase) {
            JumpPhase.Searching -> JumpPhase.Grounded
            JumpPhase.Grounded -> {
                updateGroundBaseline(sample, lift, allowFastUpdate = false)
                if (triggerLift >= thresholds.rising && velocity >= MinRisingVelocity) {
                    jumpStartMs = timestampMs
                    jumpMaxLift = triggerLift
                    jumpSampleCount = 1
                    log("起跳: lift=${triggerLift.fmt} velocity=${velocity.fmt}")
                    JumpPhase.Rising
                } else {
                    JumpPhase.Grounded
                }
            }
            JumpPhase.Rising -> when {
                landingLift <= thresholds.ground -> {
                    onCount("低帧率回落")
                    JumpPhase.Grounded
                }
                lift >= thresholds.airborne -> {
                    recordJumpSample(lift)
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
                    recordJumpSample(lift)
                    JumpPhase.Rising
                }
            }
            JumpPhase.Airborne -> {
                recordJumpSample(lift)
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
                recordJumpSample(lift)
                if (landingLift <= thresholds.ground) {
                    onCount("落地")
                    JumpPhase.Grounded
                } else {
                    JumpPhase.Landing
                }
            }
        }
    }

    private fun handleLostFrame(timestampMs: Long) {
        val lostMs = if (lastValidMs == 0L) 0L else timestampMs - lastValidMs
        stableSinceMs = null
        smoothedLift = null
        previousLift = null
        previousSampleMs = null
        if (lastValidMs == 0L || lostMs > MaxLostPoseMs) {
            log("姿态丢失过长(${lostMs}ms)，重置状态")
            baselineBodyY = null
            baselineFootY = null
            phase = JumpPhase.Searching
            resetJumpTracking()
        } else {
            log("帧丢弃: timestamp=$timestampMs, 已丢失=${lostMs}ms")
        }
    }

    private fun result(
        trackingQuality: TrackingQuality,
        countedThisFrame: Boolean,
    ) = JumpCounterResult(
        count = count,
        phase = phase,
        trackingQuality = trackingQuality,
        countedThisFrame = countedThisFrame,
    )

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
        val timeSinceLastCount = timestampMs - lastCountMs
        val canCountAgain = timeSinceLastCount >= minRefractoryMs()
        val isStandardJump = jumpMaxLift >= thresholds.validPeak && jumpSampleCount >= MinStandardJumpSamples
        val isWeakJump = jumpMaxLift >= thresholds.weakPeak && jumpSampleCount >= MinWeakJumpSamples
        val enoughLift = isStandardJump || isWeakJump
        val minAirTime = if (isStandardJump) MinAirTimeMs else MinWeakAirTimeMs
        val reasonableAirTime = airTimeMs in minAirTime..MaxJumpDurationMs
        val counted = enoughLift && reasonableAirTime && canCountAgain

        if (counted) {
            count += 1
            updateCadence(timestampMs)
            adaptivePeakLift = adaptivePeakLift
                ?.let { smooth(it, jumpMaxLift, AdaptivePeakSmoothing) }
                ?: jumpMaxLift
            lastCountMs = timestampMs
            log(
                "计数成功($reason)! count=$count, airTime=${airTimeMs}ms, " +
                    "maxLift=${jumpMaxLift.fmt}, samples=$jumpSampleCount, sinceLast=${timeSinceLastCount}ms",
            )
        } else {
            when {
                !enoughLift ->
                    log(
                        "拒绝计数($reason): 跳跃幅度不足(maxLift=${jumpMaxLift.fmt} < " +
                            "${thresholds.weakPeak.fmt}), samples=$jumpSampleCount, lift=${lift.fmt}",
                    )
                !reasonableAirTime ->
                    log(
                        "拒绝计数($reason): 持续时间不合理(${airTimeMs}ms, " +
                            "range=${minAirTime}..${MaxJumpDurationMs}ms), lift=${lift.fmt}",
                    )
                !canCountAgain ->
                    log("拒绝计数($reason): 自适应不应期内(${timeSinceLastCount}ms)")
            }
        }

        updateGroundBaseline(sample, lift, allowFastUpdate = counted)
        resetJumpTracking()
        return counted
    }

    private fun recordJumpSample(lift: Float) {
        jumpMaxLift = maxOf(jumpMaxLift, lift)
        jumpSampleCount += 1
    }

    private fun resetJumpTracking() {
        jumpMaxLift = 0f
        jumpSampleCount = 0
        smoothedLift = 0f
        previousLift = 0f
    }

    private fun filterLift(rawLift: Float): Float {
        val previous = smoothedLift
        val filtered = if (previous == null) {
            rawLift
        } else {
            smooth(previous, rawLift, LiftSmoothing)
        }
        smoothedLift = filtered
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
        if (lastCountMs <= 0L) {
            return
        }
        val cycleMs = (timestampMs - lastCountMs).coerceIn(MinCycleMs, MaxCycleMs).toFloat()
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

    private fun PoseFrame.toSample(): PoseSample? {
        val leftShoulder = required(BodyLandmark.LeftShoulder)
        val rightShoulder = required(BodyLandmark.RightShoulder)
        val leftHip = required(BodyLandmark.LeftHip)
        val rightHip = required(BodyLandmark.RightHip)
        val leftAnkle = landmarks[BodyLandmark.LeftAnkle]
        val rightAnkle = landmarks[BodyLandmark.RightAnkle]
        val leftHeel = landmarks[BodyLandmark.LeftHeel]
        val rightHeel = landmarks[BodyLandmark.RightHeel]

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

    private fun PoseSample.toFeatures(
        bodyBaseline: Float?,
        footBaseline: Float?,
    ): JumpFeatures {
        val bodyLift = if (bodyY != null && bodyBaseline != null) {
            ((bodyBaseline - bodyY) / scale).coerceAtLeast(0f)
        } else {
            null
        }
        val footLift = if (footY != null && footBaseline != null) {
            ((footBaseline - footY) / scale).coerceAtLeast(0f)
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

    private fun PoseFrame.required(landmark: BodyLandmark): PosePoint? {
        val point = landmarks[landmark] ?: return null
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
        const val MaxLostPoseMs = 750L
        const val MaxRisingMs = 300L
        const val MaxAirborneMs = 800L
        const val MaxJumpDurationMs = 900L
        const val MinStandardJumpSamples = 2
        const val MinWeakJumpSamples = 2
        const val MinStableBeforeCountingMs = 300L
        const val MinCycleMs = 180L
        const val MaxCycleMs = 900L

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
        const val RecoveryBaselineSmoothing = 0.22f
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

        private val Float.fmt: String
            get() = String.format("%.3f", this)
    }
}
