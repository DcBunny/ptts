package com.example.tiaosheng

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import io.flutter.FlutterInjector
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val POSE_CHANNEL_NAME = "tiaosheng/jump_rope_pose_analyzer"
private const val MODEL_ASSET_PATH = "assets/models/pose_landmarker_lite.task"

class JumpRopePoseAnalyzerChannel(
  private val context: Context,
  messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler {
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private val channel = MethodChannel(messenger, POSE_CHANNEL_NAME)
  private var poseLandmarker: PoseLandmarker? = null
  private var isSessionStarted = false

  init {
    channel.setMethodCallHandler(this)
  }

  fun dispose() {
    channel.setMethodCallHandler(null)
    executor.shutdown()
    closeLandmarker()
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "initialize" -> runAsync(result) {
        initializeLandmarker()
        null
      }
      "startSession" -> runAsync(result) {
        requireLandmarker()
        isSessionStarted = true
        null
      }
      "analyzeFrame" -> runAsync(result) {
        requireStartedSession()
        val frame = AndroidPoseFrame.fromCall(call)
        analyzeFrame(frame)
      }
      "stopSession" -> runAsync(result) {
        isSessionStarted = false
        null
      }
      "dispose" -> runAsync(result) {
        isSessionStarted = false
        closeLandmarker()
        null
      }
      else -> result.notImplemented()
    }
  }

  private fun runAsync(
    result: MethodChannel.Result,
    task: () -> Any?,
  ) {
    executor.execute {
      try {
        val response = task()
        postSuccess(result, response)
      } catch (error: PoseAnalyzerException) {
        postError(result, error)
      } catch (error: Throwable) {
        postError(
          result,
          PoseAnalyzerException(
            code = "jumpCounterRuntimeFailed",
            errorMessage = error.message ?: "pose_analyzer_failed",
            details = error.stackTraceToString(),
          ),
        )
      }
    }
  }

  private fun postSuccess(result: MethodChannel.Result, payload: Any?) {
    android.os.Handler(context.mainLooper).post {
      result.success(payload)
    }
  }

  private fun postError(result: MethodChannel.Result, error: PoseAnalyzerException) {
    android.os.Handler(context.mainLooper).post {
      result.error(error.code, error.errorMessage, error.details)
    }
  }

  private fun initializeLandmarker() {
    if (poseLandmarker != null) {
      return
    }
    val assetPath = FlutterInjector.instance().flutterLoader()
      .getLookupKeyForAsset(MODEL_ASSET_PATH)
    val baseOptions = BaseOptions.builder()
      .setModelAssetPath(assetPath)
      .build()
    val options = PoseLandmarker.PoseLandmarkerOptions.builder()
      .setBaseOptions(baseOptions)
      .setRunningMode(RunningMode.VIDEO)
      .setMinPosePresenceConfidence(0.5f)
      .setMinPoseDetectionConfidence(0.5f)
      .setMinTrackingConfidence(0.5f)
      .setNumPoses(2)
      .build()
    poseLandmarker = PoseLandmarker.createFromOptions(context, options)
  }

  private fun analyzeFrame(frame: AndroidPoseFrame): Map<String, Any?> {
    val startedAt = System.nanoTime()
    val mpImage = frame.toMpImage()
    val result = requireLandmarker().detectForVideo(mpImage, frame.timestampMs.toLong())
    val latencyMs = ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
    return serializeResult(frame.timestampMs, latencyMs, result)
  }

  private fun serializeResult(
    timestampMs: Int,
    analysisLatencyMs: Int,
    result: PoseLandmarkerResult,
  ): Map<String, Any?> {
    val poses = result.landmarks().map { landmarks ->
      mapOf(
        "landmarks" to landmarks.map(::serializeLandmark),
      )
    }
    return mapOf(
      "timestampMs" to timestampMs,
      "analysisLatencyMs" to analysisLatencyMs,
      "poses" to poses,
    )
  }

  private fun serializeLandmark(landmark: NormalizedLandmark): Map<String, Any> {
    return mapOf(
      "x" to landmark.x(),
      "y" to landmark.y(),
      "z" to landmark.z(),
      // Some devices/runtime paths may omit these optional fields.
      // Fall back to 1.0 instead of 0.0 to avoid dropping all frames as low-confidence.
      "visibility" to landmark.visibility().orElse(1f),
      "presence" to landmark.presence().orElse(1f),
    )
  }

  private fun requireLandmarker(): PoseLandmarker {
    return poseLandmarker ?: throw PoseAnalyzerException(
      code = "jumpCounterInitFailed",
      errorMessage = "pose_landmarker_not_initialized",
    )
  }

  private fun requireStartedSession() {
    if (!isSessionStarted) {
      throw PoseAnalyzerException(
        code = "jumpCounterRuntimeFailed",
        errorMessage = "pose_session_not_started",
      )
    }
  }

  private fun closeLandmarker() {
    poseLandmarker?.close()
    poseLandmarker = null
  }
}

private data class AndroidPoseFrame(
  val width: Int,
  val height: Int,
  val rotationDegrees: Int,
  val timestampMs: Int,
  val format: String,
  val planes: List<AndroidPosePlane>,
) {
  companion object {
    fun fromCall(call: MethodCall): AndroidPoseFrame {
      val arguments = call.arguments as? Map<*, *>
        ?: throw PoseAnalyzerException(
          code = "jumpCounterRuntimeFailed",
          errorMessage = "invalid_frame_arguments",
        )
      val rawPlanes = arguments["planes"] as? List<*>
        ?: throw PoseAnalyzerException(
          code = "jumpCounterRuntimeFailed",
          errorMessage = "invalid_frame_planes",
        )
      return AndroidPoseFrame(
        width = (arguments["width"] as? Number)?.toInt() ?: 0,
        height = (arguments["height"] as? Number)?.toInt() ?: 0,
        rotationDegrees = (arguments["rotationDegrees"] as? Number)?.toInt() ?: 0,
        timestampMs = (arguments["timestampMs"] as? Number)?.toInt() ?: 0,
        format = arguments["format"] as? String ?: "unknown",
        planes = rawPlanes.map { item ->
          val planeMap = item as? Map<*, *>
            ?: throw PoseAnalyzerException(
              code = "jumpCounterRuntimeFailed",
              errorMessage = "invalid_frame_plane",
            )
          AndroidPosePlane(
            bytes = planeMap["bytes"] as? ByteArray ?: byteArrayOf(),
            bytesPerRow = (planeMap["bytesPerRow"] as? Number)?.toInt() ?: 0,
            bytesPerPixel = (planeMap["bytesPerPixel"] as? Number)?.toInt(),
          )
        },
      )
    }
  }

  fun toMpImage(): MPImage {
    val bitmap = when (format) {
      "nv21", "yuv420" -> buildBitmapFromYuv()
      else -> throw PoseAnalyzerException(
        code = "jumpCounterRuntimeFailed",
        errorMessage = "unsupported_android_frame_format:$format",
      )
    }
    val orientedBitmap = rotateBitmap(bitmap, rotationDegrees)
    return BitmapImageBuilder(orientedBitmap).build()
  }

  private fun buildBitmapFromYuv(): Bitmap {
    val nv21Bytes = if (format == "nv21") {
      planes.firstOrNull()?.bytes ?: byteArrayOf()
    } else {
      planes.toNv21(width = width, height = height)
    }
    if (nv21Bytes.isEmpty()) {
      throw PoseAnalyzerException(
        code = "jumpCounterRuntimeFailed",
        errorMessage = "empty_android_frame_bytes",
      )
    }

    val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)
    val outputStream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, outputStream)
    val jpegBytes = outputStream.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
      ?: throw PoseAnalyzerException(
        code = "jumpCounterRuntimeFailed",
        errorMessage = "failed_to_decode_android_frame",
      )
  }

  private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) {
      return bitmap
    }
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }
}

private data class AndroidPosePlane(
  val bytes: ByteArray,
  val bytesPerRow: Int,
  val bytesPerPixel: Int?,
)

private fun List<AndroidPosePlane>.toNv21(width: Int, height: Int): ByteArray {
  if (size < 3) {
    return firstOrNull()?.bytes ?: byteArrayOf()
  }

  val yPlane = this[0]
  val uPlane = this[1]
  val vPlane = this[2]
  val frameSize = width * height
  val nv21 = ByteArray(frameSize + (frameSize / 2))

  for (row in 0 until height) {
    val inputOffset = row * yPlane.bytesPerRow
    val outputOffset = row * width
    System.arraycopy(yPlane.bytes, inputOffset, nv21, outputOffset, width)
  }

  val chromaHeight = height / 2
  val chromaWidth = width / 2
  var outputOffset = frameSize
  for (row in 0 until chromaHeight) {
    val uRowOffset = row * uPlane.bytesPerRow
    val vRowOffset = row * vPlane.bytesPerRow
    for (column in 0 until chromaWidth) {
      val uIndex = uRowOffset + (column * (uPlane.bytesPerPixel ?: 1))
      val vIndex = vRowOffset + (column * (vPlane.bytesPerPixel ?: 1))
      nv21[outputOffset++] = vPlane.bytes[vIndex]
      nv21[outputOffset++] = uPlane.bytes[uIndex]
    }
  }
  return nv21
}

private data class PoseAnalyzerException(
  val code: String,
  val errorMessage: String,
  val details: String? = null,
) : Exception(errorMessage)
