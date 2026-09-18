package com.example.ptts.features.parent_camera.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.LowLightBoostState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.ptts.features.parent_camera.domain.AutoLowLightAction
import com.example.ptts.features.parent_camera.domain.AutoLowLightCapability
import com.example.ptts.features.parent_camera.domain.AutoLowLightCommandResult
import com.example.ptts.features.parent_camera.domain.AutoLowLightDecision
import com.example.ptts.features.parent_camera.domain.AutoLowLightMode
import com.example.ptts.features.parent_camera.domain.FrameLightMetrics
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class JumpCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val analyzer: PoseFrameAnalyzer,
    private val onError: (Throwable) -> Unit,
    private val onRecordingStarted: () -> Unit,
    private val onRecordingFinalized: (Result<File>) -> Unit,
    private val onAutoLowLightCapability: (AutoLowLightCapability) -> Unit = {},
    private val onAutoLowLightCommandResult: (AutoLowLightCommandResult) -> Unit = {},
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var recorder: Recorder? = null
    private var activeRecording: Recording? = null
    private var pendingVideoFile: File? = null
    private var camera: Camera? = null
    private var originalExposureIndex = 0
    private var generation = 0L
    private var stopped = false
    private var commandInFlight = false
    private var queuedDecision: AutoLowLightDecision? = null
    private var queuedMetrics: FrameLightMetrics? = null
    private var meteringInFlight = false
    private var lastMeteringAtMs = 0L
    private var lastMeteringX = Float.NaN
    private var lastMeteringY = Float.NaN
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        stopped = false
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
        stopped = true
        generation += 1L
        restoreAutoLowLight()
        val recording = activeRecording
        activeRecording = null
        recording?.stop()
        cameraProvider?.unbindAll()
        camera = null
        analyzer.close()
        analysisExecutor.shutdown()
    }

    /** Feeds the pure policy's command and the latest person region to the camera layer. */
    fun applyAutoLowLight(decision: AutoLowLightDecision?, metrics: FrameLightMetrics?) {
        mainExecutor.execute {
            val currentCamera = camera ?: return@execute
            if (!commandInFlight && metrics != null) maybeMeterPerson(currentCamera, metrics)
            if (decision == null || decision.action == AutoLowLightAction.None) return@execute
            if (commandInFlight || meteringInFlight) {
                queuedDecision = decision
                queuedMetrics = metrics
                return@execute
            }
            when (decision.action) {
                AutoLowLightAction.EnableLowLightBoost -> requestLowLightBoost(true, decision)
                AutoLowLightAction.SetExposure -> requestExposureLevel(decision.exposureLevel, decision)
                AutoLowLightAction.DisableEnhancement -> restoreExposureAndDisableBoost(decision)
                AutoLowLightAction.Rollback -> when (decision.mode) {
                    AutoLowLightMode.LowLightBoost -> requestLowLightBoost(true, decision)
                    AutoLowLightMode.ExposureCompensation -> requestExposureLevel(decision.exposureLevel, decision)
                    AutoLowLightMode.None -> restoreExposureAndDisableBoost(decision)
                }
                AutoLowLightAction.None -> Unit
            }
        }
    }

    /** Restores the exposure selected before the automatic enhancer took control. */
    fun restoreAutoLowLight() {
        val currentCamera = camera ?: return
        mainExecutor.execute {
            val control = currentCamera.cameraControl
            runCatching { control.enableLowLightBoostAsync(false) }
            runCatching { control.setExposureCompensationIndex(originalExposureIndex) }
            commandInFlight = false
            queuedDecision = null
            queuedMetrics = null
            lastMeteringAtMs = 0L
            lastMeteringX = Float.NaN
            lastMeteringY = Float.NaN
        }
    }

    fun startRecording(): Boolean {
        val rec = recorder ?: return false
        pendingVideoFile = File.createTempFile("jump_", ".mp4", context.cacheDir)
        val outputOptions = FileOutputOptions.Builder(pendingVideoFile!!).build()
        activeRecording = rec.prepareRecording(context, outputOptions)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        onRecordingStarted()
                    }
                    is VideoRecordEvent.Finalize -> {
                        val finalizedFile = pendingVideoFile
                        pendingVideoFile = null
                        activeRecording = null
                        if (!event.hasError() && finalizedFile != null && finalizedFile.length() > 0L) {
                            onRecordingFinalized(Result.success(finalizedFile))
                        } else {
                            val error = RuntimeException("Recording failed: ${event.error} ${event.cause}")
                            onRecordingFinalized(Result.failure(error))
                            onError(error)
                        }
                    }
                }
            }
        return true
    }

    fun stopRecording() {
        val recording = activeRecording
        activeRecording = null
        recording?.stop()
    }

    private fun bind(provider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .build()
            .also { previewUseCase ->
                previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
            }

        // A jumping child moves a few percent of the frame height. At VGA that is a handful of
        // pixels, which is the same order as the landmark noise, so 720p is the practical floor
        // for a usable vertical signal.
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
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
        val boundCamera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
            capture,
        )
        camera = boundCamera
        originalExposureIndex = boundCamera.cameraInfo.exposureState.exposureCompensationIndex
        val exposureRange = boundCamera.cameraInfo.exposureState.exposureCompensationRange
        onAutoLowLightCapability(
            AutoLowLightCapability(
                lowLightBoostSupported = boundCamera.cameraInfo.isLowLightBoostSupported,
                exposureCompensationRange = exposureRange.lower..exposureRange.upper,
            ),
        )
    }

    private fun requestLowLightBoost(enabled: Boolean, decision: AutoLowLightDecision) {
        val currentCamera = camera ?: return
        if (!currentCamera.cameraInfo.isLowLightBoostSupported) {
            reportCommand(
                AutoLowLightCommandResult(
                    action = decision.action,
                    success = false,
                    mode = decision.mode,
                    exposureLevel = decision.exposureLevel,
                    actualLowLightBoostEnabled = false,
                    error = "设备不支持系统低光增强",
                ),
            )
            return
        }
        commandInFlight = true
        val requestGeneration = generation
        val future = runCatching { currentCamera.cameraControl.enableLowLightBoostAsync(enabled) }
            .getOrElse { error ->
                commandInFlight = false
                reportCommand(
                    AutoLowLightCommandResult(
                        action = decision.action,
                        success = false,
                        mode = decision.mode,
                        exposureLevel = decision.exposureLevel,
                        actualLowLightBoostEnabled = false,
                        error = error.message,
                    ),
                )
                return
            }
        future.addListener(
            {
                if (requestGeneration != generation) return@addListener
                val completed = runCatching {
                    future.get()
                    true
                }.getOrDefault(false)
                if (!completed) {
                    finishCommand(
                        AutoLowLightCommandResult(
                            action = decision.action,
                            success = false,
                            mode = decision.mode,
                            exposureLevel = decision.exposureLevel,
                            actualLowLightBoostEnabled = false,
                            error = "系统低光增强异步调用失败",
                        ),
                    )
                    return@addListener
                }
                val actualState = currentCamera.cameraInfo.lowLightBoostState.value
                if (actualState == LowLightBoostState.ACTIVE) {
                    finishCommand(
                        AutoLowLightCommandResult(
                            action = decision.action,
                            success = true,
                            mode = decision.mode,
                            exposureLevel = decision.exposureLevel,
                            actualLowLightBoostEnabled = true,
                        ),
                    )
                } else if (!enabled) {
                    finishCommand(
                        AutoLowLightCommandResult(
                            action = decision.action,
                            success = true,
                            mode = decision.mode,
                            exposureLevel = decision.exposureLevel,
                            actualLowLightBoostEnabled = false,
                        ),
                    )
                } else {
                    handler.postDelayed({
                        if (requestGeneration != generation) return@postDelayed
                        val active = currentCamera.cameraInfo.lowLightBoostState.value ==
                            LowLightBoostState.ACTIVE
                        finishCommand(
                            AutoLowLightCommandResult(
                                action = decision.action,
                                success = active,
                                mode = decision.mode,
                                exposureLevel = decision.exposureLevel,
                                actualLowLightBoostEnabled = active,
                                error = if (active) null else "系统低光增强未进入生效状态",
                            ),
                        )
                    }, LOW_LIGHT_STATE_TIMEOUT_MS)
                }
            },
            mainExecutor,
        )
    }

    private fun requestExposureLevel(level: Int, decision: AutoLowLightDecision) {
        val currentCamera = camera ?: return
        val exposureState = currentCamera.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) {
            reportCommand(
                AutoLowLightCommandResult(
                    action = decision.action,
                    success = false,
                    mode = decision.mode,
                    exposureLevel = level,
                    error = "设备不支持曝光补偿",
                ),
            )
            return
        }
        val step = exposureState.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: 1f
        val desiredEv = when (level) {
            1 -> 0.3f
            else -> 0.7f
        }
        val delta = kotlin.math.round(desiredEv / step).toInt().coerceAtLeast(1)
        val target = (originalExposureIndex + delta)
            .coerceIn(exposureState.exposureCompensationRange.lower, exposureState.exposureCompensationRange.upper)
        commandInFlight = true
        val requestGeneration = generation
        val future = runCatching { currentCamera.cameraControl.setExposureCompensationIndex(target) }
            .getOrElse { error ->
                commandInFlight = false
                reportCommand(
                    AutoLowLightCommandResult(
                        action = decision.action,
                        success = false,
                        mode = decision.mode,
                        exposureLevel = level,
                        error = error.message,
                    ),
                )
                return
            }
        future.addListener(
            {
                if (requestGeneration != generation) return@addListener
                val completed = runCatching {
                    future.get()
                    true
                }.getOrDefault(false)
                finishCommand(
                    AutoLowLightCommandResult(
                        action = decision.action,
                        success = completed,
                        mode = decision.mode,
                        exposureLevel = level,
                        error = if (completed) null else "曝光补偿异步调用失败",
                    ),
                )
            },
            mainExecutor,
        )
    }

    private fun restoreExposureAndDisableBoost(decision: AutoLowLightDecision) {
        val currentCamera = camera ?: return
        commandInFlight = true
        val requestGeneration = generation
        val boostFuture = runCatching { currentCamera.cameraControl.enableLowLightBoostAsync(false) }
            .getOrNull()
        val finish: (Boolean) -> Unit = { boostSucceeded ->
            if (requestGeneration == generation) {
                val future = runCatching {
                    currentCamera.cameraControl.setExposureCompensationIndex(originalExposureIndex)
                }.getOrNull()
                if (future == null) {
                    finishCommand(
                        AutoLowLightCommandResult(
                            action = decision.action,
                            success = false,
                            mode = decision.mode,
                            exposureLevel = decision.exposureLevel,
                            error = "恢复原始曝光失败",
                        ),
                    )
                } else {
                    future.addListener(
                        {
                            if (requestGeneration != generation) return@addListener
                            val exposureSucceeded = runCatching {
                                future.get()
                                true
                            }.getOrDefault(false)
                            finishCommand(
                                AutoLowLightCommandResult(
                                    action = decision.action,
                                    success = boostSucceeded && exposureSucceeded,
                                    mode = decision.mode,
                                    exposureLevel = decision.exposureLevel,
                                    error = if (boostSucceeded && exposureSucceeded) {
                                        null
                                    } else {
                                        "恢复相机曝光异步调用失败"
                                    },
                                ),
                            )
                        },
                        mainExecutor,
                    )
                }
            }
        }
        if (boostFuture == null) {
            finish(true)
        } else {
            boostFuture.addListener(
                {
                    val succeeded = runCatching {
                        boostFuture.get()
                        true
                    }.getOrDefault(false)
                    finish(succeeded)
                },
                mainExecutor,
            )
        }
    }

    private fun finishCommand(result: AutoLowLightCommandResult) {
        commandInFlight = false
        reportCommand(result)
        val nextDecision = queuedDecision
        val nextMetrics = queuedMetrics
        queuedDecision = null
        queuedMetrics = null
        if (nextDecision != null) applyAutoLowLight(nextDecision, nextMetrics)
    }

    private fun reportCommand(result: AutoLowLightCommandResult) {
        if (!stopped) onAutoLowLightCommandResult(result)
    }

    private fun maybeMeterPerson(currentCamera: Camera, metrics: FrameLightMetrics) {
        if (metrics.region != com.example.ptts.features.parent_camera.domain.LightMeasurementRegion.Person) return
        if (previewView.width <= 0 || previewView.height <= 0 || meteringInFlight) return
        val moved = lastMeteringX.isNaN() ||
            kotlin.math.abs(metrics.regionCenterX - lastMeteringX) >= METERING_MOVE_THRESHOLD ||
            kotlin.math.abs(metrics.regionCenterY - lastMeteringY) >= METERING_MOVE_THRESHOLD
        val now = SystemClock.elapsedRealtime()
        if (!moved && now - lastMeteringAtMs < METERING_INTERVAL_MS) return
        val point = runCatching {
            previewView.meteringPointFactory.createPoint(
                metrics.regionCenterX * previewView.width,
                metrics.regionCenterY * previewView.height,
                0.5f,
            )
        }.getOrNull() ?: return
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3L, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        if (!currentCamera.cameraInfo.isFocusMeteringSupported(action)) return
        meteringInFlight = true
        lastMeteringAtMs = now
        lastMeteringX = metrics.regionCenterX
        lastMeteringY = metrics.regionCenterY
        currentCamera.cameraControl.startFocusAndMetering(action).addListener(
            {
                meteringInFlight = false
                val nextDecision = queuedDecision
                val nextMetrics = queuedMetrics
                queuedDecision = null
                queuedMetrics = null
                if (nextDecision != null) applyAutoLowLight(nextDecision, nextMetrics)
            },
            mainExecutor,
        )
    }

    private companion object {
        const val METERING_INTERVAL_MS = 1_000L
        const val METERING_MOVE_THRESHOLD = 0.08f
        const val LOW_LIGHT_STATE_TIMEOUT_MS = 1_000L
    }
}
