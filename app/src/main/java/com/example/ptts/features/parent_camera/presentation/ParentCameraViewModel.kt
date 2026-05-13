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
import com.example.ptts.features.jump_session.presentation.JumpSessionDefaults
import com.example.ptts.features.parent_camera.data.PoseAnalysisResult
import com.example.ptts.features.parent_camera.domain.JumpCounter
import com.example.ptts.features.parent_camera.domain.JumpPhase
import com.example.ptts.features.parent_camera.domain.TrackingQuality
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class ParentCameraViewModel(
    application: Application,
    durationSeconds: Int,
) : AndroidViewModel(application) {
    private val repository = JumpRecordRepository(application)
    private val jumpCounter = JumpCounter(
        onLog = ::logJumpSession,
    )
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
        lastAnalysisFrameMs = 0L
        analysisFps = 0f
        _uiState.update { state ->
            state.copy(
                stage = ParentCameraStage.Framing,
                countdownValue = null,
                remainingSeconds = safeDurationSeconds,
                jumpCount = 0,
                trackingQuality = TrackingQuality.NoPose,
                jumpPhase = JumpPhase.Searching,
                analysisFps = 0f,
                inferenceMs = 0L,
                videoFile = null,
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

        logJumpSession(
            "onPoseAnalysisResult: stage=${uiState.value.stage} landmarks=$landmarkCount " +
                "tracking=$trackingQuality fps=${String.format("%.1f", fps)} inferenceMs=${result.inferenceMs}",
        )

        if (uiState.value.stage != ParentCameraStage.Recording) {
            _uiState.update { state ->
                state.copy(
                    trackingQuality = trackingQuality,
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
                "onPoseAnalysisResult: skipped jump counter because stage=${uiState.value.stage}",
            )
            return
        }

        val counterResult = jumpCounter.accept(frame)
        logJumpSession(
            "onPoseAnalysisResult: count=${counterResult.count} phase=${counterResult.phase} " +
                "tracking=${counterResult.trackingQuality} counted=${counterResult.countedThisFrame}",
        )
        _uiState.update { state ->
            state.copy(
                jumpCount = counterResult.count,
                trackingQuality = counterResult.trackingQuality,
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
    }

    private fun beginRecording() {
        logJumpSession("beginRecording: duration=$safeDurationSeconds")
        jumpCounter.reset()
        lastAnalysisFrameMs = 0L
        analysisFps = 0f
        recordingStartTimeMs = SystemClock.elapsedRealtime()
        cameraController?.startRecording()
        _uiState.update { state ->
            state.copy(
                stage = ParentCameraStage.Recording,
                countdownValue = null,
                remainingSeconds = safeDurationSeconds,
                jumpCount = 0,
                trackingQuality = TrackingQuality.NoPose,
                jumpPhase = JumpPhase.Searching,
            )
        }

        recordingJob = viewModelScope.launch {
            while (uiState.value.stage == ParentCameraStage.Recording) {
                delay(100)
                val elapsedMs = SystemClock.elapsedRealtime() - recordingStartTimeMs
                val remaining = (safeDurationSeconds - (elapsedMs / 1000)).toInt().coerceAtLeast(0)
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
        val finalCount = uiState.value.jumpCount
        val videoFile = cameraController?.stopRecording()
        logJumpSession("finishRecording: finalCount=$finalCount videoFile=$videoFile")
        _uiState.update { state ->
            state.copy(
                stage = ParentCameraStage.Summary,
                remainingSeconds = 0,
                countdownValue = null,
                videoFile = videoFile,
            )
        }
        viewModelScope.launch {
            repository.saveBestRecordIfNeeded(finalCount)
        }
    }

    fun saveVideoToGallery() {
        val file = uiState.value.videoFile ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
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
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false, saveSuccess = true, videoFile = null) }
                Toast.makeText(getApplication(), "视频已保存到相册", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                logJumpSession("saveVideoToGallery failed: ${error.message}")
                _uiState.update { it.copy(isSaving = false) }
                Toast.makeText(getApplication(), "保存失败", Toast.LENGTH_SHORT).show()
            }
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
