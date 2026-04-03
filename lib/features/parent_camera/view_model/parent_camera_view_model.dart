import 'dart:async';
import 'dart:math' as math;
import 'dart:ui';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:tiaosheng/core/i18n/app_i18n.dart';
import 'package:tiaosheng/features/jump_session/data/jump_session_models.dart';
import 'package:tiaosheng/features/jump_session/view_model/jump_session_view_model.dart';
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
  final List<int> _jumpEventMillis = [];

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
      unawaited(_disposeCameraResources());
    });

    if (!_hasInitialized) {
      _hasInitialized = true;
      Future<void>.microtask(initializeCamera);
    }

    return ParentCameraState.initial(
      recordDurationSeconds: recordDurationSeconds,
    );
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
      clearFeedback: true,
      clearFeedbackDetail: true,
      clearError: true,
      clearErrorDetail: true,
    );
    _captureStartedAt = null;
    _sessionStartedAt = null;
    _jumpEventMillis.clear();
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
    try {
      await device.startRecording();
      if (!ref.mounted || token != _countdownToken) {
        return false;
      }
      _captureStartedAt = DateTime.now();
      _sessionStartedAt = null;
      _jumpEventMillis.clear();
      return true;
    } catch (_) {
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
      remainingRecordSeconds: state.recordDurationSeconds,
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
    state = state.copyWith(
      isRecording: false,
      isProcessingVideo: true,
      isFrameVisible: true,
      clearError: true,
      clearErrorDetail: true,
      clearFeedback: true,
      clearFeedbackDetail: true,
    );

    final stopResult = await _stopRecordingDevice(device);
    final baseSummary = _buildSummary();
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

  ParentCameraSummary _buildSummary({String? videoPath}) {
    return ParentCameraSummary(
      recordId: DateTime.now().millisecondsSinceEpoch.toString(),
      startedAt: _sessionStartedAt ?? DateTime.now(),
      jumpCount: state.jumpCount,
      durationSeconds: _completedDurationSeconds(),
      videoPath: videoPath,
      jumpEventMillis: List<int>.unmodifiable(_jumpEventMillis),
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
      remainingRecordSeconds: state.recordDurationSeconds,
    );
    _captureStartedAt = null;
    _sessionStartedAt = null;
    _jumpEventMillis.clear();
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
        isCountdownActive: false,
        resetCountdownValue: true,
        isRecording: false,
        isProcessingVideo: false,
        isFrameVisible: false,
        remainingRecordSeconds: recordDurationSeconds,
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
      isCountdownActive: false,
      resetCountdownValue: true,
      isRecording: false,
      isProcessingVideo: false,
      isFrameVisible: false,
      remainingRecordSeconds: recordDurationSeconds,
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

    if (device == null) {
      _captureStartedAt = null;
      _sessionStartedAt = null;
      _jumpEventMillis.clear();
      return _ReleaseCameraResult(errorKey: captureErrorKey);
    }

    var errorKey = captureErrorKey;
    _captureStartedAt = null;
    _sessionStartedAt = null;
    _jumpEventMillis.clear();

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
