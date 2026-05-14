package com.example.ptts.features.parent_camera.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "VideoOverlayProcessor"
private const val TIMEOUT_US = 10_000L

class VideoOverlayProcessor {

    fun process(
        inputFile: File,
        outputFile: File,
        overlayStates: List<OverlayFrameState>,
        onProgress: (Int) -> Unit = {},
    ): Result<Unit> = runCatching {
        require(overlayStates.isNotEmpty()) { "overlayStates must not be empty" }
        val timeline = OverlayTimeline(overlayStates)
        Log.d(TAG, "starting: input=${inputFile.absolutePath}, output=${outputFile.absolutePath}, metadata=${overlayStates.size}")

        // 1. 视频提取器
        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(inputFile.absolutePath)
        val videoTrackIndex = findVideoTrack(videoExtractor)
        require(videoTrackIndex >= 0) { "No video track found" }
        videoExtractor.selectTrack(videoTrackIndex)
        val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
        Log.d(TAG, "video track: index=$videoTrackIndex, format=$videoFormat")

        // 2. 音频预读取
        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(inputFile.absolutePath)
        val audioTrackIndex = findAudioTrack(audioExtractor)
        val hasAudio = audioTrackIndex >= 0
        val audioSamples = mutableListOf<AudioSample>()
        val audioFormat: MediaFormat? = if (hasAudio) {
            audioExtractor.selectTrack(audioTrackIndex)
            val format = audioExtractor.getTrackFormat(audioTrackIndex)
            readAllAudioSamples(audioExtractor, audioSamples)
            Log.d(TAG, "audio pre-read: ${audioSamples.size} samples, format=$format")
            format
        } else {
            Log.d(TAG, "no audio track")
            null
        }
        audioExtractor.release()

        // 3. 创建编解码器
        val decoder = createDecoder(videoFormat)
        val encoderConfig = createEncoder(videoFormat)

        // 4. 创建 Muxer
        val rotation = videoFormat.getRotation()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) {
            muxer.setOrientationHint(rotation)
        }

        try {
            processInternal(
                videoExtractor = videoExtractor,
                audioSamples = audioSamples,
                audioFormat = audioFormat,
                decoder = decoder,
                encoder = encoderConfig.codec,
                encoderColorFormat = encoderConfig.colorFormat,
                muxer = muxer,
                videoFormat = videoFormat,
                timeline = timeline,
                onProgress = onProgress,
            )
            Log.d(TAG, "completed successfully")
        } finally {
            videoExtractor.release()
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoderConfig.codec.stop() }
            runCatching { encoderConfig.codec.release() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
        Unit
    }.onFailure { error ->
        Log.e(TAG, "processing failed", error)
    }

    private fun processInternal(
        videoExtractor: MediaExtractor,
        audioSamples: List<AudioSample>,
        audioFormat: MediaFormat?,
        decoder: MediaCodec,
        encoder: MediaCodec,
        encoderColorFormat: Int,
        muxer: MediaMuxer,
        videoFormat: MediaFormat,
        timeline: OverlayTimeline,
        onProgress: (Int) -> Unit,
    ) {
        val videoWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val videoHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        require(videoWidth % 2 == 0 && videoHeight % 2 == 0) {
            "Video dimensions must be even for YUV420 encoding: ${videoWidth}x$videoHeight"
        }
        val totalDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        Log.d(TAG, "video dimensions: ${videoWidth}x${videoHeight}, durationUs=$totalDurationUs")

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(204, 17, 17, 17)
        }

        var videoTrackId = -1
        var audioTrackId = -1
        var muxerStarted = false

        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderInputDone = false
        var encoderOutputDone = false

        val decoderBufferInfo = MediaCodec.BufferInfo()
        val encoderBufferInfo = MediaCodec.BufferInfo()

        var framesProcessed = 0

        while (!encoderOutputDone) {
            drainEncoder(
                encoder = encoder,
                encoderBufferInfo = encoderBufferInfo,
                muxer = muxer,
                muxerStarted = { muxerStarted },
                videoTrackId = { videoTrackId },
                onFormatChanged = { newTrackId ->
                    videoTrackId = newTrackId
                    if (audioFormat != null) {
                        audioTrackId = muxer.addTrack(audioFormat)
                        Log.d(TAG, "audio track added, audioTrackId=$audioTrackId")
                    }
                    muxer.start()
                    muxerStarted = true
                    Log.d(TAG, "muxer started")
                    if (audioSamples.isNotEmpty()) {
                        writeAudioSamples(muxer, audioTrackId, audioSamples)
                        Log.d(TAG, "audio samples written: ${audioSamples.size}")
                    }
                },
                onEndOfStream = {
                    encoderOutputDone = true
                    Log.d(TAG, "encoder output EOS, framesProcessed=$framesProcessed")
                },
            )

            if (!decoderInputDone) {
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufIndex >= 0) {
                    val inputBuf = decoder.getInputBuffer(inputBufIndex)!!
                    val sampleSize = videoExtractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, 0, 0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        decoderInputDone = true
                        Log.d(TAG, "decoder EOS sent")
                    } else {
                        decoder.queueInputBuffer(
                            inputBufIndex, 0, sampleSize,
                            videoExtractor.sampleTime, 0
                        )
                        videoExtractor.advance()
                    }
                }
            }

            if (!decoderOutputDone) {
                val outputBufIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US)
                if (outputBufIndex >= 0) {
                    val doRender = decoderBufferInfo.size != 0
                    if (doRender) {
                        val image = decoder.getOutputImage(outputBufIndex)
                            ?: throw IllegalStateException("Decoder did not provide an image for frame $framesProcessed")
                        val bitmap = imageToBitmap(image)
                        val canvas = Canvas(bitmap)
                        val elapsedMs = decoderBufferInfo.presentationTimeUs / 1000
                        drawOverlay(canvas, videoWidth, videoHeight, elapsedMs, timeline, paint, bgPaint)
                        queueEncoderFrame(
                            encoder = encoder,
                            encoderColorFormat = encoderColorFormat,
                            bitmap = bitmap,
                            presentationTimeUs = decoderBufferInfo.presentationTimeUs,
                            encoderBufferInfo = encoderBufferInfo,
                            muxer = muxer,
                            muxerStarted = { muxerStarted },
                            videoTrackId = { videoTrackId },
                            onFormatChanged = { newTrackId ->
                                videoTrackId = newTrackId
                                if (audioFormat != null) {
                                    audioTrackId = muxer.addTrack(audioFormat)
                                    Log.d(TAG, "audio track added, audioTrackId=$audioTrackId")
                                }
                                muxer.start()
                                muxerStarted = true
                                Log.d(TAG, "muxer started")
                                if (audioSamples.isNotEmpty()) {
                                    writeAudioSamples(muxer, audioTrackId, audioSamples)
                                    Log.d(TAG, "audio samples written: ${audioSamples.size}")
                                }
                            },
                        )
                        bitmap.recycle()
                        image.close()
                        framesProcessed++
                    }
                    decoder.releaseOutputBuffer(outputBufIndex, false)
                    if ((decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderOutputDone = true
                        queueEncoderEndOfStream(
                            encoder = encoder,
                            presentationTimeUs = decoderBufferInfo.presentationTimeUs,
                            encoderBufferInfo = encoderBufferInfo,
                            muxer = muxer,
                            muxerStarted = { muxerStarted },
                            videoTrackId = { videoTrackId },
                            onFormatChanged = { newTrackId ->
                                videoTrackId = newTrackId
                                if (audioFormat != null) {
                                    audioTrackId = muxer.addTrack(audioFormat)
                                    Log.d(TAG, "audio track added, audioTrackId=$audioTrackId")
                                }
                                muxer.start()
                                muxerStarted = true
                                Log.d(TAG, "muxer started")
                                if (audioSamples.isNotEmpty()) {
                                    writeAudioSamples(muxer, audioTrackId, audioSamples)
                                    Log.d(TAG, "audio samples written: ${audioSamples.size}")
                                }
                            },
                        )
                        encoderInputDone = true
                        Log.d(TAG, "decoder output EOS, encoder EOS queued")
                    }

                    if (totalDurationUs > 0) {
                        val progress = ((decoderBufferInfo.presentationTimeUs * 100) / totalDurationUs).toInt()
                        onProgress(progress.coerceIn(0, 100))
                    }
                }
            }

            if (decoderInputDone && decoderOutputDone && encoderInputDone) {
                drainEncoder(
                    encoder = encoder,
                    encoderBufferInfo = encoderBufferInfo,
                    muxer = muxer,
                    muxerStarted = { muxerStarted },
                    videoTrackId = { videoTrackId },
                    onFormatChanged = { newTrackId ->
                        videoTrackId = newTrackId
                        if (audioFormat != null) {
                            audioTrackId = muxer.addTrack(audioFormat)
                            Log.d(TAG, "audio track added, audioTrackId=$audioTrackId")
                        }
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "muxer started")
                        if (audioSamples.isNotEmpty()) {
                            writeAudioSamples(muxer, audioTrackId, audioSamples)
                            Log.d(TAG, "audio samples written: ${audioSamples.size}")
                        }
                    },
                    onEndOfStream = {
                        encoderOutputDone = true
                        Log.d(TAG, "encoder output EOS, framesProcessed=$framesProcessed")
                    },
                )
            }
        }
    }

    private fun createDecoder(videoFormat: MediaFormat): MediaCodec {
        val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(videoFormat, null, null, 0)
        decoder.start()
        Log.d(TAG, "decoder created: $mime")
        return decoder
    }

    private fun createEncoder(videoFormat: MediaFormat): EncoderConfig {
        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val bitRate = if (videoFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
            videoFormat.getInteger(MediaFormat.KEY_BIT_RATE)
        } else {
            width * height * 4
        }
        val frameRate = if (videoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            videoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else {
            30
        }
        val iFrameInterval = if (videoFormat.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) {
            videoFormat.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL)
        } else {
            1
        }

        val codecInfo = selectAvcEncoder()
        val colorFormat = selectYuv420ColorFormat(codecInfo)
        val encoder = MediaCodec.createByCodecName(codecInfo.name)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        Log.d(TAG, "encoder created: ${codecInfo.name} ${width}x${height} @${frameRate}fps colorFormat=$colorFormat")
        return EncoderConfig(codec = encoder, colorFormat = colorFormat)
    }

    private fun selectAvcEncoder(): MediaCodecInfo {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return codecs.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { type ->
                type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
            } && runCatching { selectYuv420ColorFormat(info) }.isSuccess
        } ?: throw IllegalStateException("No AVC encoder with YUV420 input support")
    }

    private fun selectYuv420ColorFormat(codecInfo: MediaCodecInfo): Int {
        val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val supported = capabilities.colorFormats.toSet()
        return when {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            else -> throw IllegalStateException("Unsupported encoder color formats: $supported")
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun readAllAudioSamples(extractor: MediaExtractor, samples: MutableList<AudioSample>) {
        while (true) {
            val buffer = ByteBuffer.allocate(256 * 1024)
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            buffer.limit(sampleSize)
            val data = ByteArray(sampleSize)
            buffer.get(data)
            val info = MediaCodec.BufferInfo()
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            samples.add(AudioSample(ByteBuffer.wrap(data), info))
            extractor.advance()
        }
    }

    private fun queueEncoderFrame(
        encoder: MediaCodec,
        encoderColorFormat: Int,
        bitmap: Bitmap,
        presentationTimeUs: Long,
        encoderBufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        muxerStarted: () -> Boolean,
        videoTrackId: () -> Int,
        onFormatChanged: (Int) -> Unit,
    ) {
        val yuv = bitmapToYuv420(bitmap, encoderColorFormat)
        while (true) {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex)
                    ?: throw IllegalStateException("Encoder input buffer is null")
                inputBuffer.clear()
                require(inputBuffer.capacity() >= yuv.size) {
                    "Encoder input buffer too small: ${inputBuffer.capacity()} < ${yuv.size}"
                }
                inputBuffer.put(yuv)
                encoder.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
                return
            }
            drainEncoder(
                encoder = encoder,
                encoderBufferInfo = encoderBufferInfo,
                muxer = muxer,
                muxerStarted = muxerStarted,
                videoTrackId = videoTrackId,
                onFormatChanged = onFormatChanged,
                onEndOfStream = {},
            )
        }
    }

    private fun queueEncoderEndOfStream(
        encoder: MediaCodec,
        presentationTimeUs: Long,
        encoderBufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        muxerStarted: () -> Boolean,
        videoTrackId: () -> Int,
        onFormatChanged: (Int) -> Unit,
    ) {
        while (true) {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                return
            }
            drainEncoder(
                encoder = encoder,
                encoderBufferInfo = encoderBufferInfo,
                muxer = muxer,
                muxerStarted = muxerStarted,
                videoTrackId = videoTrackId,
                onFormatChanged = onFormatChanged,
                onEndOfStream = {},
            )
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        encoderBufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        muxerStarted: () -> Boolean,
        videoTrackId: () -> Int,
        onFormatChanged: (Int) -> Unit,
        onEndOfStream: () -> Unit,
    ) {
        while (true) {
            when (val status = encoder.dequeueOutputBuffer(encoderBufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newTrackId = muxer.addTrack(encoder.outputFormat)
                    Log.d(TAG, "encoder format changed, videoTrackId=$newTrackId")
                    onFormatChanged(newTrackId)
                }
                else -> {
                    if (status >= 0) {
                        val encodedData = encoder.getOutputBuffer(status)
                            ?: throw IllegalStateException("Encoder output buffer is null")
                        if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            encoderBufferInfo.size = 0
                        }
                        if (encoderBufferInfo.size != 0) {
                            check(muxerStarted()) { "Muxer has not started" }
                            encodedData.position(encoderBufferInfo.offset)
                            encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                            muxer.writeSampleData(videoTrackId(), encodedData, encoderBufferInfo)
                        }
                        val isEndOfStream =
                            (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(status, false)
                        if (isEndOfStream) {
                            onEndOfStream()
                            return
                        }
                    }
                }
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        return yuvToRgbBitmap(image, image.width, image.height)
    }

    private fun yuvToRgbBitmap(image: Image, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val uRowStride = image.planes[1].rowStride
        val vRowStride = image.planes[2].rowStride
        val uPixelStride = image.planes[1].pixelStride
        val vPixelStride = image.planes[2].pixelStride

        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIdx = row * yRowStride + col
                val y = (yBuffer.get(yIdx).toInt() and 0xFF)

                val uvRow = row shr 1
                val uvCol = col shr 1
                val uIdx = uvRow * uRowStride + uvCol * uPixelStride
                val vIdx = uvRow * vRowStride + uvCol * vPixelStride

                val u = (uBuffer.get(uIdx).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vIdx).toInt() and 0xFF) - 128

                // BT.601 整型快速转换
                val r = (y + ((359 * v) shr 8)).coerceIn(0, 255)
                val g = (y - ((88 * u + 183 * v) shr 8)).coerceIn(0, 255)
                val b = (y + ((454 * u) shr 8)).coerceIn(0, 255)

                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun drawOverlay(
        canvas: Canvas,
        videoWidth: Int,
        videoHeight: Int,
        elapsedMs: Long,
        timeline: OverlayTimeline,
        textPaint: Paint,
        bgPaint: Paint,
    ) {
        val meta = timeline.stateAt(elapsedMs)

        val paddingX = videoWidth * 0.03f
        val paddingY = videoHeight * 0.03f
        val badgeWidth = videoWidth * 0.28f
        val badgeHeight = videoHeight * 0.09f
        val cornerRadius = badgeHeight * 0.25f
        val textSize = badgeHeight * 0.5f

        val timeText = formatDurationForOverlay(meta.remainingSeconds)
        drawBadge(
            canvas, paddingX, paddingY, badgeWidth, badgeHeight,
            cornerRadius, textSize, timeText, textPaint, bgPaint
        )

        val countText = meta.jumpCount.toString()
        drawBadge(
            canvas, videoWidth - paddingX - badgeWidth, paddingY,
            badgeWidth, badgeHeight, cornerRadius, textSize, countText,
            textPaint, bgPaint
        )
    }

    private fun drawBadge(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        cornerRadius: Float,
        textSize: Float,
        text: String,
        textPaint: Paint,
        bgPaint: Paint,
    ) {
        val rect = RectF(left, top, left + width, top + height)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        textPaint.textSize = textSize
        canvas.drawText(
            text, left + width / 2, top + height / 2 + textSize / 3, textPaint
        )
    }

    private fun writeAudioSamples(
        muxer: MediaMuxer,
        trackId: Int,
        samples: List<AudioSample>,
    ) {
        for (sample in samples) {
            val data = sample.data.duplicate()
            data.position(0)
            data.limit(sample.info.size)
            muxer.writeSampleData(trackId, data, sample.info)
        }
    }

    private fun bitmapToYuv420(bitmap: Bitmap, colorFormat: Int): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val frameSize = width * height
        val chromaSize = frameSize / 4
        val output = ByteArray(frameSize + chromaSize * 2)
        val pixels = IntArray(frameSize)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var yIndex = 0
        val isSemiPlanar =
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        var uIndex = frameSize
        var vIndex = if (isSemiPlanar) frameSize + 1 else frameSize + chromaSize

        for (row in 0 until height) {
            for (col in 0 until width) {
                val pixel = pixels[row * width + col]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                output[yIndex++] = y.coerceIn(0, 255).toByte()

                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    output[uIndex++] = u.coerceIn(0, 255).toByte()
                    output[vIndex++] = v.coerceIn(0, 255).toByte()
                    if (isSemiPlanar) {
                        uIndex++
                        vIndex++
                    }
                }
            }
        }

        return output
    }

    private fun formatDurationForOverlay(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun MediaFormat.getRotation(): Int {
        return if (containsKey(MediaFormat.KEY_ROTATION)) {
            getInteger(MediaFormat.KEY_ROTATION)
        } else {
            0
        }
    }

    private data class AudioSample(
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo,
    )

    private data class EncoderConfig(
        val codec: MediaCodec,
        val colorFormat: Int,
    )
}
