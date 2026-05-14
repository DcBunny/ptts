package com.example.ptts.features.parent_camera.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ptts.R
import com.example.ptts.features.jump_session.presentation.JumpSessionDefaults
import com.example.ptts.features.jump_session.presentation.formatDuration
import com.example.ptts.features.parent_camera.data.JumpCameraController
import com.example.ptts.features.parent_camera.data.PoseAnalysisResult
import com.example.ptts.features.parent_camera.data.PoseFrameAnalyzer
import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.TrackingQuality
import com.example.ptts.features.parent_camera.presentation.CaptureQualityIssue
import com.example.ptts.features.parent_camera.presentation.ParentCameraError
import com.example.ptts.features.parent_camera.presentation.ParentCameraStage
import com.example.ptts.features.parent_camera.presentation.ParentCameraUiState
import com.example.ptts.features.parent_camera.presentation.ParentCameraViewModel
import com.example.ptts.features.parent_camera.presentation.PoseOverlay
import com.example.ptts.ui.theme.BrandOrange
import com.example.ptts.ui.theme.CameraAccent
import com.example.ptts.ui.theme.CameraButton
import com.example.ptts.ui.theme.CameraPanel
import com.example.ptts.ui.theme.CameraPanelLight
import com.example.ptts.ui.theme.PttsTheme

@Composable
fun ParentCameraScreen(
    durationSeconds: Int,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: ParentCameraViewModel = viewModel(
        factory = ParentCameraViewModel.Factory(
            application = application,
            durationSeconds = durationSeconds,
        ),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onCameraPermissionResult(granted)
    }

    LaunchedEffect(hasCameraPermission) {
        viewModel.onCameraPermissionResult(hasCameraPermission)
    }

    var cameraController by remember { mutableStateOf<JumpCameraController?>(null) }

    ParentCameraContent(
        state = state,
        onStart = viewModel::startCountdown,
        onRetry = viewModel::retry,
        onRequestPermission = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onExit = onExit,
        onSaveVideo = { viewModel.saveVideoToGallery() },
        onControllerReady = { controller ->
            cameraController = controller
            viewModel.onCameraReady()
        },
        onCameraError = viewModel::onCameraError,
        onRecordingStarted = viewModel::onRecordingStarted,
        onRecordingFinalized = viewModel::onRecordingFinalized,
        onPoseFrame = viewModel::onPoseAnalysisResult,
        modifier = modifier,
    )

    LaunchedEffect(cameraController) {
        cameraController?.let { controller ->
            viewModel.setCameraController(controller)
        }
    }
}

@Composable
private fun ParentCameraContent(
    state: ParentCameraUiState,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onRequestPermission: () -> Unit,
    onExit: () -> Unit,
    onSaveVideo: () -> Unit,
    onControllerReady: (JumpCameraController) -> Unit,
    onCameraError: (Throwable) -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingFinalized: (Result<java.io.File>) -> Unit,
    onPoseFrame: (PoseAnalysisResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (state.cameraPermissionGranted) {
            CameraPreview(
                onControllerReady = onControllerReady,
                onCameraError = onCameraError,
                onRecordingStarted = onRecordingStarted,
                onRecordingFinalized = onRecordingFinalized,
                onPoseFrame = onPoseFrame,
            )
        } else {
            PermissionBackground()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x55000000),
                            Color(0x11000000),
                            Color(0xC8000000),
                        ),
                    ),
                ),
        )

        if (state.stage == ParentCameraStage.Recording) {
            PoseOverlayCanvas(overlay = state.poseOverlay)
        } else if (state.stage != ParentCameraStage.Summary) {
            FocusFrame()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, top = 48.dp, end = 20.dp, bottom = 24.dp),
        ) {
            CameraHeader(state = state)
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                state.countdownValue?.let { countdown ->
                    Text(
                        text = countdown.toString(),
                        color = Color.White,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                if (state.stage == ParentCameraStage.Summary) {
                    SummaryCard(
                        state = state,
                        onDone = onExit,
                        onRetry = onRetry,
                        onSaveVideo = onSaveVideo,
                    )
                }
                if (!state.cameraPermissionGranted || state.errorState != null) {
                    CameraMessageCard(
                        state = state,
                        onRequestPermission = onRequestPermission,
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            CameraControls(
                state = state,
                onStart = onStart,
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun CameraPreview(
    onControllerReady: (JumpCameraController) -> Unit,
    onCameraError: (Throwable) -> Unit,
    onRecordingStarted: () -> Unit,
    onRecordingFinalized: (Result<java.io.File>) -> Unit,
    onPoseFrame: (PoseAnalysisResult) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )

    DisposableEffect(context, lifecycleOwner, previewView) {
        val analyzer = PoseFrameAnalyzer(
            onResult = onPoseFrame,
            onError = {},
        )
        val controller = JumpCameraController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            analyzer = analyzer,
            onError = onCameraError,
            onRecordingStarted = onRecordingStarted,
            onRecordingFinalized = onRecordingFinalized,
        )
        controller.start()
        onControllerReady(controller)

        onDispose {
            controller.stop()
        }
    }
}

@Composable
private fun CameraHeader(state: ParentCameraUiState) {
    if (state.stage == ParentCameraStage.Recording) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                InfoBadge(value = formatDuration(state.remainingSeconds))
                Spacer(modifier = Modifier.weight(1f))
                InfoBadge(value = state.jumpCount.toString())
            }
            Spacer(modifier = Modifier.height(12.dp))
            AnalysisStatusCard(state = state)
        }
    } else {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .background(CameraPanelLight, RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccessibilityNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.parent_camera_guide),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CameraControls(
    state: ParentCameraUiState,
    onStart: () -> Unit,
    onExit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.stage != ParentCameraStage.Summary) {
            Spacer(modifier = Modifier.width(68.dp))
            TextButton(
                onClick = onStart,
                enabled = state.stage == ParentCameraStage.Framing &&
                    state.cameraPermissionGranted &&
                    state.errorState == null,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = CameraButton,
                    contentColor = Color(0xFF2E2D31),
                    disabledContainerColor = Color(0x99F2F0E8),
                    disabledContentColor = Color(0xAA2E2D31),
                ),
            ) {
                Text(
                    text = if (state.stage == ParentCameraStage.Recording) {
                        stringResource(R.string.parent_camera_recording)
                    } else {
                        stringResource(R.string.parent_camera_start)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.width(14.dp))
        FilledIconButton(
            onClick = onExit,
            modifier = Modifier.size(54.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Logout,
                contentDescription = stringResource(R.string.parent_camera_exit),
            )
        }
    }
}

@Composable
private fun AnalysisStatusCard(state: ParentCameraUiState) {
    val guidance = when (state.captureQuality.issue) {
        CaptureQualityIssue.Good -> when (state.trackingQuality) {
            TrackingQuality.Tracking -> stringResource(R.string.parent_camera_analysis_tracking)
            TrackingQuality.NoPose -> stringResource(R.string.parent_camera_analysis_lost)
            TrackingQuality.PartialBody -> stringResource(R.string.parent_camera_analysis_waiting)
        }
        CaptureQualityIssue.NoPose -> stringResource(R.string.parent_camera_analysis_lost)
        CaptureQualityIssue.PartialBody -> stringResource(R.string.parent_camera_analysis_waiting)
        CaptureQualityIssue.TooFar -> stringResource(R.string.parent_camera_analysis_too_far)
        CaptureQualityIssue.EdgeClipped -> stringResource(R.string.parent_camera_analysis_edge_clipped)
        CaptureQualityIssue.LowLightOrBlur -> stringResource(R.string.parent_camera_analysis_low_light)
        CaptureQualityIssue.Shaky -> stringResource(R.string.parent_camera_analysis_shaky)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CameraPanel.copy(alpha = 0.72f), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = guidance,
            modifier = Modifier.weight(1f),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(
                R.string.parent_camera_analysis_metrics,
                state.analysisFps,
                state.inferenceMs,
                state.captureQuality.score,
            ),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CameraMessageCard(
    state: ParentCameraUiState,
    onRequestPermission: () -> Unit,
) {
    val title = when (state.errorState) {
        ParentCameraError.PermissionDenied, null -> stringResource(R.string.parent_camera_permission_title)
        ParentCameraError.NoBackCamera -> stringResource(R.string.parent_camera_no_camera)
        ParentCameraError.CameraUnavailable -> stringResource(R.string.parent_camera_error)
    }
    val body = when (state.errorState) {
        ParentCameraError.PermissionDenied, null -> stringResource(R.string.parent_camera_permission_body)
        ParentCameraError.NoBackCamera -> stringResource(R.string.parent_camera_no_camera)
        ParentCameraError.CameraUnavailable -> stringResource(R.string.parent_camera_error)
    }
    Column(
        modifier = Modifier
            .width(320.dp)
            .background(CameraPanel, RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.CameraAlt,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (state.errorState == null || state.errorState == ParentCameraError.PermissionDenied) {
            Spacer(modifier = Modifier.height(18.dp))
            TextButton(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White,
                ),
            ) {
                Text(text = stringResource(R.string.parent_camera_permission_action))
            }
        }
    }
}

@Composable
private fun SummaryCard(
    state: ParentCameraUiState,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onSaveVideo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .background(CameraPanel, RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(28.dp))
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 22.dp),
    ) {
        Text(
            text = stringResource(R.string.parent_camera_summary_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.parent_camera_summary_score),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = state.jumpCount.toString(),
            color = Color.White,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row {
            ResultMetric(
                label = stringResource(R.string.parent_camera_summary_time),
                value = formatDuration(state.durationSeconds),
                modifier = Modifier.weight(1f),
            )
            ResultMetric(
                label = stringResource(R.string.parent_camera_summary_best),
                value = maxOf(state.bestRecord, state.jumpCount).toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(22.dp))
        TextButton(
            onClick = onSaveVideo,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            enabled = state.videoFile != null && !state.isSaving && !state.isFinalizingVideo,
            colors = ButtonDefaults.textButtonColors(
                containerColor = Color(0xFF4CAF50),
                contentColor = Color.White,
                disabledContainerColor = Color(0x334CAF50),
                disabledContentColor = Color.White.copy(alpha = 0.5f),
            ),
        ) {
            Text(
                text = if (state.isFinalizingVideo) {
                    stringResource(R.string.parent_camera_processing_video)
                } else if (state.isSaving) {
                    stringResource(R.string.parent_camera_saving)
                } else {
                    stringResource(R.string.parent_camera_save_video)
                },
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row {
            TextButton(
                onClick = onDone,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                ),
            ) {
                Text(text = stringResource(R.string.parent_camera_summary_save))
            }
            Spacer(modifier = Modifier.width(12.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White,
                ),
            ) {
                Text(text = stringResource(R.string.parent_camera_summary_retry))
            }
        }
    }
}

@Composable
private fun ResultMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun InfoBadge(value: String) {
    Box(
        modifier = Modifier
            .width(122.dp)
            .background(Color(0xCC111111), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PoseOverlayCanvas(overlay: PoseOverlay) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pointsByLandmark = overlay.points.associateBy { it.landmark }
        SkeletonSegments.forEach { (start, end) ->
            val startPoint = pointsByLandmark[start]
            val endPoint = pointsByLandmark[end]
            if (startPoint != null && endPoint != null) {
                drawLine(
                    color = CameraAccent.copy(alpha = 0.88f),
                    start = Offset(startPoint.x * size.width, startPoint.y * size.height),
                    end = Offset(endPoint.x * size.width, endPoint.y * size.height),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        overlay.points.forEach { point ->
            drawCircle(
                color = Color(0xFFE3FFF8),
                radius = 5.dp.toPx(),
                center = Offset(point.x * size.width, point.y * size.height),
            )
        }
    }
}

private val SkeletonSegments = listOf(
    BodyLandmark.LeftShoulder to BodyLandmark.RightShoulder,
    BodyLandmark.LeftShoulder to BodyLandmark.LeftHip,
    BodyLandmark.RightShoulder to BodyLandmark.RightHip,
    BodyLandmark.LeftHip to BodyLandmark.RightHip,
    BodyLandmark.LeftHip to BodyLandmark.LeftKnee,
    BodyLandmark.RightHip to BodyLandmark.RightKnee,
    BodyLandmark.LeftKnee to BodyLandmark.LeftAnkle,
    BodyLandmark.RightKnee to BodyLandmark.RightAnkle,
    BodyLandmark.LeftAnkle to BodyLandmark.LeftHeel,
    BodyLandmark.RightAnkle to BodyLandmark.RightHeel,
)

@Composable
private fun FocusFrame() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val frameWidth = size.width * 0.72f
        val frameHeight = size.height * 0.68f
        val topLeft = Offset(
            x = (size.width - frameWidth) / 2f,
            y = (size.height - frameHeight) / 2f,
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.82f),
            topLeft = topLeft,
            size = androidx.compose.ui.geometry.Size(frameWidth, frameHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(42f, 42f),
            style = Stroke(width = 4.dp.toPx()),
        )
    }
}

@Composable
private fun PermissionBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF3C403D), Color(0xFF181818)),
                ),
            ),
    )
}

@Preview(showBackground = true)
@Composable
private fun ParentCameraScreenPreview() {
    PttsTheme {
        ParentCameraContent(
            state = ParentCameraUiState(
                cameraPermissionGranted = true,
                stage = ParentCameraStage.Recording,
                remainingSeconds = JumpSessionDefaults.InitialDurationSeconds,
                jumpCount = 12,
                analysisFps = 24f,
                inferenceMs = 18,
            ),
            onStart = {},
            onRetry = {},
            onRequestPermission = {},
            onExit = {},
            onSaveVideo = {},
            onControllerReady = { _ -> },
            onCameraError = { _ -> },
            onRecordingStarted = {},
            onRecordingFinalized = { _ -> },
            onPoseFrame = { _ -> },
        )
    }
}
