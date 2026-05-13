package com.example.ptts.features.parent_camera.domain

import kotlin.math.hypot

class JumpCounter(
    private val onLog: ((String) -> Unit)? = null,
) {
    private var count = 0
    private var phase = JumpPhase.Searching
    private var baselineFootY: Float? = null
    private var jumpStartMs = 0L
    private var lastCountMs = Long.MIN_VALUE / 2
    private var lastValidMs = 0L
    private var phaseStartMs = 0L
    private var jumpMaxLift = 0f
    private var jumpSampleCount = 0
    private var lastTorsoLength: Float? = null

    fun reset() {
        count = 0
        phase = JumpPhase.Searching
        baselineFootY = null
        jumpStartMs = 0L
        lastCountMs = Long.MIN_VALUE / 2
        lastValidMs = 0L
        phaseStartMs = 0L
        jumpMaxLift = 0f
        jumpSampleCount = 0
        lastTorsoLength = null
        log("计数器已重置")
    }

    fun accept(frame: PoseFrame): JumpCounterResult {
        log("accept: timestamp=${frame.timestampMs} landmarks=${frame.landmarks.size}")
        val sample = frame.toSample()
        if (sample == null) {
            val lostMs = if (lastValidMs == 0L) 0L else frame.timestampMs - lastValidMs
            if (lastValidMs == 0L || lostMs > MaxLostPoseMs) {
                log("姿态丢失过长(${lostMs}ms)，重置状态")
                baselineFootY = null
                phase = JumpPhase.Searching
            } else {
                log("帧丢弃: timestamp=${frame.timestampMs}, 已丢失=${lostMs}ms")
            }
            return result(TrackingQuality.PartialBody, countedThisFrame = false)
        }

        lastValidMs = frame.timestampMs
        lastTorsoLength = sample.torsoLength
        log("sample: footY=${sample.footY.fmt} torso=${sample.torsoLength.fmt}")
        val currentBaseline = baselineFootY
        if (currentBaseline == null) {
            baselineFootY = sample.footY
            phase = JumpPhase.Grounded
            log("建立基线: footY=${sample.footY.fmt}, torso=${sample.torsoLength.fmt}")
            return result(TrackingQuality.Tracking, countedThisFrame = false)
        }

        val lift = ((currentBaseline - sample.footY) / sample.torsoLength).coerceAtLeast(0f)
        var counted = false
        val oldPhase = phase
        log("lift=${lift.fmt} baseline=${currentBaseline.fmt} footY=${sample.footY.fmt} phase=$phase")

        val newPhase = when (phase) {
            JumpPhase.Searching -> JumpPhase.Grounded
            JumpPhase.Grounded -> {
                baselineFootY = smoothBaseline(currentBaseline, sample.footY, lift)
                if (lift >= WeakJumpThreshold) {
                    jumpStartMs = frame.timestampMs
                    jumpMaxLift = lift
                    jumpSampleCount = 1
                    log("起跳: lift=${lift.fmt} threshold=$RisingThreshold timestamp=${frame.timestampMs}")
                    JumpPhase.Rising
                } else {
                    JumpPhase.Grounded
                }
            }
            JumpPhase.Rising -> when {
                lift >= AirborneThreshold -> {
                    jumpMaxLift = maxOf(jumpMaxLift, lift)
                    jumpSampleCount += 1
                    log("进入空中: lift=${lift.fmt} threshold=$AirborneThreshold")
                    JumpPhase.Airborne
                }
                frame.timestampMs - phaseStartMs > MaxRisingMs -> {
                    log("Rising 超时(${frame.timestampMs - phaseStartMs}ms)，重置到 Grounded")
                    baselineFootY = sample.footY
                    jumpMaxLift = 0f
                    jumpSampleCount = 0
                    JumpPhase.Grounded
                }
                lift <= GroundThreshold -> {
                    counted = maybeCountLanding(
                        frame.timestampMs,
                        sample.footY,
                        lift,
                        reason = "低帧率回落",
                    )
                    JumpPhase.Grounded
                }
                else -> {
                    jumpMaxLift = maxOf(jumpMaxLift, lift)
                    jumpSampleCount += 1
                    JumpPhase.Rising
                }
            }
            JumpPhase.Airborne -> {
                jumpMaxLift = maxOf(jumpMaxLift, lift)
                jumpSampleCount += 1
                if (lift <= GroundThreshold) {
                    counted = maybeCountLanding(
                        frame.timestampMs,
                        sample.footY,
                        lift,
                        reason = "空中直接落地",
                    )
                    JumpPhase.Grounded
                } else if (lift <= LandingThreshold) {
                    log("开始落地: lift=${lift.fmt} threshold=$LandingThreshold timestamp=${frame.timestampMs}")
                    JumpPhase.Landing
                } else if (frame.timestampMs - phaseStartMs > MaxAirborneMs) {
                    log("Airborne 超时(${frame.timestampMs - phaseStartMs}ms)，重置到 Grounded")
                    baselineFootY = sample.footY
                    jumpMaxLift = 0f
                    jumpSampleCount = 0
                    JumpPhase.Grounded
                } else {
                    JumpPhase.Airborne
                }
            }
            JumpPhase.Landing -> {
                if (lift <= GroundThreshold) {
                    counted = maybeCountLanding(
                        frame.timestampMs,
                        sample.footY,
                        lift,
                        reason = "落地",
                    )
                    JumpPhase.Grounded
                } else {
                    jumpMaxLift = maxOf(jumpMaxLift, lift)
                    jumpSampleCount += 1
                    JumpPhase.Landing
                }
            }
        }

        if (newPhase != phase) {
            phaseStartMs = frame.timestampMs
            log("phase 转换: $phase -> $newPhase")
        }
        phase = newPhase
        if (oldPhase != phase || phase == JumpPhase.Airborne || phase == JumpPhase.Rising) {
            log(
                "帧@${frame.timestampMs}: phase=$phase " +
                    "lift=${lift.fmt} baseline=${currentBaseline.fmt} footY=${sample.footY.fmt}",
            )
        }

        return result(TrackingQuality.Tracking, counted)
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

    private fun smoothBaseline(
        previous: Float,
        footY: Float,
        lift: Float,
    ): Float {
        val factor = if (lift <= GroundThreshold) 0.08f else 0.01f
        return previous * (1f - factor) + footY * factor
    }

    private fun maybeCountLanding(
        timestampMs: Long,
        footY: Float,
        lift: Float,
        reason: String,
    ): Boolean {
        val airTimeMs = timestampMs - jumpStartMs
        val timeSinceLastCount = timestampMs - lastCountMs
        val canCountAgain = timeSinceLastCount >= RefractoryMs
        val isStandardJump = jumpMaxLift >= ValidJumpThreshold
        val isWeakJump = jumpMaxLift >= WeakJumpThreshold && jumpSampleCount >= MinWeakJumpSamples
        val enoughLift = isStandardJump || isWeakJump
        val minAirTime = if (isStandardJump) MinAirTimeMs else MinWeakAirTimeMs
        val reasonableAirTime = airTimeMs in minAirTime..MaxJumpDurationMs
        val counted = enoughLift && reasonableAirTime && canCountAgain

        if (counted) {
            count += 1
            lastCountMs = timestampMs
            log(
                "计数成功($reason)! count=$count, airTime=${airTimeMs}ms, " +
                    "maxLift=${jumpMaxLift.fmt}, samples=$jumpSampleCount, sinceLast=${timeSinceLastCount}ms",
            )
        } else {
            when {
                !enoughLift ->
                    log(
                        "拒绝计数($reason): 跳跃高度不足(maxLift=${jumpMaxLift.fmt} < " +
                            "${WeakJumpThreshold.fmt}), samples=$jumpSampleCount, lift=${lift.fmt}",
                    )
                !reasonableAirTime ->
                    log(
                        "拒绝计数($reason): 持续时间不合理(${airTimeMs}ms, " +
                            "range=${minAirTime}..${MaxJumpDurationMs}ms), lift=${lift.fmt}",
                    )
                !canCountAgain ->
                    log("拒绝计数($reason): 不应期内(${timeSinceLastCount}ms < ${RefractoryMs}ms)")
            }
        }

        baselineFootY = baselineFootY
            ?.let { previous ->
                if (!isStandardJump && footY < previous) {
                    previous
                } else {
                    smoothBaseline(previous, footY, lift = 0f)
                }
            }
            ?: footY
        jumpMaxLift = 0f
        jumpSampleCount = 0
        return counted
    }

    private fun PoseFrame.toSample(): PoseSample? {
        val torsoLength = torsoLengthOrFallback()

        val leftAnkle = landmarks[BodyLandmark.LeftAnkle]
        val rightAnkle = landmarks[BodyLandmark.RightAnkle]
        val leftHeel = landmarks[BodyLandmark.LeftHeel]
        val rightHeel = landmarks[BodyLandmark.RightHeel]

        val leftFootY = footYOrNull(leftAnkle, leftHeel)
        val rightFootY = footYOrNull(rightAnkle, rightHeel)

        val footY = when {
            leftFootY != null && rightFootY != null -> (leftFootY + rightFootY) / 2f
            leftFootY != null -> leftFootY
            rightFootY != null -> rightFootY
            else -> {
                log(
                    "脚部置信度不足: " +
                        "L ankle=${leftAnkle?.confidence?.fmt}/${leftHeel?.confidence?.fmt}, " +
                        "R ankle=${rightAnkle?.confidence?.fmt}/${rightHeel?.confidence?.fmt}",
                )
                return null
            }
        }

        return PoseSample(footY = footY, torsoLength = torsoLength)
    }

    private fun PoseFrame.torsoLengthOrFallback(): Float {
        val leftShoulder = required(BodyLandmark.LeftShoulder)
        val rightShoulder = required(BodyLandmark.RightShoulder)
        val leftHip = required(BodyLandmark.LeftHip)
        val rightHip = required(BodyLandmark.RightHip)

        val measuredTorsoLength = if (
            leftShoulder != null &&
            rightShoulder != null &&
            leftHip != null &&
            rightHip != null
        ) {
            val shoulderMid = midpoint(leftShoulder, rightShoulder)
            val hipMid = midpoint(leftHip, rightHip)
            distance(shoulderMid, hipMid)
        } else {
            null
        }

        return when {
            measuredTorsoLength != null && measuredTorsoLength >= MinTorsoLength -> measuredTorsoLength
            lastTorsoLength != null -> lastTorsoLength ?: FallbackTorsoLength
            else -> FallbackTorsoLength
        }
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

    private fun midpoint(first: PosePoint, second: PosePoint) = PosePoint(
        x = (first.x + second.x) / 2f,
        y = (first.y + second.y) / 2f,
        confidence = minOf(first.confidence, second.confidence),
    )

    private fun distance(first: PosePoint, second: PosePoint): Float {
        return hypot(first.x - second.x, first.y - second.y)
    }

    private fun log(message: String) {
        onLog?.invoke("[JumpCounter] $message")
    }

    private data class PoseSample(
        val footY: Float,
        val torsoLength: Float,
    )

    private companion object {
        const val MinLandmarkConfidence = 0.40f
        const val MinFootConfidence = 0.25f
        const val MinTorsoLength = 0.08f
        const val RisingThreshold = 0.045f
        const val AirborneThreshold = 0.058f
        const val ValidJumpThreshold = 0.052f
        const val WeakJumpThreshold = 0.038f
        const val LandingThreshold = 0.045f
        const val GroundThreshold = 0.026f
        const val MinAirTimeMs = 100L
        const val MinWeakAirTimeMs = 110L
        const val RefractoryMs = 160L
        const val MaxLostPoseMs = 750L
        const val MaxRisingMs = 300L
        const val MaxAirborneMs = 800L
        const val MaxJumpDurationMs = 900L
        const val MinWeakJumpSamples = 2
        const val FallbackTorsoLength = 0.27f
        const val AnkleWeight = 0.7f
        const val HeelWeight = 0.3f

        private val Float.fmt: String
            get() = String.format("%.3f", this)
    }
}
