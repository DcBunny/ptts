package com.example.ptts.features.parent_camera.data

import android.util.Log
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.CameraMotion
import com.example.ptts.features.parent_camera.domain.FrameLightMetrics
import com.example.ptts.features.parent_camera.domain.PoseFrame
import com.example.ptts.features.parent_camera.domain.PosePoint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.util.concurrent.Executors

class PoseFrameAnalyzer(
    private val onResult: (PoseAnalysisResult) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val detector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build(),
    )
    private val motionEstimator = CameraMotionEstimator()
    private val lightMetricsCalculator = FrameLightMetricsCalculator()

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            Log.i(TAG, "analyze: mediaImage is null, closing proxy")
            imageProxy.close()
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        val captureTimestampMs = (imageProxy.imageInfo.timestamp / 1_000_000L)
            .takeIf { it > 0L }
            ?: startedAt
        val rotation = imageProxy.imageInfo.rotationDegrees
        val dimensions = imageProxy.analysisDimensions()
        val motion = motionEstimator.estimate(imageProxy)
        val normalizedMotion = motion.toCameraMotion(rotation)
        val lightMetrics = imageProxy.planes.firstOrNull()?.let { plane ->
            lightMetricsCalculator.measure(
                plane = LumaPlaneView(
                    buffer = plane.buffer,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    cropLeft = imageProxy.cropRect.left,
                    cropTop = imageProxy.cropRect.top,
                    cropWidth = imageProxy.cropRect.width(),
                    cropHeight = imageProxy.cropRect.height(),
                ),
                rotationDegrees = rotation,
                timestampMs = captureTimestampMs,
            )
        }
        if (VerboseLogging) {
            Log.i(
                TAG,
                "analyze: frame timestamp=$captureTimestampMs " +
                    "size=${dimensions.width}x${dimensions.height} rotation=$rotation",
            )
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        // Listeners run on the analysis executor. The default main-thread executor added the UI
        // thread to the critical path and let a busy frame delay the next analysis frame.
        detector.process(inputImage)
            .addOnSuccessListener(analysisExecutor) { pose ->
                val inferenceMs = SystemClock.elapsedRealtime() - startedAt
                if (VerboseLogging) {
                    Log.i(TAG, "analyze: ML Kit success, landmarks=${pose.allPoseLandmarks.size} inferenceMs=$inferenceMs")
                }
                val frame = pose.toPoseFrame(
                    timestampMs = captureTimestampMs,
                    imageWidth = dimensions.width,
                    imageHeight = dimensions.height,
                    motion = normalizedMotion,
                    lightMetrics = lightMetrics,
                )
                lightMetricsCalculator.updatePersonRegion(frame.landmarks, captureTimestampMs)
                onResult(
                    PoseAnalysisResult(
                        frame = frame,
                        inferenceMs = inferenceMs,
                        analysisAspectRatio = dimensions.width.toFloat() /
                            dimensions.height.toFloat().coerceAtLeast(1f),
                        lightMetrics = lightMetrics,
                    ),
                )
            }
            .addOnFailureListener(analysisExecutor) { error ->
                Log.e(TAG, "analyze: ML Kit failed", error)
                onError(error)
            }
            .addOnCompleteListener(analysisExecutor) {
                imageProxy.close()
            }
    }

    fun close() {
        motionEstimator.reset()
        lightMetricsCalculator.reset()
        detector.close()
        analysisExecutor.shutdown()
    }

    private fun Pose.toPoseFrame(
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        motion: CameraMotion,
        lightMetrics: FrameLightMetrics?,
    ): PoseFrame {
        val landmarks = LandmarkTypes.mapNotNull { (bodyLandmark, mlKitType) ->
            val landmark = getPoseLandmark(mlKitType) ?: return@mapNotNull null
            bodyLandmark to PosePoint(
                x = landmark.position.x / imageWidth,
                y = landmark.position.y / imageHeight,
                confidence = landmark.inFrameLikelihood,
            )
        }.toMap()

        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = landmarks,
            cameraMotion = motion,
            lightMetrics = lightMetrics,
        )
    }

    private fun CameraMotionEstimator.MotionEstimate.toCameraMotion(rotation: Int): CameraMotion {
        val (x, y) = when (rotation) {
            90 -> -offsetY to offsetX
            180 -> -offsetX to -offsetY
            270 -> offsetY to -offsetX
            else -> offsetX to offsetY
        }
        return CameraMotion(
            offsetX = x,
            offsetY = y,
            available = true,
            reliable = reliable,
            magnitude = magnitude,
        )
    }

    private fun ImageProxy.analysisDimensions(): AnalysisDimensions {
        return if (imageInfo.rotationDegrees == 90 || imageInfo.rotationDegrees == 270) {
            AnalysisDimensions(width = height, height = width)
        } else {
            AnalysisDimensions(width = width, height = height)
        }
    }

    private data class AnalysisDimensions(
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val TAG = "JumpDebug"

        /** Per-frame logging is useful while tuning thresholds and costly in normal use. */
        const val VerboseLogging = false

        val LandmarkTypes = listOf(
            BodyLandmark.LeftShoulder to PoseLandmark.LEFT_SHOULDER,
            BodyLandmark.RightShoulder to PoseLandmark.RIGHT_SHOULDER,
            BodyLandmark.LeftHip to PoseLandmark.LEFT_HIP,
            BodyLandmark.RightHip to PoseLandmark.RIGHT_HIP,
            BodyLandmark.LeftKnee to PoseLandmark.LEFT_KNEE,
            BodyLandmark.RightKnee to PoseLandmark.RIGHT_KNEE,
            BodyLandmark.LeftAnkle to PoseLandmark.LEFT_ANKLE,
            BodyLandmark.RightAnkle to PoseLandmark.RIGHT_ANKLE,
            BodyLandmark.LeftHeel to PoseLandmark.LEFT_HEEL,
            BodyLandmark.RightHeel to PoseLandmark.RIGHT_HEEL,
        )
    }
}

data class PoseAnalysisResult(
    val frame: PoseFrame,
    val inferenceMs: Long,
    /**
     * Aspect ratio (width / height) of the upright analysis image the landmarks were measured
     * in. Overlays must map landmarks through the same fill-center transform as the preview,
     * otherwise the skeleton is drawn in a different coordinate space than the camera image.
     */
    val analysisAspectRatio: Float = 1f,
    val lightMetrics: FrameLightMetrics? = frame.lightMetrics,
)
