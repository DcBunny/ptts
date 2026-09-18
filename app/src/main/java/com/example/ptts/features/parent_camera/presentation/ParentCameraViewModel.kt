package com.example.ptts.features.parent_camera.presentation

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ptts.features.parent_camera.data.JumpCameraController
import com.example.ptts.features.parent_camera.data.JumpRecordRepository
import com.example.ptts.features.parent_camera.data.JumpSessionDiagnosticRecorder
import com.example.ptts.features.parent_camera.data.OverlayFrameState
import com.example.ptts.features.parent_camera.data.VideoOverlayProcessor
import com.example.ptts.features.jump_session.presentation.JumpSessionDefaults
import com.example.ptts.features.parent_camera.data.PoseAnalysisResult
import com.example.ptts.features.parent_camera.domain.AutoLowLightCapability
import com.example.ptts.features.parent_camera.domain.AutoLowLightCommandResult
import com.example.ptts.features.parent_camera.domain.AutoLowLightState
import com.example.ptts.features.parent_camera.domain.AutoLowLightStrategy
import com.example.ptts.features.parent_camera.domain.JumpCounter
import com.example.ptts.features.parent_camera.domain.JumpPhase
import com.example.ptts.features.parent_camera.domain.TrackingQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ParentCameraViewModel(
    application: Application,
    durationSeconds: Int,
) : AndroidViewModel(application) {
    private val repository = JumpRecordRepository(application)
    private val diagnosticsEnabled =
        (application.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val diagnostics = JumpSessionDiagnosticRecorder(
        enabled = diagnosticsEnabled,
        outputDir = File(application.cacheDir, DIAGNOSTICS_DIR),
    )
    private val jumpCounter = JumpCounter(
        onLog = ::logJumpSession,
        onDiagnostic = diagnostics::record,
    )
    private val captureQualityAnalyzer = PoseCaptureQualityAnalyzer()
    private val autoLowLightStrategy = AutoLowLightStrategy()
    private val safeDurationSeconds = durationSeconds.coerceAtLeast(JumpSessionDefaults.MinDurationSeconds)
    private val _uiState = MutableStateFlow(
        ParentCameraUiState(
            durationSeconds = safeDurationSeconds,
            remainingSeconds = safeDurationSeconds,
        ),
    )
    val uiState: StateFlow<ParentCameraUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var recordingJob: Job? = null
    private var lastAnalysisFrameMs = 0L
    private var analysisFps = 0f
    private var cameraController: JumpCameraController? = null
    private var recordingStartTimeMs = 0L
    private var isRecordingActive = false
    private var videoProcessingJob: Job? = null
    private val overlayTimeline = mutableListOf<OverlayFrameState>()

    init {
        viewModelScope.launch {
            repository.bestRecord.collect { bestRecord ->
                logJumpSession("bestRecord updated: $bestRecord")
                _uiState.update { state ->
                    state.copy(bestRecord = bestRecord)
                }
            }
        }
    }

    fun setCameraController(controller: JumpCameraController) {
        cameraController = controller
    }

    fun onAutoLowLightCapability(capability: AutoLowLightCapability) {
        autoLowLightStrategy.setCapability(capability)
        _uiState.update { state ->
            state.copy(
                autoLowLightState = autoLowLightStrategy.state,
                autoLowLightLevel = autoLowLightStrategy.exposureLevel,
                autoLowLightReason = autoLowLightStrategy.lastReason,
            )
        }
        logJumpSession(
            "auto low-light capability: boost=${capability.lowLightBoostSupported} " +
                "exposure=${capability.exposureCompensationRange}",
        )
    }

    fun onAutoLowLightCommandResult(result: AutoLowLightCommandResult) {
        autoLowLightStrategy.onCommandResult(result)
        _uiState.update { state ->
            state.copy(
                autoLowLightState = autoLowLightStrategy.state,
                autoLowLightLevel = autoLowLightStrategy.exposureLevel,
                autoLowLightReason = autoLowLightStrategy.lastReason,
            )
        }
        logJumpSession(
            "auto low-light command=${result.action} success=${result.success} " +
                "error=${result.error ?: ""}",
        )
    }

    /** Debug-only export hook; production recorder is disabled by default. */
    fun exportDiagnostics(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            diagnostics.exportTo(file)
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        logJumpSession("onCameraPermissionResult: granted=$granted")
        _uiState.update { state ->
            state.copy(
                cameraPermissionGranted = granted,
                errorState = if (granted) null else ParentCameraError.PermissionDenied,
            )
        }
    }

    fun onCameraReady() {
        logJumpSession("onCameraReady")
        _uiState.update { state ->
            state.copy(isCameraReady = true, errorState = null)
        }
    }

    fun onCameraError(error: Throwable) {
        logJumpSession("onCameraError: ${error.message}")
        _uiState.update { state ->
            state.copy(
                errorState = ParentCameraError.CameraUnavailable,
                isCameraReady = false,
            )
        }
    }

    fun startCountdown() {
        val state = uiState.value
        logJumpSession("startCountdown: stage=${state.stage} permission=${state.cameraPermissionGranted}")
        if (!state.cameraPermissionGranted || state.stage != ParentCameraStage.Framing) {
            logJumpSession("startCountdown: ignored, preconditions not met")
            return
        }

        countdownJob?.cancel()
        recordingJob?.cancel()
        videoProcessingJob?.cancel()
        jumpCounter.reset(clearCalibration = true)
        autoLowLightStrategy.reset()
        cameraController?.restoreAutoLowLight()
        diagnostics.clear()
        isRecordingActive = false
        _uiState.update {
            it.copy(
                stage = ParentCameraStage.Countdown,
                countdownValue = 3,
                jumpCount = 0,
                confirmedJumpCount = 0,
                estimatedJumpCount = 0,
                remainingSeconds = safeDurationSeconds,
                jumpPhase = JumpPhase.Searching,
                isRecovering = false,
                isCalibrating = true,
                autoLowLightState = AutoLowLightState.Normal,
                autoLowLightLevel = 0,
                autoLowLightReason = "",
            )
        }

        countdownJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val current = uiState.value.countdownValue ?: return@launch
                logJumpSession("countdown: $current")
                if (current > 1) {
                    _uiState.update { it.copy(countdownValue = current - 1) }
                } else {
                    beginRecording()
                    return@launch
                }
            }
        }
    }

    fun retry() {
        logJumpSession("retry")
        countdownJob?.cancel()
        recordingJob?.cancel()
        videoProcessingJob?.cancel()
        jumpCounter.reset(clearCalibration = true)
        autoLowLightStrategy.reset()
        cameraController?.restoreAutoLowLight()
        diagnostics.clear()
        captureQualityAnalyzer.reset()
        isRecordingActive = false
        lastAnalysisFrameMs = 0L
        analysisFps = 0f
        overlayTimeline.clear()
        _uiState.update { state ->
            state.copy(
                stage = ParentCameraStage.Framing,
                countdownValue = null,
                remainingSeconds = safeDurationSeconds,
                jumpCount = 0,
                confirmedJumpCount = 0,
                estimatedJumpCount = 0,
                trackingQuality = TrackingQuality.NoPose,
                captureQuality = CaptureQualityState(),
                jumpPhase = JumpPhase.Searching,
                isRecovering = false,
                isCalibrating = true,
                autoLowLightState = AutoLowLightState.Normal,
                autoLowLightLevel = 0,
                autoLowLightReason = "",
                analysisFps = 0f,
                inferenceMs = 0L,
                videoFile = null,
                isFinalizingVideo = false,
                saveSuccess = false,
            )
        }
    }

    fun onPoseAnalysisResult(result: PoseAnalysisResult) {
        val baseFrame = result.frame.copy(sessionStage = uiState.value.stage.name)
        val landmarkCount = baseFrame.landmarks.size
        val fps = updateFps(baseFrame.timestampMs)
        val captureQuality = captureQualityAnalyzer.analyze(baseFrame)
        val framingTrackingQuality = when {
            baseFrame.landmarks.isEmpty() -> TrackingQuality.NoPose
            captureQuality.issue == CaptureQualityIssue.NoPose -> TrackingQuality.NoPose
            captureQuality.issue == CaptureQualityIssue.LowLightOrBlur ||
                captureQuality.issue == CaptureQualityIssue.UnreliablePose -> TrackingQuality.UnreliablePose
            captureQuality.issue == CaptureQualityIssue.PartialBody -> TrackingQuality.PartialBody
            else -> TrackingQuality.Tracking
        }

        val lightMetrics = result.lightMetrics ?: baseFrame.lightMetrics
        val validPoseForLight = baseFrame.landmarks.values.count { it.confidence >= 0.35f } >= 3 &&
            captureQuality.issue != CaptureQualityIssue.NoPose
        val lightDecision = lightMetrics?.let {
            autoLowLightStrategy.onFrame(
                metrics = it,
                validPose = validPoseForLight,
                analysisFps = fps,
            )
        }
        val frame = baseFrame.copy(
            lightMetrics = lightMetrics,
            autoLowLightState = autoLowLightStrategy.state,
            autoLowLightLevel = autoLowLightStrategy.exposureLevel,
            autoLowLightReason = autoLowLightStrategy.lastReason,
        )
        cameraController?.applyAutoLowLight(lightDecision, lightMetrics)

        logJumpSession(
            "onPoseAnalysisResult: stage=${uiState.value.stage} landmarks=$landmarkCount " +
                "tracking=$framingTrackingQuality quality=${captureQuality.issue}/${captureQuality.score} " +
                "fps=${String.format("%.1f", fps)} inferenceMs=${result.inferenceMs}",
        )

        if (uiState.value.stage != ParentCameraStage.Recording || !isRecordingActive) {
            if (uiState.value.stage == ParentCameraStage.Framing || uiState.value.stage == ParentCameraStage.Countdown) {
                jumpCounter.calibrate(frame)
            }
            _uiState.update { state ->
                state.copy(
                    trackingQuality = framingTrackingQuality,
                    captureQuality = captureQuality,
                    autoLowLightState = autoLowLightStrategy.state,
                    autoLowLightLevel = autoLowLightStrategy.exposureLevel,
                    autoLowLightReason = autoLowLightStrategy.lastReason,
                    poseOverlay = PoseOverlay(
                        points = frame.landmarks.map { (landmark, point) ->
                            PoseOverlayPoint(
                                landmark = landmark,
                                x = point.x,
                                y = point.y,
                                confidence = point.confidence,
                            )
                        },
                    ),
                    analysisFps = fps,
                    inferenceMs = result.inferenceMs,
                    analysisAspectRatio = result.analysisAspectRatio,
                    isCalibrating = state.stage == ParentCameraStage.Countdown &&
                        !jumpCounter.isCalibrationReady(),
                )
            }
            logJumpSession(
                "onPoseAnalysisResult: skipped jump counter because stage=${uiState.value.stage} active=$isRecordingActive",
            )
            return
        }

        val previousCount = uiState.value.jumpCount
        val previousEstimatedCount = uiState.value.estimatedJumpCount
        val counterResult = jumpCounter.accept(frame)
        logJumpSession(
            "onPoseAnalysisResult: count=${counterResult.count} phase=${counterResult.phase} " +
                "tracking=${counterResult.trackingQuality} counted=${counterResult.countedThisFrame}",
        )
        _uiState.update { state ->
            state.copy(
                jumpCount = counterResult.count,
                confirmedJumpCount = counterResult.confirmedCount,
                estimatedJumpCount = counterResult.estimatedCount,
                trackingQuality = counterResult.trackingQuality,
                captureQuality = captureQuality,
                autoLowLightState = autoLowLightStrategy.state,
                autoLowLightLevel = autoLowLightStrategy.exposureLevel,
                autoLowLightReason = autoLowLightStrategy.lastReason,
                jumpPhase = counterResult.phase,
                isRecovering = counterResult.recovering,
                poseOverlay = PoseOverlay(
                    points = frame.landmarks.map { (landmark, point) ->
                        PoseOverlayPoint(
                            landmark = landmark,
                            x = point.x,
                            y = point.y,
                            confidence = point.confidence,
                        )
                    },
                ),
                analysisFps = fps,
                inferenceMs = result.inferenceMs,
                analysisAspectRatio = result.analysisAspectRatio,
            )
        }
        if (counterResult.count != previousCount || counterResult.estimatedCount != previousEstimatedCount) {
            appendOverlayState(SystemClock.elapsedRealtime() - recordingStartTimeMs)
        }
    }

    private fun beginRecording() {
        logJumpSession("beginRecording: duration=$safeDurationSeconds")
        jumpCounter.startSession()
        captureQualityAnalyzer.reset()
        lastAnalysisFrameMs = 0L
        analysisFps = 0f
        isRecordingActive = false
        recordingStartTimeMs = 0L
        overlayTimeline.clear()
        _uiState.update { state ->
            state.copy(
                stage = ParentCameraStage.Recording,
                countdownValue = null,
                remainingSeconds = safeDurationSeconds,
                jumpCount = 0,
                confirmedJumpCount = 0,
                estimatedJumpCount = 0,
                trackingQuality = TrackingQuality.NoPose,
                captureQuality = CaptureQualityState(),
                jumpPhase = JumpPhase.Searching,
                isRecovering = false,
                isCalibrating = false,
                videoFile = null,
                isFinalizingVideo = false,
                autoLowLightState = autoLowLightStrategy.state,
                autoLowLightLevel = autoLowLightStrategy.exposureLevel,
                autoLowLightReason = autoLowLightStrategy.lastReason,
            )
        }
        val started = cameraController?.startRecording() == true
        if (!started) {
            _uiState.update { state ->
                state.copy(
                    stage = ParentCameraStage.Framing,
                    errorState = ParentCameraError.CameraUnavailable,
                )
            }
        }
    }

    fun onRecordingStarted() {
        if (uiState.value.stage != ParentCameraStage.Recording || isRecordingActive) {
            logJumpSession("onRecordingStarted: ignored stage=${uiState.value.stage} active=$isRecordingActive")
            return
        }
        logJumpSession("onRecordingStarted")
        recordingJob?.cancel()
        jumpCounter.startSession()
        captureQualityAnalyzer.reset()
        lastAnalysisFrameMs = 0L
        analysisFps = 0f
        recordingStartTimeMs = SystemClock.elapsedRealtime()
        isRecordingActive = true
        overlayTimeline.clear()
        overlayTimeline.add(
            OverlayFrameState(
                elapsedMs = 0L,
                remainingSeconds = safeDurationSeconds,
                jumpCount = 0,
                estimatedCount = 0,
            ),
        )
        _uiState.update { state ->
            state.copy(
                remainingSeconds = safeDurationSeconds,
                jumpCount = 0,
                confirmedJumpCount = 0,
                estimatedJumpCount = 0,
                trackingQuality = TrackingQuality.NoPose,
                captureQuality = CaptureQualityState(),
                jumpPhase = JumpPhase.Searching,
                isRecovering = false,
                isCalibrating = false,
                analysisFps = 0f,
                inferenceMs = 0L,
            )
        }

        recordingJob = viewModelScope.launch {
            while (uiState.value.stage == ParentCameraStage.Recording) {
                delay(100)
                val elapsedMs = SystemClock.elapsedRealtime() - recordingStartTimeMs
                val remaining = (safeDurationSeconds - (elapsedMs / 1000)).toInt().coerceAtLeast(0)
                appendOverlayState(elapsedMs)
                _uiState.update { state ->
                    state.copy(remainingSeconds = remaining)
                }
                if (remaining <= 0 && elapsedMs >= safeDurationSeconds * 1000L) {
                    finishRecording()
                    return@launch
                }
            }
        }
    }

    private fun finishRecording() {
        if (uiState.value.stage != ParentCameraStage.Recording || !isRecordingActive) {
            return
        }
        isRecordingActive = false
        val finalCount = uiState.value.jumpCount
        overlayTimeline.add(
            OverlayFrameState(
                elapsedMs = safeDurationSeconds * 1000L,
                remainingSeconds = 0,
                jumpCount = finalCount,
                estimatedCount = uiState.value.estimatedJumpCount,
            ),
        )
        cameraController?.stopRecording()
        cameraController?.restoreAutoLowLight()
        autoLowLightStrategy.reset()
        logJumpSession("finishRecording: finalCount=$finalCount")
        _uiState.update { state ->
            state.copy(
                stage = ParentCameraStage.Summary,
                remainingSeconds = 0,
                countdownValue = null,
                videoFile = null,
                isFinalizingVideo = true,
            )
        }
        viewModelScope.launch {
            repository.saveBestRecordIfNeeded(finalCount)
        }
        if (diagnosticsEnabled) {
            viewModelScope.launch(Dispatchers.IO) {
                diagnostics.exportForSession(System.currentTimeMillis())
            }
        }
    }

    fun onRecordingFinalized(result: Result<File>) {
        isRecordingActive = false
        result.onSuccess { file ->
            logJumpSession("onRecordingFinalized: file=$file size=${file.length()}")
            videoProcessingJob?.cancel()
            videoProcessingJob = viewModelScope.launch {
                val processedResult = runCatching {
                    processVideoWithOverlay(file, overlayTimeline.toList())
                }
                processedResult.onSuccess { processedFile ->
                    file.delete()
                    _uiState.update { state ->
                        state.copy(
                            videoFile = processedFile,
                            isFinalizingVideo = false,
                            errorState = null,
                        )
                    }
                }.onFailure { error ->
                    logJumpSession("processVideoWithOverlay failed: ${error.message}")
                    _uiState.update { state ->
                        state.copy(
                            videoFile = null,
                            isFinalizingVideo = false,
                            errorState = ParentCameraError.CameraUnavailable,
                        )
                    }
                    Toast.makeText(getApplication(), "成绩视频生成失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.onFailure { error ->
            logJumpSession("onRecordingFinalized failed: ${error.message}")
            _uiState.update { state ->
                state.copy(
                    isFinalizingVideo = false,
                    errorState = ParentCameraError.CameraUnavailable,
                )
            }
            Toast.makeText(getApplication(), "视频生成失败", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun processVideoWithOverlay(
        inputFile: File,
        timelineSnapshot: List<OverlayFrameState>,
    ): File {
        require(timelineSnapshot.isNotEmpty()) { "Missing overlay timeline" }
        logJumpSession("processVideoWithOverlay: starting, metadata=${timelineSnapshot.size}, input=${inputFile.length()} bytes")
        return withContext(Dispatchers.Default) {
            val outputFile = File.createTempFile("jump_overlay_", ".mp4", getApplication<Application>().cacheDir)
            try {
                val processor = VideoOverlayProcessor()
                processor.process(inputFile, outputFile, timelineSnapshot).getOrThrow()
                require(outputFile.length() > 0L) { "Processed video is empty" }
                logJumpSession("processVideoWithOverlay: success, output=${outputFile.length()} bytes")
                outputFile
            } catch (error: Throwable) {
                outputFile.delete()
                throw error
            }
        }
    }

    fun saveVideoToGallery() {
        val file = uiState.value.videoFile ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val processResult = runCatching {
                val context = getApplication<Application>()
                saveToMediaStore(context, file)
                file.delete()
            }
            processResult.onSuccess {
                _uiState.update { it.copy(isSaving = false, saveSuccess = true, videoFile = null) }
                Toast.makeText(getApplication(), "视频已保存到相册", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                logJumpSession("saveVideoToGallery failed: ${error.message}")
                _uiState.update { it.copy(isSaving = false) }
                Toast.makeText(getApplication(), "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun appendOverlayState(elapsedMs: Long) {
        val state = uiState.value
        val remaining = (safeDurationSeconds - (elapsedMs / 1000)).toInt().coerceAtLeast(0)
        val overlayState = OverlayFrameState(
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            remainingSeconds = remaining,
            jumpCount = state.jumpCount,
            estimatedCount = state.estimatedJumpCount,
        )
        if (overlayTimeline.lastOrNull() != overlayState) {
            overlayTimeline.add(overlayState)
        }
    }

    private suspend fun saveToMediaStore(context: android.content.Context, file: File) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "jump_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw RuntimeException("Failed to create MediaStore entry")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw RuntimeException("Failed to open MediaStore output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun updateFps(timestampMs: Long): Float {
        val previousFrameMs = lastAnalysisFrameMs
        lastAnalysisFrameMs = timestampMs
        if (previousFrameMs == 0L || timestampMs <= previousFrameMs) {
            return analysisFps
        }
        val instantFps = 1000f / (timestampMs - previousFrameMs)
        analysisFps = if (analysisFps == 0f) {
            instantFps
        } else {
            analysisFps * 0.8f + instantFps * 0.2f
        }
        return analysisFps
    }

    override fun onCleared() {
        cameraController?.restoreAutoLowLight()
        super.onCleared()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun logJumpSession(message: String) = Unit

    private companion object {
        const val DIAGNOSTICS_DIR = "jump_diagnostics"
    }

    class Factory(
        private val application: Application,
        private val durationSeconds: Int,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ParentCameraViewModel(application, durationSeconds) as T
        }
    }

}
