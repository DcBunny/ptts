package com.example.ptts.features.parent_camera.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
        Log.d(
            TAG,
            "starting: input=${inputFile.absolutePath}, output=${outputFile.absolutePath}, metadata=${overlayStates.size}",
        )

        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(inputFile.absolutePath)
        val videoTrackIndex = findVideoTrack(videoExtractor)
        require(videoTrackIndex >= 0) { "No video track found" }
        videoExtractor.selectTrack(videoTrackIndex)
        val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
        Log.d(TAG, "video track: index=$videoTrackIndex, format=$videoFormat")

        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(inputFile.absolutePath)
        val audioTrackIndex = findAudioTrack(audioExtractor)
        val audioSamples = mutableListOf<AudioSample>()
        val audioFormat = if (audioTrackIndex >= 0) {
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

        val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val totalDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }

        val encoder = createEncoder(videoFormat)
        val inputSurface = CodecInputSurface(encoder.inputSurface)
        var renderer: TextureRenderer? = null
        var outputSurface: DecoderOutputSurface? = null
        var decoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            inputSurface.makeCurrent()
            renderer = TextureRenderer(width, height, timeline)
            outputSurface = DecoderOutputSurface(renderer.textureId)
            decoder = createDecoder(videoFormat, outputSurface.surface)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val rotation = videoFormat.getRotation()
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            processInternal(
                videoExtractor = videoExtractor,
                totalDurationUs = totalDurationUs,
                audioSamples = audioSamples,
                audioFormat = audioFormat,
                decoder = decoder,
                encoder = encoder.codec,
                inputSurface = inputSurface,
                outputSurface = outputSurface,
                renderer = renderer,
                muxer = muxer,
                onProgress = onProgress,
            )
            Log.d(TAG, "completed successfully")
        } finally {
            videoExtractor.release()
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder.codec.stop() }
            runCatching { encoder.codec.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            outputSurface?.release()
            renderer?.release()
            inputSurface.release()
        }
        Unit
    }.onFailure { error ->
        Log.e(TAG, "processing failed", error)
    }

    private fun processInternal(
        videoExtractor: MediaExtractor,
        totalDurationUs: Long,
        audioSamples: List<AudioSample>,
        audioFormat: MediaFormat?,
        decoder: MediaCodec,
        encoder: MediaCodec,
        inputSurface: CodecInputSurface,
        outputSurface: DecoderOutputSurface,
        renderer: TextureRenderer,
        muxer: MediaMuxer,
        onProgress: (Int) -> Unit,
    ) {
        var videoTrackId = -1
        var audioTrackId = -1
        var muxerStarted = false

        fun startMuxer(videoFormat: MediaFormat) {
            if (muxerStarted) return
            videoTrackId = muxer.addTrack(videoFormat)
            if (audioFormat != null) {
                audioTrackId = muxer.addTrack(audioFormat)
                Log.d(TAG, "audio track added, audioTrackId=$audioTrackId")
            }
            muxer.start()
            muxerStarted = true
            if (audioSamples.isNotEmpty()) {
                writeAudioSamples(muxer, audioTrackId, audioSamples)
                Log.d(TAG, "audio samples written: ${audioSamples.size}")
            }
        }

        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderOutputDone = false
        val decoderBufferInfo = MediaCodec.BufferInfo()
        val encoderBufferInfo = MediaCodec.BufferInfo()
        var framesProcessed = 0

        while (!encoderOutputDone) {
            encoderOutputDone = drainEncoder(
                encoder = encoder,
                bufferInfo = encoderBufferInfo,
                muxer = muxer,
                muxerStarted = { muxerStarted },
                videoTrackId = { videoTrackId },
                onOutputFormatChanged = ::startMuxer,
            )

            if (!decoderInputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        ?: throw IllegalStateException("Decoder input buffer is null")
                    val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        decoderInputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            sampleSize,
                            videoExtractor.sampleTime,
                            videoExtractor.sampleFlags,
                        )
                        videoExtractor.advance()
                    }
                }
            }

            if (!decoderOutputDone) {
                when (val outputBufferIndex = decoder.dequeueOutputBuffer(decoderBufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "decoder format changed: ${decoder.outputFormat}")
                    else -> {
                        if (outputBufferIndex >= 0) {
                            val doRender = decoderBufferInfo.size != 0
                            decoder.releaseOutputBuffer(outputBufferIndex, doRender)

                            if (doRender) {
                                outputSurface.awaitNewImage()
                                outputSurface.updateTexImage()
                                renderer.drawFrame(
                                    textureTransform = outputSurface.transformMatrix,
                                    elapsedMs = decoderBufferInfo.presentationTimeUs / 1000L,
                                )
                                inputSurface.setPresentationTime(decoderBufferInfo.presentationTimeUs * 1000L)
                                inputSurface.swapBuffers()
                                framesProcessed++

                                if (totalDurationUs > 0L) {
                                    val progress =
                                        ((decoderBufferInfo.presentationTimeUs * 100L) / totalDurationUs).toInt()
                                    onProgress(progress.coerceIn(0, 100))
                                }
                            }

                            if ((decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decoderOutputDone = true
                                encoder.signalEndOfInputStream()
                                Log.d(TAG, "decoder EOS, encoder EOS signaled")
                            }
                        }
                    }
                }
            }
        }

        Log.d(TAG, "encoder EOS, framesProcessed=$framesProcessed")
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        muxerStarted: () -> Boolean,
        videoTrackId: () -> Int,
        onOutputFormatChanged: (MediaFormat) -> Unit,
    ): Boolean {
        while (true) {
            when (val status = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormatChanged(encoder.outputFormat)
                else -> {
                    if (status >= 0) {
                        val encodedData = encoder.getOutputBuffer(status)
                            ?: throw IllegalStateException("Encoder output buffer is null")
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            check(muxerStarted()) { "Muxer has not started" }
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackId(), encodedData, bufferInfo)
                        }
                        val isEndOfStream = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        encoder.releaseOutputBuffer(status, false)
                        if (isEndOfStream) {
                            return true
                        }
                    }
                }
            }
        }
    }

    private fun createDecoder(videoFormat: MediaFormat, outputSurface: Surface): MediaCodec {
        val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
        return MediaCodec.createDecoderByType(mime).also { decoder ->
            decoder.configure(videoFormat, outputSurface, null, 0)
            decoder.start()
            Log.d(TAG, "decoder created: $mime")
        }
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

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        Log.d(TAG, "encoder created: ${width}x${height} @${frameRate}fps")
        return EncoderConfig(codec = codec, inputSurface = inputSurface)
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
        val inputSurface: Surface,
    )
}

private class TextureRenderer(
    private val width: Int,
    private val height: Int,
    private val timeline: OverlayTimeline,
) {
    private val vertexBuffer = createFloatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        ),
    )
    private val videoTextureBuffer = createFloatBuffer(
        floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        ),
    )
    private val overlayTextureBuffer = createFloatBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        ),
    )
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val videoProgram = createProgram(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
    private val overlayProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
    private val overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val overlayCanvas = Canvas(overlayBitmap)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(204, 17, 17, 17)
    }
    private var lastOverlayVisualState: OverlayVisualState? = null
    val textureId: Int
    private val overlayTextureId: Int

    init {
        textureId = createExternalTexture()
        overlayTextureId = createOverlayTexture()
    }

    fun drawFrame(textureTransform: FloatArray, elapsedMs: Long) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawVideo(textureTransform)
        drawOverlay(elapsedMs)
        checkGlError("drawFrame")
    }

    fun release() {
        GLES20.glDeleteProgram(videoProgram)
        GLES20.glDeleteProgram(overlayProgram)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        GLES20.glDeleteTextures(1, intArrayOf(overlayTextureId), 0)
        overlayBitmap.recycle()
    }

    private fun drawVideo(textureTransform: FloatArray) {
        GLES20.glUseProgram(videoProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        setCommonProgramInputs(videoProgram, textureTransform, videoTextureBuffer)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(videoProgram, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawOverlay(elapsedMs: Long) {
        val state = timeline.stateAt(elapsedMs)
        val visualState = OverlayVisualState(
            remainingSeconds = state.remainingSeconds,
            jumpCount = state.jumpCount,
        )
        if (visualState != lastOverlayVisualState) {
            updateOverlayTexture(state)
            lastOverlayVisualState = visualState
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        setCommonProgramInputs(overlayProgram, identityMatrix, overlayTextureBuffer)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(overlayProgram, "uTexture"), 1)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun setCommonProgramInputs(
        program: Int,
        textureMatrix: FloatArray,
        textureBuffer: FloatBuffer,
    ) {
        val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        val textureLocation = GLES20.glGetAttribLocation(program, "aTextureCoord")
        val matrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        textureBuffer.position(0)
        GLES20.glEnableVertexAttribArray(textureLocation)
        GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0)
    }

    private fun updateOverlayTexture(state: OverlayFrameState) {
        overlayBitmap.eraseColor(Color.TRANSPARENT)
        val paddingX = width * 0.03f
        val paddingY = height * 0.03f
        val badgeWidth = width * 0.28f
        val badgeHeight = height * 0.09f
        val cornerRadius = badgeHeight * 0.25f
        val textSize = badgeHeight * 0.5f

        drawBadge(
            left = paddingX,
            top = paddingY,
            width = badgeWidth,
            height = badgeHeight,
            cornerRadius = cornerRadius,
            textSize = textSize,
            text = formatDurationForOverlay(state.remainingSeconds),
        )
        drawBadge(
            left = width - paddingX - badgeWidth,
            top = paddingY,
            width = badgeWidth,
            height = badgeHeight,
            cornerRadius = cornerRadius,
            textSize = textSize,
            text = state.jumpCount.toString(),
        )

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlayBitmap)
    }

    private fun drawBadge(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        cornerRadius: Float,
        textSize: Float,
        text: String,
    ) {
        val rect = RectF(left, top, left + width, top + height)
        overlayCanvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        textPaint.textSize = textSize
        overlayCanvas.drawText(text, left + width / 2f, top + height / 2f + textSize / 3f, textPaint)
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return textures[0]
    }

    private fun createOverlayTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)
        return textures[0]
    }

    private fun formatDurationForOverlay(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }

    private data class OverlayVisualState(
        val remainingSeconds: Int,
        val jumpCount: Int,
    )
}

private class DecoderOutputSurface(textureId: Int) : SurfaceTexture.OnFrameAvailableListener {
    private val frameSyncObject = Object()
    private var frameAvailable = false
    private val surfaceTexture = SurfaceTexture(textureId)
    val surface = Surface(surfaceTexture)
    val transformMatrix = FloatArray(16)

    init {
        surfaceTexture.setOnFrameAvailableListener(this)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        synchronized(frameSyncObject) {
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }

    fun awaitNewImage() {
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                frameSyncObject.wait(2500L)
                if (!frameAvailable) {
                    throw RuntimeException("Surface frame wait timed out")
                }
            }
            frameAvailable = false
        }
    }

    fun updateTexImage() {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(transformMatrix)
    }

    fun release() {
        surface.release()
        surfaceTexture.release()
    }
}

private class CodecInputSurface(private val surface: Surface) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        eglSetup()
    }

    fun makeCurrent() {
        check(
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext),
        ) { "eglMakeCurrent failed" }
    }

    fun swapBuffers() {
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "eglSwapBuffers failed" }
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        surface.release()
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun eglSetup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE,
            8,
            EGL14.EGL_GREEN_SIZE,
            8,
            EGL14.EGL_BLUE_SIZE,
            8,
            EGL14.EGL_ALPHA_SIZE,
            8,
            EGL14.EGL_RENDERABLE_TYPE,
            EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                eglDisplay,
                attribList,
                0,
                configs,
                0,
                configs.size,
                numConfigs,
                0,
            ),
        ) { "eglChooseConfig failed" }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            configs[0],
            surface,
            surfaceAttribs,
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
    }
}

private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)
    val linkStatus = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] == 0) {
        val log = GLES20.glGetProgramInfoLog(program)
        GLES20.glDeleteProgram(program)
        throw RuntimeException("Could not link GL program: $log")
    }
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)
    return program
}

private fun loadShader(shaderType: Int, source: String): Int {
    val shader = GLES20.glCreateShader(shaderType)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(shader)
        GLES20.glDeleteShader(shader)
        throw RuntimeException("Could not compile GL shader: $log")
    }
    return shader
}

private fun createFloatBuffer(values: FloatArray): FloatBuffer {
    return ByteBuffer.allocateDirect(values.size * java.lang.Float.BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
}

private fun checkGlError(label: String) {
    val error = GLES20.glGetError()
    if (error != GLES20.GL_NO_ERROR) {
        throw RuntimeException("$label: glError 0x${Integer.toHexString(error)}")
    }
}

private const val VIDEO_VERTEX_SHADER = """
    uniform mat4 uTextureMatrix;
    attribute vec4 aPosition;
    attribute vec4 aTextureCoord;
    varying vec2 vTextureCoord;
    void main() {
        gl_Position = aPosition;
        vTextureCoord = (uTextureMatrix * aTextureCoord).xy;
    }
"""

private const val VIDEO_FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTextureCoord;
    uniform samplerExternalOES uTexture;
    void main() {
        gl_FragColor = texture2D(uTexture, vTextureCoord);
    }
"""

private const val OVERLAY_VERTEX_SHADER = """
    uniform mat4 uTextureMatrix;
    attribute vec4 aPosition;
    attribute vec4 aTextureCoord;
    varying vec2 vTextureCoord;
    void main() {
        gl_Position = aPosition;
        vTextureCoord = (uTextureMatrix * aTextureCoord).xy;
    }
"""

private const val OVERLAY_FRAGMENT_SHADER = """
    precision mediump float;
    varying vec2 vTextureCoord;
    uniform sampler2D uTexture;
    void main() {
        gl_FragColor = texture2D(uTexture, vTextureCoord);
    }
"""
