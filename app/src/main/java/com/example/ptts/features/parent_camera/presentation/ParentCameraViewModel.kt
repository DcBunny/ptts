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
import com.example.ptts.features.parent_camera.data.OverlayFrameState
import com.example.ptts.features.parent_camera.data.VideoOverlayProcessor
import com.example.ptts.features.jump_session.presentation.JumpSessionDefaults
import com.example.ptts.features.parent_camera.data.PoseAnalysisResult
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
    private val jumpCounter = JumpCounter(
        onLog = ::logJumpSession,
    )
    private val captureQualityAnalyzer = PoseCaptureQualityAnalyzer()
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
        isRecordingActive = false
        _uiState.update {
            it.copy(
                stage = ParentCameraStage.Countdown,
                countdownValue = 3,
                jumpCount = 0,
                remainingSeconds = safeDurationSeconds,
                jumpPhase = JumpPhase.Searching,
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
        jumpCounter.reset()
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
                trackingQuality = TrackingQuality.NoPose,
                captureQuality = CaptureQualityState(),
                jumpPhase = JumpPhase.Searching,
                analysisFps = 0f,
                inferenceMs = 0L,
                videoFile = null,
                isFinalizingVideo = false,
                saveSuccess = false,
            )
        }
    }

    fun onPoseAnalysisResult(result: PoseAnalysisResult) {
        val frame = result.frame
        val landmarkCount = frame.landmarks.size
        val trackingQuality = if (frame.landmarks.isEmpty()) {
            TrackingQuality.NoPose
        } else {
            TrackingQuality.Tracking
        }
        val fps = updateFps(frame.timestampMs)
        val captureQuality = captureQualityAnalyzer.analyze(frame)

        logJumpSession(
            "onPoseAnalysisResult: stage=${uiState.value.stage} landmarks=$landmarkCount " +
                "tracking=$trackingQuality quality=${captureQuality.issue}/${captureQuality.score} " +
                "fps=${String.format("%.1f", fps)} inferenceMs=${result.inferenceMs}",
        )

        if (uiState.value.stage != ParentCameraStage.Recording || !isRecordingActive) {
            _uiState.update { state ->
                state.copy(
                    trackingQuality = trackingQuality,
                    captureQuality = captureQuality,
                    poseOverlay = PoseOverlay(
                        points = frame.landmarks.map { (landmark, point) ->
                            PoseOverlayPoint(landmark = landmark, x = point.x, y = point.y)
                        },
                    ),
                    analysisFps = fps,
                    inferenceMs = result.inferenceMs,
                )
            }
            logJumpSession(
                "onPoseAnalysisResult: skipped jump counter because stage=${uiState.value.stage} active=$isRecordingActive",
            )
            return
        }

        val previousCount = uiState.value.jumpCount
        val counterResult = jumpCounter.accept(frame)
        logJumpSession(
            "onPoseAnalysisResult: count=${counterResult.count} phase=${counterResult.phase} " +
                "tracking=${counterResult.trackingQuality} counted=${counterResult.countedThisFrame}",
        )
        _uiState.update { state ->
            state.copy(
                jumpCount = counterResult.count,
                trackingQuality = counterResult.trackingQuality,
                captureQuality = captureQuality,
                jumpPhase = counterResult.phase,
                poseOverlay = PoseOverlay(
                    points = frame.landmarks.map { (landmark, point) ->
                        PoseOverlayPoint(landmark = landmark, x = point.x, y = point.y)
                    },
                ),
                analysisFps = fps,
                inferenceMs = result.inferenceMs,
            )
        }
        if (counterResult.count != previousCount) {
            appendOverlayState(SystemClock.elapsedRealtime() - recordingStartTimeMs)
        }
    }

    private fun beginRecording() {
        logJumpSession("beginRecording: duration=$safeDurationSeconds")
        jumpCounter.reset()
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
                trackingQuality = TrackingQuality.NoPose,
                captureQuality = CaptureQualityState(),
                jumpPhase = JumpPhase.Searching,
                videoFile = null,
                isFinalizingVideo = false,
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
        jumpCounter.reset()
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
            ),
        )
        _uiState.update { state ->
            state.copy(
                remainingSeconds = safeDurationSeconds,
                jumpCount = 0,
                trackingQuality = TrackingQuality.NoPose,
                captureQuality = CaptureQualityState(),
                jumpPhase = JumpPhase.Searching,
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
            ),
        )
        cameraController?.stopRecording()
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
    }

    fun onRecordingFinalized(result: Result<File>) {
        isRecordingActive = false
        result.onSuccess { file ->
            logJumpSession("onRecordingFinalized: file=$file size=${file.length()}")
            _uiState.update { state ->
                state.copy(
                    videoFile = file,
                    isFinalizingVideo = false,
                    errorState = null,
                )
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

    fun saveVideoToGallery() {
        val file = uiState.value.videoFile ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val processResult = runCatching {
                val context = getApplication<Application>()
                val processedFile = processVideoWithOverlay(file)
                saveToMediaStore(context, processedFile)
                processedFile.delete()
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

    private suspend fun processVideoWithOverlay(inputFile: File): File {
        val timelineSnapshot = overlayTimeline.toList()
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

    private fun appendOverlayState(elapsedMs: Long) {
        val state = uiState.value
        val remaining = (safeDurationSeconds - (elapsedMs / 1000)).toInt().coerceAtLeast(0)
        val overlayState = OverlayFrameState(
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            remainingSeconds = remaining,
            jumpCount = state.jumpCount,
        )
        if (overlayTimeline.lastOrNull() != overlayState) {
            overlayTimeline.add(overlayState)
        }
    }

    private fun saveToMediaStore(context: android.content.Context, file: File) {
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
        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
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

    @Suppress("UNUSED_PARAMETER")
    private fun logJumpSession(message: String) = Unit

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
