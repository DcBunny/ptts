package com.example.ptts.features.parent_camera.data

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.PoseFrame
import com.example.ptts.features.parent_camera.domain.PosePoint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions

class PoseFrameAnalyzer(
    private val onResult: (PoseAnalysisResult) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
    private val detector = PoseDetection.getClient(
        AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build(),
    )

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            Log.i(TAG, "analyze: mediaImage is null, closing proxy")
            imageProxy.close()
            return
        }

        val startedAt = System.currentTimeMillis()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val dimensions = imageProxy.analysisDimensions()
        Log.i(TAG, "analyze: frame startedAt=$startedAt size=${dimensions.width}x${dimensions.height} rotation=$rotation")

        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(inputImage)
            .addOnSuccessListener { pose ->
                val inferenceMs = System.currentTimeMillis() - startedAt
                val landmarkCount = pose.allPoseLandmarks.size
                Log.i(TAG, "analyze: ML Kit success, landmarks=$landmarkCount inferenceMs=$inferenceMs")
                onResult(
                    PoseAnalysisResult(
                        frame = pose.toPoseFrame(
                            timestampMs = startedAt,
                            imageWidth = dimensions.width,
                            imageHeight = dimensions.height,
                        ),
                        inferenceMs = inferenceMs,
                    ),
                )
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "analyze: ML Kit failed", error)
                onError(error)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }

    private fun Pose.toPoseFrame(
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
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
)
