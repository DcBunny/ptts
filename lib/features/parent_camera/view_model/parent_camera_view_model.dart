import 'dart:async';
import 'dart:math' as math;
import 'dart:ui';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:tiaosheng/core/i18n/app_i18n.dart';
import 'package:tiaosheng/features/jump_session/data/jump_session_models.dart';
import 'package:tiaosheng/features/jump_session/view_model/jump_session_view_model.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_counter_engine.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_analyzer.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_models.dart';
import 'package:tiaosheng/features/parent_camera/data/parent_camera_frame.dart';
import 'package:tiaosheng/features/parent_camera/data/parent_camera_service.dart';
import 'package:tiaosheng/features/parent_camera/data/session_video_overlay_processor.dart';
import 'package:tiaosheng/features/parent_camera/data/session_video_overlay_timeline.dart';
import 'package:tiaosheng/features/parent_camera/data/video_library_saver.dart';
import 'package:tiaosheng/features/parent_camera/view_model/parent_camera_state.dart';

final parentCameraServiceProvider = Provider<ParentCameraService>((ref) {
  return CameraParentCameraService();
});

final videoLibrarySaverProvider = Provider<VideoLibrarySaver>((ref) {
  return GalVideoLibrarySaver();
});

final sessionVideoOverlayProcessorProvider =
    Provider<SessionVideoOverlayProcessor>((ref) {
      return NativeSessionVideoOverlayProcessor();
    });

final jumpRopePoseAnalyzerConfigProvider = Provider<JumpRopePoseAnalyzerConfig>(
  (ref) {
    return const JumpRopePoseAnalyzerConfig();
  },
);

final jumpRopeTemporalEnhancerProvider = Provider<JumpRopeTemporalEnhancer>((
  ref,
) {
  return const NoopJumpRopeTemporalEnhancer();
});

final jumpRopeCounterAnalyzerProvider = Provider<JumpRopePoseAnalyzer>((ref) {
  return NativeJumpRopePoseAnalyzer(
    config: ref.watch(jumpRopePoseAnalyzerConfigProvider),
  );
});

final parentCameraViewModelProvider =
    NotifierProvider.autoDispose<ParentCameraViewModel, ParentCameraState>(
      ParentCameraViewModel.new,
    );

class ParentCameraViewModel extends Notifier<ParentCameraState> {
  static const _countdownSeconds = 3;
  static const _overlayTimelineBuilder = SessionVideoOverlayTimelineBuilder();

  bool _hasInitialized = false;
  int _countdownToken = 0;
  ParentCameraDevice? _device;
  Timer? _recordTimer;
  DateTime? _captureStartedAt;
  DateTime? _sessionStartedAt;
  JumpRopePoseAnalyzer? _poseAnalyzer;
  JumpRopeCounterEngine? _jumpCounterEngine;
  final List<int> _jumpEventMillis = [];
  ParentCameraFrame? _pendingAnalysisFrame;
  int? _lastQueuedFrameTimestampMs;
  var _isAnalyzingFrame = false;
  var _hasAnalyzerError = false;

  JumpRopeCounterEngine get _counterEngine {
    return _jumpCounterEngine ??= JumpRopeCounterEngine(
      temporalEnhancer: ref.read(jumpRopeTemporalEnhancerProvider),
    );
  }

  @override
  ParentCameraState build() {
    final recordDurationSeconds = ref.watch(
      jumpSessionViewModelProvider.select(
        (state) => state.selectedDurationSeconds,
      ),
    );

    ref.onDispose(() {
      _countdownToken++;
      _recordTimer?.cancel();
      _pendingAnalysisFrame = null;
      unawaited(_disposeCameraResources());
      unawaited(_disposeAnalyzer());
    });

    if (!_hasInitialized) {
      _hasInitialized = true;
      Future<void>.microtask(_initializeRuntime);
    }

    return ParentCameraState.initial(
      recordDurationSeconds: recordDurationSeconds,
    );
  }

  Future<void> _initializeRuntime() async {
    await initializeCamera();
    await initializeAnalyzer();
  }

  Future<void> initializeCamera() async {
    if (_device != null) {
      return;
    }

    state = state.copyWith(isInitializing: true, clearError: true);

    try {
      final device = await ref
          .read(parentCameraServiceProvider)
          .createDefaultDevice();
      if (!ref.mounted) {
        await device.dispose();
        return;
      }

      _device = device;
      state = state.copyWith(
        isInitializing: false,
        device: device,
        isRecording: false,
        isFrameVisible: true,
        clearError: true,
        clearErrorDetail: true,
      );
    } on ParentCameraException catch (error) {
      if (!ref.mounted) {
        return;
      }
      state = state.copyWith(
        isInitializing: false,
        errorKey: error.errorKey,
        clearErrorDetail: true,
        clearDevice: true,
      );
    } catch (_) {
      if (!ref.mounted) {
        return;
      }
      state = state.copyWith(
        isInitializing: false,
        errorKey: 'cameraInitFailed',
        clearErrorDetail: true,
        clearDevice: true,
      );
    }
  }

  Future<void> initializeAnalyzer() async {
    if (_poseAnalyzer != null || _hasAnalyzerError) {
      return;
    }

    state = state.copyWith(
      isAnalyzerInitializing: true,
      isAnalyzerReady: false,
      analysisStatusKey: 'parentCameraAnalysisPreparing',
      clearError: true,
      clearErrorDetail: true,
    );

    final analyzer = ref.read(jumpRopeCounterAnalyzerProvider);
    try {
      await analyzer.initialize();
      if (!ref.mounted) {
        await analyzer.dispose();
        return;
      }

      _poseAnalyzer = analyzer;
      state = state.copyWith(
        isAnalyzerInitializing: false,
        isAnalyzerReady: true,
        analysisStatusKey: 'parentCameraAnalysisSearching',
        clearError: true,
        clearErrorDetail: true,
      );
    } on JumpRopePoseAnalyzerException catch (error) {
      _hasAnalyzerError = true;
      if (!ref.mounted) {
        return;
      }
      state = state.copyWith(
        isAnalyzerInitializing: false,
        isAnalyzerReady: false,
        errorKey: error.errorKey,
        errorDetail: error.detail,
      );
    } catch (_) {
      _hasAnalyzerError = true;
      if (!ref.mounted) {
        return;
      }
      state = state.copyWith(
        isAnalyzerInitializing: false,
        isAnalyzerReady: false,
        errorKey: 'jumpCounterInitFailed',
        clearErrorDetail: true,
      );
    }
  }

  Future<void> startCountdownAndRecording() async {
    final device = _device;
    if (!_canStartRecording(device)) {
      return;
    }

    final token = _beginRecordingFlow();
    final canEnterSession = await _runCountdown(token);
    if (!canEnterSession || !ref.mounted || token != _countdownToken) {
      return;
    }

    final hasStartedCapture = await _startCapture(device!, token);
    if (!hasStartedCapture || !ref.mounted || token != _countdownToken) {
      return;
    }

    _beginActiveRecording();
  }

  bool _canStartRecording(ParentCameraDevice? device) {
    return device != null &&
        !state.isInitializing &&
        state.isAnalyzerReady &&
        !state.isAnalyzerInitializing &&
        !state.isCountdownActive &&
        !state.isRecording &&
        !state.isProcessingVideo &&
        !state.isSavingVideo;
  }

  int _beginRecordingFlow() {
    final token = ++_countdownToken;
    _recordTimer?.cancel();
    state = state.copyWith(
      clearSummary: true,
      isProcessingVideo: false,
      isVideoSaved: false,
      isSavingVideo: false,
      detectedPoses: const <JumpRopePose>[],
      clearFeedback: true,
      clearFeedbackDetail: true,
      clearError: true,
      clearErrorDetail: true,
    );
    _captureStartedAt = null;
    _sessionStartedAt = null;
    _jumpEventMillis.clear();
    _counterEngine.reset();
    _pendingAnalysisFrame = null;
    _lastQueuedFrameTimestampMs = null;
    _isAnalyzingFrame = false;
    return token;
  }

  Future<bool> _runCountdown(int token) async {
    for (var remaining = _countdownSeconds; remaining > 0; remaining--) {
      state = state.copyWith(
        isCountdownActive: true,
        countdownValue: remaining,
        clearError: true,
        clearErrorDetail: true,
      );
      await Future<void>.delayed(const Duration(seconds: 1));
      if (!ref.mounted || token != _countdownToken) {
        return false;
      }
    }

    state = state.copyWith(
      isCountdownActive: false,
      resetCountdownValue: true,
      isFrameVisible: false,
      jumpCount: 0,
      remainingRecordSeconds: state.recordDurationSeconds,
      clearError: true,
      clearErrorDetail: true,
    );
    return true;
  }

  Future<bool> _startCapture(ParentCameraDevice device, int token) async {
    final analyzer = _poseAnalyzer;
    if (analyzer == null) {
      state = state.copyWith(
        isRecording: false,
        isFrameVisible: true,
        errorKey: 'jumpCounterInitFailed',
        clearErrorDetail: true,
      );
      return false;
    }

    try {
      await analyzer.startSession();
      _captureStartedAt = DateTime.now();
      _sessionStartedAt = null;
      _jumpEventMillis.clear();
      _counterEngine.reset();
      _pendingAnalysisFrame = null;
      _lastQueuedFrameTimestampMs = null;
      _isAnalyzingFrame = false;
      await device.startRecording(onFrameAvailable: _queueAnalysisFrame);
      if (!ref.mounted || token != _countdownToken) {
        return false;
      }
      return true;
    } on JumpRopePoseAnalyzerException catch (error) {
      _captureStartedAt = null;
      if (!ref.mounted || token != _countdownToken) {
        return false;
      }
      state = state.copyWith(
        isRecording: false,
        isFrameVisible: true,
        errorKey: error.errorKey,
        errorDetail: error.detail,
      );
      return false;
    } catch (_) {
      _captureStartedAt = null;
      try {
        await analyzer.stopSession();
      } catch (_) {}
      if (!ref.mounted || token != _countdownToken) {
        return false;
      }
      state = state.copyWith(
        isRecording: false,
        isFrameVisible: true,
        errorKey: 'cameraRecordStartFailed',
        clearErrorDetail: true,
      );
      return false;
    }
  }

  void _beginActiveRecording() {
    _sessionStartedAt = DateTime.now();
    state = state.copyWith(
      isRecording: true,
      jumpCount: 0,
      analysisStatusKey: 'parentCameraAnalysisSearching',
      analysisFps: 0,
      analysisLatencyMs: 0,
      didApplyCountCorrection: false,
      remainingRecordSeconds: state.recordDurationSeconds,
      detectedPoses: const <JumpRopePose>[],
    );
    _startRecordingTimer();
  }

  void incrementJumpCount() {
    if (!state.isRecording) {
      return;
    }
    _addJumpEvent();
    state = state.copyWith(jumpCount: state.jumpCount + 1);
  }

  void _queueAnalysisFrame(ParentCameraFrame frame) {
    final captureStartedAt = _captureStartedAt;
    if (!state.isRecording || captureStartedAt == null) {
      return;
    }

    var relativeTimestampMs =
        frame.timestampMs - captureStartedAt.millisecondsSinceEpoch;
    if (relativeTimestampMs < 0) {
      relativeTimestampMs = 0;
    }

    final lastQueuedFrameTimestampMs = _lastQueuedFrameTimestampMs;
    if (lastQueuedFrameTimestampMs != null &&
        relativeTimestampMs <= lastQueuedFrameTimestampMs) {
      relativeTimestampMs = lastQueuedFrameTimestampMs + 1;
    }
    _lastQueuedFrameTimestampMs = relativeTimestampMs;

    _pendingAnalysisFrame = frame.copyWith(timestampMs: relativeTimestampMs);
    if (_isAnalyzingFrame) {
      return;
    }
    unawaited(_drainAnalysisFrames());
  }

  Future<void> _drainAnalysisFrames() async {
    final analyzer = _poseAnalyzer;
    if (analyzer == null || _isAnalyzingFrame) {
      return;
    }

    _isAnalyzingFrame = true;
    try {
      while (ref.mounted && state.isRecording) {
        final frame = _takeNextAnalysisFrame();
        if (frame == null) {
          break;
        }
        try {
          final poseResult = await analyzer.analyzeFrame(frame);
          if (!ref.mounted || !state.isRecording) {
            break;
          }
          final counterUpdate = _counterEngine.ingest(poseResult);
          _applyCounterUpdate(counterUpdate, poseResult.poses);
        } on JumpRopePoseAnalyzerException catch (error) {
          _handleAnalyzerRuntimeError(error);
          break;
        } catch (_) {
          _handleAnalyzerRuntimeError(
            const JumpRopePoseAnalyzerException('jumpCounterRuntimeFailed'),
          );
          break;
        }
      }
    } finally {
      _isAnalyzingFrame = false;
    }
  }

  ParentCameraFrame? _takeNextAnalysisFrame() {
    final frame = _pendingAnalysisFrame;
    _pendingAnalysisFrame = null;
    return frame;
  }

  void _applyCounterUpdate(
    JumpRopeCounterUpdate update,
    List<JumpRopePose> detectedPoses,
  ) {
    final newJumpEventMillis = update.newJumpEventMillis;
    if (newJumpEventMillis != null) {
      _jumpEventMillis.add(newJumpEventMillis);
    }
    state = state.copyWith(
      jumpCount: update.jumpCount,
      analysisStatusKey: update.statusKey,
      analysisFps: update.analysisFps,
      analysisLatencyMs: update.analysisLatencyMs,
      detectedPoses: List<JumpRopePose>.unmodifiable(detectedPoses),
      clearError: update.statusKey == 'parentCameraAnalysisTracking',
    );
  }

  void _handleAnalyzerRuntimeError(JumpRopePoseAnalyzerException error) {
    if (!ref.mounted) {
      return;
    }
    _pendingAnalysisFrame = null;
    state = state.copyWith(
      analysisStatusKey: 'parentCameraAnalysisPaused',
      errorKey: error.errorKey,
      errorDetail: error.detail,
      detectedPoses: const <JumpRopePose>[],
    );
  }

  void _addJumpEvent() {
    final captureStartedAt = _captureStartedAt;
    if (captureStartedAt == null) {
      return;
    }
    final elapsedMillis = DateTime.now()
        .difference(captureStartedAt)
        .inMilliseconds;
    if (elapsedMillis < 0) {
      return;
    }
    _jumpEventMillis.add(elapsedMillis);
  }

  Future<void> prepareToLeave() async {
    _countdownToken++;
    _recordTimer?.cancel();
    state = state.copyWith(
      isCountdownActive: false,
      resetCountdownValue: true,
      clearSummary: true,
      isProcessingVideo: false,
      isSavingVideo: false,
      detectedPoses: const <JumpRopePose>[],
      clearFeedback: true,
      clearFeedbackDetail: true,
      clearErrorDetail: true,
    );
    await _releaseCamera(clearState: true);
  }

  Future<void> stopRecordingSession() async {
    final device = _device;
    if (device == null || !state.isRecording) {
      return;
    }

    _recordTimer?.cancel();
    _pendingAnalysisFrame = null;
    state = state.copyWith(
      isRecording: false,
      isProcessingVideo: true,
      isFrameVisible: true,
      analysisStatusKey: 'parentCameraAnalysisCorrection',
      detectedPoses: const <JumpRopePose>[],
      clearError: true,
      clearErrorDetail: true,
      clearFeedback: true,
      clearFeedbackDetail: true,
    );

    final stopResult = await _stopRecordingDevice(device);
    await _stopAnalyzerSession();
    final finalCountResult = _resolveFinalCountResult(
      _counterEngine.finalizeSession(),
    );
    _jumpEventMillis
      ..clear()
      ..addAll(finalCountResult.jumpEventMillis);
    final baseSummary = _buildSummary(
      jumpCount: finalCountResult.jumpCount,
      jumpEventMillis: finalCountResult.jumpEventMillis,
    );
    final finalizedVideoResult = await _finalizeRecordedVideo(
      baseSummary: baseSummary,
      sourceVideoPath: stopResult.videoPath,
      stopErrorKey: stopResult.errorKey,
    );
    final finalizedSummary = finalizedVideoResult.summary;

    _captureStartedAt = null;
    _sessionStartedAt = null;

    if (!ref.mounted) {
      return;
    }

    await _saveSessionRecord(finalizedSummary);
    if (!ref.mounted) {
      return;
    }

    _showSummaryAfterRecording(
      summary: finalizedSummary,
      errorKey: _resolveSummaryErrorKey(
        stopErrorKey: stopResult.errorKey,
        summary: finalizedSummary,
      ),
      errorDetail: finalizedVideoResult.errorDetail,
    );
    state = state.copyWith(
      analysisStatusKey: finalCountResult.statusKey,
      analysisFps: finalCountResult.analysisFps,
      analysisLatencyMs: finalCountResult.analysisLatencyMs,
      didApplyCountCorrection: finalCountResult.correctionApplied,
    );
  }

  Future<_StopRecordingResult> _stopRecordingDevice(
    ParentCameraDevice device,
  ) async {
    try {
      final videoPath = await device.stopRecording();
      return _StopRecordingResult(videoPath: videoPath, errorKey: null);
    } catch (_) {
      return const _StopRecordingResult(
        videoPath: null,
        errorKey: 'cameraRecordStopFailed',
      );
    }
  }

  ParentCameraSummary _buildSummary({
    String? videoPath,
    int? jumpCount,
    List<int>? jumpEventMillis,
  }) {
    return ParentCameraSummary(
      recordId: DateTime.now().millisecondsSinceEpoch.toString(),
      startedAt: _sessionStartedAt ?? DateTime.now(),
      jumpCount: jumpCount ?? state.jumpCount,
      durationSeconds: _completedDurationSeconds(),
      videoPath: videoPath,
      jumpEventMillis: List<int>.unmodifiable(
        jumpEventMillis ?? _jumpEventMillis,
      ),
    );
  }

  Future<_FinalizeVideoResult> _finalizeRecordedVideo({
    required ParentCameraSummary baseSummary,
    required String? sourceVideoPath,
    required String? stopErrorKey,
  }) async {
    if (sourceVideoPath == null || sourceVideoPath.isEmpty) {
      return _FinalizeVideoResult(summary: baseSummary, errorDetail: null);
    }
    if (stopErrorKey != null) {
      return _FinalizeVideoResult(
        summary: baseSummary.copyWith(videoPath: sourceVideoPath),
        errorDetail: null,
      );
    }

    try {
      final outputPath = await ref
          .read(sessionVideoOverlayProcessorProvider)
          .process(
            inputPath: sourceVideoPath,
            overlayItems: _buildOverlayItems(baseSummary),
          );
      return _FinalizeVideoResult(
        summary: baseSummary.copyWith(videoPath: outputPath),
        errorDetail: null,
      );
    } on SessionVideoOverlayException catch (error) {
      return _FinalizeVideoResult(
        summary: baseSummary,
        errorDetail: error.detail,
      );
    } catch (_) {
      return _FinalizeVideoResult(summary: baseSummary, errorDetail: null);
    }
  }

  String? _resolveSummaryErrorKey({
    required String? stopErrorKey,
    required ParentCameraSummary summary,
  }) {
    if (stopErrorKey != null) {
      return stopErrorKey;
    }
    if (summary.videoPath == null || summary.videoPath!.isEmpty) {
      return 'videoComposeFailed';
    }
    return null;
  }

  void _showSummaryAfterRecording({
    required ParentCameraSummary summary,
    required String? errorKey,
    required String? errorDetail,
  }) {
    state = state.copyWith(
      isRecording: false,
      isProcessingVideo: false,
      isFrameVisible: true,
      errorKey: errorKey,
      clearError: errorKey == null,
      errorDetail: errorDetail,
      clearErrorDetail: errorDetail == null,
      summary: summary,
      isVideoSaved: false,
      isSavingVideo: false,
      analysisStatusKey: 'parentCameraAnalysisCompleted',
      clearFeedback: true,
      clearFeedbackDetail: true,
    );
  }

  Future<void> _saveSessionRecord(ParentCameraSummary summary) async {
    final videoPath = summary.videoPath;
    if (videoPath == null || videoPath.isEmpty) {
      return;
    }

    try {
      final repository = ref.read(jumpSessionRepositoryProvider);
      await repository.saveRecord(
        JumpSessionRecord(
          id: summary.recordId,
          startedAt: summary.startedAt,
          durationSeconds: summary.durationSeconds,
          jumpCount: summary.jumpCount,
          videoPath: videoPath,
        ),
      );
      await ref.read(jumpSessionViewModelProvider.notifier).loadHistory();
    } catch (_) {}
  }

  Future<void> saveVideoToLibrary() async {
    final summary = state.summary;
    if (summary == null || state.isSavingVideo || state.isVideoSaved) {
      return;
    }

    final videoPath = summary.videoPath;
    if (videoPath == null || videoPath.isEmpty) {
      state = state.copyWith(
        feedbackKey: 'videoUnavailable',
        clearFeedbackDetail: true,
        clearErrorDetail: true,
      );
      return;
    }

    state = state.copyWith(
      isSavingVideo: true,
      clearFeedback: true,
      clearFeedbackDetail: true,
      clearError: true,
      clearErrorDetail: true,
    );

    try {
      await ref.read(videoLibrarySaverProvider).saveVideo(videoPath);
      if (!ref.mounted) {
        return;
      }
      state = state.copyWith(
        isSavingVideo: false,
        isVideoSaved: true,
        feedbackKey: 'videoSaveSuccess',
        clearFeedbackDetail: true,
      );
    } on VideoLibrarySaveException catch (error) {
      if (!ref.mounted) {
        return;
      }
      state = state.copyWith(
        isSavingVideo: false,
        feedbackKey: error.errorKey,
        feedbackDetail: error.detail,
      );
    } catch (_) {
      if (!ref.mounted) {
        return;
      }
      state = state.copyWith(
        isSavingVideo: false,
        feedbackKey: 'videoSaveFailed',
        clearFeedbackDetail: true,
      );
    }
  }

  Future<void> retryRecording() async {
    if (state.isSavingVideo || state.isProcessingVideo) {
      return;
    }

    state = state.copyWith(
      isCountdownActive: false,
      resetCountdownValue: true,
      clearSummary: true,
      isProcessingVideo: false,
      isVideoSaved: false,
      clearFeedback: true,
      clearFeedbackDetail: true,
      clearError: true,
      clearErrorDetail: true,
      isFrameVisible: true,
      jumpCount: 0,
      analysisStatusKey: 'parentCameraAnalysisSearching',
      analysisFps: 0,
      analysisLatencyMs: 0,
      didApplyCountCorrection: false,
      remainingRecordSeconds: state.recordDurationSeconds,
      detectedPoses: const <JumpRopePose>[],
    );
    _captureStartedAt = null;
    _sessionStartedAt = null;
    _jumpEventMillis.clear();
    _counterEngine.reset();
    _pendingAnalysisFrame = null;
    _lastQueuedFrameTimestampMs = null;
  }

  void clearFeedback() {
    if (state.feedbackKey == null) {
      return;
    }
    state = state.copyWith(clearFeedback: true, clearFeedbackDetail: true);
  }

  Future<void> _releaseCamera({bool clearState = false}) async {
    final recordDurationSeconds = state.recordDurationSeconds;
    if (clearState && ref.mounted) {
      state = state.copyWith(
        isInitializing: false,
        isAnalyzerInitializing: false,
        isAnalyzerReady: _poseAnalyzer != null,
        isCountdownActive: false,
        resetCountdownValue: true,
        isRecording: false,
        isProcessingVideo: false,
        isFrameVisible: false,
        remainingRecordSeconds: recordDurationSeconds,
        analysisStatusKey: 'parentCameraAnalysisSearching',
        analysisFps: 0,
        analysisLatencyMs: 0,
        didApplyCountCorrection: false,
        detectedPoses: const <JumpRopePose>[],
        clearDevice: true,
        clearSummary: true,
        isSavingVideo: false,
        clearFeedback: true,
        clearFeedbackDetail: true,
        clearError: true,
        clearErrorDetail: true,
      );
      // 先把 CameraPreview 从组件树移除，再释放底层 controller，
      // 避免 disposed controller 仍被上一帧的预览组件访问。
      await Future<void>.delayed(Duration.zero);
    }

    final releaseResult = await _disposeCameraResources(
      captureErrorKey: state.errorKey,
    );

    if (!clearState || !ref.mounted) {
      return;
    }

    state = state.copyWith(
      isInitializing: false,
      isAnalyzerInitializing: false,
      isAnalyzerReady: _poseAnalyzer != null,
      isCountdownActive: false,
      resetCountdownValue: true,
      isRecording: false,
      isProcessingVideo: false,
      isFrameVisible: false,
      remainingRecordSeconds: recordDurationSeconds,
      analysisStatusKey: 'parentCameraAnalysisSearching',
      analysisFps: 0,
      analysisLatencyMs: 0,
      didApplyCountCorrection: false,
      detectedPoses: const <JumpRopePose>[],
      errorKey: releaseResult.errorKey,
      clearErrorDetail: true,
      clearDevice: true,
      clearError: releaseResult.errorKey == null,
      clearSummary: true,
      isSavingVideo: false,
      clearFeedback: true,
      clearFeedbackDetail: true,
    );
  }

  Future<_ReleaseCameraResult> _disposeCameraResources({
    String? captureErrorKey,
  }) async {
    final device = _device;
    _device = null;
    _recordTimer?.cancel();
    _recordTimer = null;
    _pendingAnalysisFrame = null;
    _isAnalyzingFrame = false;
    _lastQueuedFrameTimestampMs = null;

    if (device == null) {
      _captureStartedAt = null;
      _sessionStartedAt = null;
      _jumpEventMillis.clear();
      _counterEngine.reset();
      return _ReleaseCameraResult(errorKey: captureErrorKey);
    }

    var errorKey = captureErrorKey;
    _captureStartedAt = null;
    _sessionStartedAt = null;
    _jumpEventMillis.clear();
    _counterEngine.reset();
    _lastQueuedFrameTimestampMs = null;

    try {
      if (device.isRecording) {
        await device.stopRecording();
      }
    } catch (_) {
      errorKey = 'cameraRecordStopFailed';
    } finally {
      await device.dispose();
    }

    return _ReleaseCameraResult(errorKey: errorKey);
  }

  Future<void> _stopAnalyzerSession() async {
    final analyzer = _poseAnalyzer;
    if (analyzer == null) {
      return;
    }
    try {
      await analyzer.stopSession();
    } catch (_) {}
  }

  Future<void> _disposeAnalyzer() async {
    final analyzer = _poseAnalyzer;
    _poseAnalyzer = null;
    if (analyzer == null) {
      return;
    }
    try {
      await analyzer.dispose();
    } catch (_) {}
  }

  JumpRopeCounterSessionResult _resolveFinalCountResult(
    JumpRopeCounterSessionResult counterResult,
  ) {
    if (counterResult.jumpCount > 0 || _jumpEventMillis.isEmpty) {
      return counterResult;
    }
    return JumpRopeCounterSessionResult(
      jumpCount: state.jumpCount,
      jumpEventMillis: List<int>.unmodifiable(_jumpEventMillis),
      analysisFps: counterResult.analysisFps,
      analysisLatencyMs: counterResult.analysisLatencyMs,
      statusKey: state.analysisStatusKey,
      correctionApplied: false,
    );
  }

  void _startRecordingTimer() {
    _recordTimer?.cancel();
    _recordTimer = Timer.periodic(const Duration(milliseconds: 250), (_) {
      if (!ref.mounted || !state.isRecording) {
        _recordTimer?.cancel();
        return;
      }

      final next = _resolveRemainingRecordSeconds();
      if (next <= 0) {
        state = state.copyWith(remainingRecordSeconds: 0);
        unawaited(stopRecordingSession());
        return;
      }

      if (next != state.remainingRecordSeconds) {
        state = state.copyWith(remainingRecordSeconds: next);
      }
    });
  }

  int _resolveRemainingRecordSeconds() {
    final sessionStartedAt = _sessionStartedAt;
    if (sessionStartedAt == null) {
      return state.remainingRecordSeconds;
    }

    final elapsedSeconds = DateTime.now()
        .difference(sessionStartedAt)
        .inSeconds;
    final remaining = state.recordDurationSeconds - elapsedSeconds;
    return remaining <= 0 ? 0 : remaining;
  }

  int _completedDurationSeconds() {
    final sessionStartedAt = _sessionStartedAt;
    if (sessionStartedAt == null) {
      final fallbackDuration =
          state.recordDurationSeconds - state.remainingRecordSeconds;
      return fallbackDuration > 0 ? fallbackDuration : 0;
    }

    final elapsedSeconds = DateTime.now()
        .difference(sessionStartedAt)
        .inSeconds;
    if (elapsedSeconds <= 0) {
      return 0;
    }
    if (elapsedSeconds >= state.recordDurationSeconds) {
      return state.recordDurationSeconds;
    }
    return elapsedSeconds;
  }

  List<SessionVideoOverlayItem> _buildOverlayItems(
    ParentCameraSummary summary,
  ) {
    final i18n = AppI18n(PlatformDispatcher.instance.locale);
    return _overlayTimelineBuilder.build(
      countdownSeconds: 0,
      activeDurationSeconds: math.max(summary.durationSeconds, 1),
      jumpEventMillis: summary.jumpEventMillis,
      resolveText: i18n.parentCameraOverlayText,
    );
  }
}

class _StopRecordingResult {
  const _StopRecordingResult({required this.videoPath, required this.errorKey});

  final String? videoPath;
  final String? errorKey;
}

class _FinalizeVideoResult {
  const _FinalizeVideoResult({
    required this.summary,
    required this.errorDetail,
  });

  final ParentCameraSummary summary;
  final String? errorDetail;
}

class _ReleaseCameraResult {
  const _ReleaseCameraResult({required this.errorKey});

  final String? errorKey;
}
