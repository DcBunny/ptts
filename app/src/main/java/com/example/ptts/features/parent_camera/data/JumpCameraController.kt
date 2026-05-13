package com.example.ptts.features.parent_camera.data

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class JumpCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analyzer: PoseFrameAnalyzer,
    private val onError: (Throwable) -> Unit,
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var recorder: Recorder? = null
    private var activeRecording: Recording? = null
    private var pendingVideoFile: File? = null

    fun start() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                runCatching {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    bind(provider)
                }.onFailure(onError)
            },
            mainExecutor,
        )
    }

    fun stop() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        analyzer.close()
        analysisExecutor.shutdown()
    }

    fun startRecording(): File? {
        val rec = recorder ?: return null
        pendingVideoFile = File.createTempFile("jump_", ".mp4", context.cacheDir)
        val outputOptions = FileOutputOptions.Builder(pendingVideoFile!!).build()
        activeRecording = rec.prepareRecording(context, outputOptions)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        if (!event.hasError()) {
                            // recording succeeded
                        } else {
                            onError(RuntimeException("Recording failed: ${event.error} ${event.cause}"))
                        }
                    }
                }
            }
        return pendingVideoFile
    }

    fun stopRecording(): File? {
        activeRecording?.stop()
        activeRecording = null
        return pendingVideoFile
    }

    private fun bind(provider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .build()
            .also { previewUseCase ->
                previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysisUseCase ->
                analysisUseCase.setAnalyzer(analysisExecutor, analyzer)
            }

        val rec = Recorder.Builder()
            .setExecutor(mainExecutor)
            .build()
        val capture = VideoCapture.withOutput(rec)
        recorder = rec

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
            capture,
        )
    }
}
