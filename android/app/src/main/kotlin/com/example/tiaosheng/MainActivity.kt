package com.example.tiaosheng

import android.graphics.Color
import android.os.Looper
import android.net.Uri
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo as ExoMediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val CHANNEL_NAME = "tiaosheng/session_video_overlay"
private const val METHOD_COMPOSE_WITH_OVERLAY = "composeWithOverlay"
private const val LOG_TAG = "SessionVideoOverlay"

class MainActivity : FlutterActivity() {
  private val composeExecutor = Executors.newSingleThreadExecutor()
  private var jumpRopePoseAnalyzerChannel: JumpRopePoseAnalyzerChannel? = null

  override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
      .setMethodCallHandler { call, result ->
        if (call.method != METHOD_COMPOSE_WITH_OVERLAY) {
          result.notImplemented()
          return@setMethodCallHandler
        }
        handleComposeCall(call, result)
      }
    jumpRopePoseAnalyzerChannel = JumpRopePoseAnalyzerChannel(
      context = applicationContext,
      messenger = flutterEngine.dartExecutor.binaryMessenger,
    )
  }

  override fun onDestroy() {
    jumpRopePoseAnalyzerChannel?.dispose()
    jumpRopePoseAnalyzerChannel = null
    composeExecutor.shutdown()
    super.onDestroy()
  }

  private fun handleComposeCall(call: MethodCall, result: MethodChannel.Result) {
    val args = parseOverlayArgs(call)
    if (args == null) {
      result.error(
        "videoComposeFailed",
        "invalid_arguments",
        "inputPath is required and overlayItems must be valid",
      )
      return
    }

    composeExecutor.execute {
      try {
        val outputPath = composeWithOverlay(args)
        runOnUiThread { result.success(outputPath) }
      } catch (error: ComposeException) {
        runOnUiThread { result.error(error.code, error.message, error.detail) }
      } catch (error: Throwable) {
        runOnUiThread {
          result.error(
            "videoComposeFailed",
            error.message ?: "compose_failed",
            error.stackTraceToString(),
          )
        }
      }
    }
  }

  private fun parseOverlayArgs(call: MethodCall): OverlayArgs? {
    val inputPath = call.argument<String>("inputPath") ?: return null
    val overlayItems = parseOverlayItems(call) ?: return null
    return OverlayArgs(
      inputPath = inputPath,
      overlayItems = overlayItems,
    )
  }

  private fun parseOverlayItems(call: MethodCall): List<OverlayItem>? {
    val rawItems = call.argument<List<*>>("overlayItems") ?: emptyList<Any?>()
    val items = mutableListOf<OverlayItem>()
    for (rawItem in rawItems) {
      val map = rawItem as? Map<*, *> ?: return null
      val text = map["text"] as? String ?: return null
      val startMs = (map["startMs"] as? Number)?.toLong() ?: return null
      val endMs = (map["endMs"] as? Number)?.toLong() ?: return null
      val positionName = map["position"] as? String ?: return null
      val styleName = map["style"] as? String ?: return null
      val position = OverlayPosition.fromName(positionName) ?: return null
      val style = OverlayStyle.fromName(styleName) ?: return null
      items.add(
        OverlayItem(
          text = text,
          startMs = startMs,
          endMs = endMs,
          position = position,
          style = style,
        ),
      )
    }
    return items
  }

  @UnstableApi
  @Throws(ComposeException::class)
  private fun composeWithOverlay(args: OverlayArgs): String {
    val inputFile = File(args.inputPath)
    if (inputFile.exists().not()) {
      throw ComposeException("videoUnavailable", "video file is missing")
    }

    val outputFile = buildOutputFile()
    if (outputFile.exists()) {
      outputFile.delete()
    }

    val editedMediaItem = createEditedMediaItem(inputFile, args)
    try {
      runTransformerBlocking(
        editedMediaItem = editedMediaItem,
        outputPath = outputFile.absolutePath,
        preferSoftwareDecoder = false,
      )
    } catch (error: ExportException) {
      if (shouldRetryWithSoftwareDecoder(error)) {
        Log.w(
          LOG_TAG,
          "Hardware decoder export failed, retrying with software-preferred decoders: ${
            exportExceptionSummary(error)
          }",
        )
        if (outputFile.exists()) {
          outputFile.delete()
        }
        try {
          runTransformerBlocking(
            editedMediaItem = editedMediaItem,
            outputPath = outputFile.absolutePath,
            preferSoftwareDecoder = true,
          )
        } catch (retryError: ExportException) {
          throw toComposeException(
            retryError,
            previousAttemptSummary = exportExceptionSummary(error),
          )
        }
      } else {
        throw toComposeException(error)
      }
    }

    if (outputFile.exists().not()) {
      throw ComposeException("videoComposeOutputMissing", "output file is missing")
    }
    return outputFile.absolutePath
  }

  @Throws(ComposeException::class)
  private fun buildOutputFile(): File {
    val outputDirectory = File(filesDir, "session_videos")
    if (outputDirectory.exists().not() && outputDirectory.mkdirs().not()) {
      throw ComposeException(
        "videoComposeFailed",
        "failed to create local video directory",
        outputDirectory.absolutePath,
      )
    }
    return File(
      outputDirectory,
      "jump_session_overlay_${System.currentTimeMillis()}.mp4",
    )
  }

  @UnstableApi
  private fun createEditedMediaItem(inputFile: File, args: OverlayArgs): EditedMediaItem {
    val overlays = args.overlayItems.map(::TimelineTextOverlay)
    val overlayEffect = OverlayEffect(overlays)
    val effects = Effects(emptyList(), listOf(overlayEffect))
    return EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(inputFile)))
      .setEffects(effects)
      .build()
  }

  @UnstableApi
  @Throws(ComposeException::class)
  private fun runTransformerBlocking(
    editedMediaItem: EditedMediaItem,
    outputPath: String,
    preferSoftwareDecoder: Boolean,
  ) {
    val latch = CountDownLatch(1)
    var exportError: ExportException? = null

    val transformer = runOnMainThreadBlocking {
      val decoderFactory = buildDecoderFactory(preferSoftwareDecoder)
      val assetLoaderFactory = DefaultAssetLoaderFactory(
        applicationContext,
        decoderFactory,
        Clock.DEFAULT,
      )
      Transformer.Builder(applicationContext)
        .setAssetLoaderFactory(assetLoaderFactory)
        .setLooper(mainLooper)
        .addListener(
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              latch.countDown()
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              exportError = exportException
              latch.countDown()
            }
          },
        )
        .build()
    }

    runOnMainThreadBlocking {
      transformer.start(editedMediaItem, outputPath)
    }

    val finished = latch.await(3, TimeUnit.MINUTES)
    if (finished.not()) {
      runOnMainThreadBlocking {
        transformer.cancel()
      }
      throw ComposeException("videoComposeFailed", "compose timeout")
    }

    val error = exportError
    if (error != null) {
      throw error
    }
  }

  @UnstableApi
  private fun buildDecoderFactory(preferSoftwareDecoder: Boolean): DefaultDecoderFactory {
    val builder = DefaultDecoderFactory.Builder(applicationContext)
      .setEnableDecoderFallback(true)
      .setShouldConfigureOperatingRate(false)
      .setListener(
        object : DefaultDecoderFactory.Listener {
          override fun onCodecInitialized(
            codecName: String,
            availableCodecInitExceptions: List<ExportException>,
          ) {
            if (availableCodecInitExceptions.isEmpty()) {
              Log.i(LOG_TAG, "Decoder initialized: $codecName")
              return
            }

            val initFailures = availableCodecInitExceptions.joinToString(" | ") {
              exportExceptionSummary(it)
            }
            Log.w(
              LOG_TAG,
              "Decoder initialized after fallback: $codecName ; previous failures: $initFailures",
            )
          }
        },
      )

    if (preferSoftwareDecoder) {
      builder.setMediaCodecSelector(softwarePreferredCodecSelector())
    }

    return builder.build()
  }

  @UnstableApi
  private fun softwarePreferredCodecSelector(): MediaCodecSelector {
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
      MediaCodecUtil
        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        .sortedWith(
          compareByDescending<ExoMediaCodecInfo> { it.softwareOnly }
            .thenByDescending { !it.hardwareAccelerated }
            .thenBy { it.name },
        )
    }
  }

  private fun shouldRetryWithSoftwareDecoder(error: ExportException): Boolean {
    val codecInfo = error.codecInfo ?: return false
    if (!codecInfo.isDecoder || !codecInfo.isVideo) {
      return false
    }

    val isDecoderFailure =
      error.errorCode == ExportException.ERROR_CODE_DECODING_FAILED ||
        error.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED
    if (!isDecoderFailure) {
      return false
    }

    val codecName = codecInfo.name.orEmpty()
    return codecName.startsWith("OMX.hisi", ignoreCase = true) ||
      codecName.startsWith("OMX.", ignoreCase = true)
  }

  private fun toComposeException(
    error: ExportException,
    previousAttemptSummary: String? = null,
  ): ComposeException {
    Log.e(LOG_TAG, "Video compose export failed: ${exportExceptionSummary(error)}", error)
    return ComposeException(
      "videoComposeFailed",
      error.message ?: "transformer_error",
      exportExceptionDetail(error, previousAttemptSummary),
    )
  }

  private fun exportExceptionSummary(error: ExportException): String {
    val codecName = error.codecInfo?.name ?: "unknown_codec"
    return "${error.errorCodeName}|codec=$codecName|message=${error.message ?: "empty_message"}"
  }

  private fun exportExceptionDetail(
    error: ExportException,
    previousAttemptSummary: String? = null,
  ): String {
    val codecInfo = error.codecInfo
    val codecName = codecInfo?.name ?: "unknown_codec"
    val configurationFormat = codecInfo?.configurationFormat ?: "unknown_format"
    return buildString {
      append("errorCode=")
      append(error.errorCode)
      append(' ')
      append("errorCodeName=")
      append(error.errorCodeName)
      append(' ')
      append("codec=")
      append(codecName)
      append(' ')
      append("format=")
      append(configurationFormat)
      if (previousAttemptSummary != null) {
        append(' ')
        append("previousAttempt=")
        append(previousAttemptSummary)
      }
    }
  }

  @Throws(ComposeException::class)
  private fun <T> runOnMainThreadBlocking(block: () -> T): T {
    if (Looper.myLooper() == mainLooper) {
      return block()
    }

    val latch = CountDownLatch(1)
    var result: T? = null
    var failure: Throwable? = null

    runOnUiThread {
      try {
        result = block()
      } catch (error: Throwable) {
        failure = error
      } finally {
        latch.countDown()
      }
    }

    val completed = latch.await(10, TimeUnit.SECONDS)
    if (completed.not()) {
      throw ComposeException("videoComposeFailed", "main thread dispatch timeout")
    }

    failure?.let { error ->
      throw ComposeException(
        "videoComposeFailed",
        error.message ?: "main_thread_dispatch_failed",
        error.stackTraceToString(),
      )
    }

    @Suppress("UNCHECKED_CAST")
    return result as T
  }
}

data class OverlayArgs(
  val inputPath: String,
  val overlayItems: List<OverlayItem>,
)

data class OverlayItem(
  val text: String,
  val startMs: Long,
  val endMs: Long,
  val position: OverlayPosition,
  val style: OverlayStyle,
)

enum class OverlayPosition {
  CENTER,
  TOP_LEFT,
  TOP_RIGHT,
  BOTTOM_CENTER,
  ;

  companion object {
    fun fromName(value: String): OverlayPosition? {
      return when (value) {
        "center" -> CENTER
        "topLeft" -> TOP_LEFT
        "topRight" -> TOP_RIGHT
        "bottomCenter" -> BOTTOM_CENTER
        else -> null
      }
    }
  }
}

enum class OverlayStyle {
  COUNTDOWN,
  BADGE,
  SUBTITLE,
  ;

  companion object {
    fun fromName(value: String): OverlayStyle? {
      return when (value) {
        "countdown" -> COUNTDOWN
        "badge" -> BADGE
        "subtitle" -> SUBTITLE
        else -> null
      }
    }
  }
}

class ComposeException(
  val code: String,
  override val message: String,
  val detail: String? = null,
) : Exception(message)

@UnstableApi
private class TimelineTextOverlay(
  private val item: OverlayItem,
) : TextOverlay() {
  private val style = textStyleFor(item.style)
  private val renderedText = styledText(
    item.text.ifBlank { " " },
    style.textSize,
    style.withBadge,
  )
  private val visibleSettings = overlaySettingsFor(item.position)
  private val hiddenSettings = OverlaySettings.Builder().setAlphaScale(0f).build()

  override fun getText(presentationTimeUs: Long): SpannableString {
    return renderedText
  }

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
    return if (isHidden(presentationTimeUs)) hiddenSettings else visibleSettings
  }

  private fun isHidden(presentationTimeUs: Long): Boolean {
    val presentationTimeMs = presentationTimeUs / 1_000L
    return presentationTimeMs < item.startMs || presentationTimeMs >= item.endMs
  }
}

private data class OverlayTextStyle(
  val textSize: Int,
  val withBadge: Boolean,
)

private fun overlaySettingsFor(position: OverlayPosition): OverlaySettings {
  return when (position) {
    OverlayPosition.CENTER -> OverlaySettings.Builder().build()
    OverlayPosition.TOP_LEFT -> OverlaySettings.Builder()
      .setBackgroundFrameAnchor(-0.92f, 0.86f)
      .setOverlayFrameAnchor(-1f, 1f)
      .build()
    OverlayPosition.TOP_RIGHT -> OverlaySettings.Builder()
      .setBackgroundFrameAnchor(0.92f, 0.86f)
      .setOverlayFrameAnchor(1f, 1f)
      .build()
    OverlayPosition.BOTTOM_CENTER -> OverlaySettings.Builder()
      .setBackgroundFrameAnchor(0f, -0.86f)
      .setOverlayFrameAnchor(0.5f, 0f)
      .build()
  }
}

private fun textStyleFor(style: OverlayStyle): OverlayTextStyle {
  return when (style) {
    OverlayStyle.COUNTDOWN -> OverlayTextStyle(textSize = 104, withBadge = false)
    OverlayStyle.BADGE -> OverlayTextStyle(textSize = 54, withBadge = true)
    OverlayStyle.SUBTITLE -> OverlayTextStyle(textSize = 26, withBadge = true)
  }
}

private fun styledText(
  value: String,
  textSize: Int,
  withBadge: Boolean,
): SpannableString {
  val content = if (withBadge) "  $value  " else value
  val spannable = SpannableString(content)
  spannable.setSpan(
    ForegroundColorSpan(Color.WHITE),
    0,
    content.length,
    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
  )
  spannable.setSpan(
    StyleSpan(Typeface.BOLD),
    0,
    content.length,
    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
  )
  spannable.setSpan(
    AbsoluteSizeSpan(textSize, true),
    0,
    content.length,
    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
  )
  if (withBadge) {
    spannable.setSpan(
      BackgroundColorSpan(Color.argb(176, 0, 0, 0)),
      0,
      content.length,
      Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
  }
  return spannable
}
