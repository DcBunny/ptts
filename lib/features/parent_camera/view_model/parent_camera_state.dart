import 'package:tiaosheng/features/parent_camera/data/parent_camera_service.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_models.dart';

class ParentCameraState {
  const ParentCameraState({
    required this.recordDurationSeconds,
    required this.isInitializing,
    required this.isAnalyzerInitializing,
    required this.isAnalyzerReady,
    required this.isCountdownActive,
    required this.countdownValue,
    required this.isRecording,
    required this.isProcessingVideo,
    required this.isFrameVisible,
    required this.remainingRecordSeconds,
    required this.jumpCount,
    required this.analysisStatusKey,
    required this.analysisFps,
    required this.analysisLatencyMs,
    required this.didApplyCountCorrection,
    required this.device,
    required this.errorKey,
    required this.errorDetail,
    required this.summary,
    required this.isSavingVideo,
    required this.isVideoSaved,
    required this.feedbackKey,
    required this.feedbackDetail,
    required this.detectedPoses,
  });

  factory ParentCameraState.initial({required int recordDurationSeconds}) {
    return ParentCameraState(
      recordDurationSeconds: recordDurationSeconds,
      isInitializing: true,
      isAnalyzerInitializing: true,
      isAnalyzerReady: false,
      isCountdownActive: false,
      countdownValue: null,
      isRecording: false,
      isProcessingVideo: false,
      isFrameVisible: true,
      remainingRecordSeconds: recordDurationSeconds,
      jumpCount: 0,
      analysisStatusKey: 'parentCameraAnalysisPreparing',
      analysisFps: 0,
      analysisLatencyMs: 0,
      didApplyCountCorrection: false,
      device: null,
      errorKey: null,
      errorDetail: null,
      summary: null,
      isSavingVideo: false,
      isVideoSaved: false,
      feedbackKey: null,
      feedbackDetail: null,
      detectedPoses: const <JumpRopePose>[],
    );
  }

  final int recordDurationSeconds;
  final bool isInitializing;
  final bool isAnalyzerInitializing;
  final bool isAnalyzerReady;
  final bool isCountdownActive;
  final int? countdownValue;
  final bool isRecording;
  final bool isProcessingVideo;
  final bool isFrameVisible;
  final int remainingRecordSeconds;
  final int jumpCount;
  final String analysisStatusKey;
  final double analysisFps;
  final int analysisLatencyMs;
  final bool didApplyCountCorrection;
  final ParentCameraDevice? device;
  final String? errorKey;
  final String? errorDetail;
  final ParentCameraSummary? summary;
  final bool isSavingVideo;
  final bool isVideoSaved;
  final String? feedbackKey;
  final String? feedbackDetail;
  final List<JumpRopePose> detectedPoses;

  bool get hasPreview => device != null && device!.isInitialized;

  bool get canStart =>
      hasPreview &&
      !isInitializing &&
      isAnalyzerReady &&
      !isAnalyzerInitializing &&
      !isCountdownActive &&
      !isRecording &&
      !isProcessingVideo &&
      summary == null &&
      !isSavingVideo;

  bool get canSaveVideoToLibrary =>
      summary?.videoPath != null && !isProcessingVideo && !isSavingVideo;

  ParentCameraState copyWith({
    int? recordDurationSeconds,
    bool? isInitializing,
    bool? isAnalyzerInitializing,
    bool? isAnalyzerReady,
    bool? isCountdownActive,
    int? countdownValue,
    bool resetCountdownValue = false,
    bool? isRecording,
    bool? isProcessingVideo,
    bool? isFrameVisible,
    int? remainingRecordSeconds,
    int? jumpCount,
    String? analysisStatusKey,
    double? analysisFps,
    int? analysisLatencyMs,
    bool? didApplyCountCorrection,
    ParentCameraDevice? device,
    bool clearDevice = false,
    String? errorKey,
    bool clearError = false,
    String? errorDetail,
    bool clearErrorDetail = false,
    ParentCameraSummary? summary,
    bool clearSummary = false,
    bool? isSavingVideo,
    bool? isVideoSaved,
    String? feedbackKey,
    bool clearFeedback = false,
    String? feedbackDetail,
    bool clearFeedbackDetail = false,
    List<JumpRopePose>? detectedPoses,
  }) {
    return ParentCameraState(
      recordDurationSeconds:
          recordDurationSeconds ?? this.recordDurationSeconds,
      isInitializing: isInitializing ?? this.isInitializing,
      isAnalyzerInitializing:
          isAnalyzerInitializing ?? this.isAnalyzerInitializing,
      isAnalyzerReady: isAnalyzerReady ?? this.isAnalyzerReady,
      isCountdownActive: isCountdownActive ?? this.isCountdownActive,
      countdownValue: resetCountdownValue
          ? null
          : (countdownValue ?? this.countdownValue),
      isRecording: isRecording ?? this.isRecording,
      isProcessingVideo: isProcessingVideo ?? this.isProcessingVideo,
      isFrameVisible: isFrameVisible ?? this.isFrameVisible,
      remainingRecordSeconds:
          remainingRecordSeconds ?? this.remainingRecordSeconds,
      jumpCount: jumpCount ?? this.jumpCount,
      analysisStatusKey: analysisStatusKey ?? this.analysisStatusKey,
      analysisFps: analysisFps ?? this.analysisFps,
      analysisLatencyMs: analysisLatencyMs ?? this.analysisLatencyMs,
      didApplyCountCorrection:
          didApplyCountCorrection ?? this.didApplyCountCorrection,
      device: clearDevice ? null : (device ?? this.device),
      errorKey: clearError ? null : (errorKey ?? this.errorKey),
      errorDetail: clearErrorDetail ? null : (errorDetail ?? this.errorDetail),
      summary: clearSummary ? null : (summary ?? this.summary),
      isSavingVideo: isSavingVideo ?? this.isSavingVideo,
      isVideoSaved: isVideoSaved ?? this.isVideoSaved,
      feedbackKey: clearFeedback ? null : (feedbackKey ?? this.feedbackKey),
      feedbackDetail: clearFeedbackDetail
          ? null
          : (feedbackDetail ?? this.feedbackDetail),
      detectedPoses: detectedPoses ?? this.detectedPoses,
    );
  }
}

class ParentCameraSummary {
  const ParentCameraSummary({
    required this.recordId,
    required this.startedAt,
    required this.jumpCount,
    required this.durationSeconds,
    required this.videoPath,
    required this.jumpEventMillis,
  });

  final String recordId;
  final DateTime startedAt;
  final int jumpCount;
  final int durationSeconds;
  final String? videoPath;
  final List<int> jumpEventMillis;

  ParentCameraSummary copyWith({
    String? recordId,
    DateTime? startedAt,
    int? jumpCount,
    int? durationSeconds,
    String? videoPath,
    List<int>? jumpEventMillis,
  }) {
    return ParentCameraSummary(
      recordId: recordId ?? this.recordId,
      startedAt: startedAt ?? this.startedAt,
      jumpCount: jumpCount ?? this.jumpCount,
      durationSeconds: durationSeconds ?? this.durationSeconds,
      videoPath: videoPath ?? this.videoPath,
      jumpEventMillis: jumpEventMillis ?? this.jumpEventMillis,
    );
  }
}
