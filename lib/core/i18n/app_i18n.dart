import 'package:flutter/material.dart';

class AppI18n {
  const AppI18n(this.locale);

  final Locale locale;

  static const LocalizationsDelegate<AppI18n> delegate = _AppI18nDelegate();

  static const supportedLocales = [Locale('zh'), Locale('en')];

  static AppI18n of(BuildContext context) {
    return Localizations.of<AppI18n>(context, AppI18n)!;
  }

  static const Map<String, Map<String, String>> _localizedValues = {
    'zh': {
      'appTitle': '葡萄跳绳',
      'sessionTitle': '跳绳首页',
      'bestRecord': '最佳记录',
      'bestRecordEmpty': '暂无训练记录',
      'bestRecordDetail': '历史最高跳绳次数',
      'duration': '跳绳时长',
      'durationAdjustHint': '每次点击增加或减少 30 秒，支持 10 秒起步',
      'seconds': '秒',
      'jumpCount': '跳绳次数',
      'remaining': '剩余时间',
      'start': '开始',
      'pause': '暂停',
      'resume': '继续',
      'finish': '结束并保存',
      'simulateJump': '手动 +1',
      'history': '历史记录',
      'emptyHistory': '暂无训练记录',
      'statusIdle': '待开始',
      'statusRunning': '进行中',
      'statusPaused': '已暂停',
      'statusCompleted': '已完成',
      'statusError': '异常',
      'recording': '录制中',
      'notRecording': '未录制',
      'parentPhoto': '家长拍照',
      'parentPhotoHint': '进入家长拍照页后即可开始录制',
      'parentCameraGuide': '请将跳绳者放在人像框内',
      'parentCameraStart': '开始记录',
      'parentCameraExit': '退出',
      'parentCameraReadyHint': '保持全身进入取景框，点击开始后将倒计时 3 秒',
      'parentCameraCountdownHint': '倒计时结束后将自动开始录制',
      'parentCameraRecordingHint': '正在记录跳绳过程，点击退出将结束拍摄',
      'parentCameraLoading': '正在启动相机',
      'parentCameraInitializing': '相机准备中',
      'parentCameraRecording': '录制中',
      'parentCameraProcessingVideo': '正在生成本地视频',
      'parentCameraAnalysisPreparing': '正在准备识别模型',
      'parentCameraAnalysisSearching': '请保持单人全身进入画面',
      'parentCameraAnalysisTracking': '正在实时识别跳绳',
      'parentCameraAnalysisLowConfidence': '画面不稳定，已暂停计数',
      'parentCameraAnalysisMultiplePeople': '检测到多人入镜，已暂停计数',
      'parentCameraAnalysisPaused': '识别异常，已暂停自动计数',
      'parentCameraAnalysisCorrection': '正在校正本次计数',
      'parentCameraAnalysisCompleted': '计数已完成',
      'parentCameraAnalysisMetrics': '识别',
      'parentCameraSummaryTitle': '本次成绩',
      'parentCameraSummaryScore': '成绩',
      'parentCameraSummaryTime': '时间',
      'parentCameraSummaryVideo': '视频',
      'parentCameraSummaryPending': '未保存',
      'parentCameraSummarySave': '保存到相册',
      'parentCameraSummarySaving': '保存到相册中',
      'parentCameraSummarySaved': '已保存到相册',
      'parentCameraSummaryRetry': '再来一次',
      'parentCameraOverlayStartJump': '开始起跳',
      'parentCameraOverlayRhythmStable': '节奏稳定',
      'parentCameraOverlayRhythmFast': '节奏加快',
      'parentCameraOverlayRhythmSlow': '节奏放缓',
      'cameraUnavailable': '未检测到可用摄像头',
      'cameraPermissionDenied': '未获得相机权限，请在系统设置中开启后重试',
      'cameraAccessRestricted': '当前设备限制访问摄像头',
      'cameraInitFailed': '相机启动失败，请稍后重试',
      'cameraRecordStartFailed': '开始录制失败，请重新尝试',
      'cameraRecordStopFailed': '结束录制失败，但你仍可以返回首页',
      'jumpCounterInitFailed': '跳绳识别初始化失败，请稍后重试',
      'jumpCounterRuntimeFailed': '跳绳识别中断，本次将停止自动计数',
      'jumpCounterBackendUnsupported': '当前识别后端暂不支持，请切换到 MediaPipe',
      'videoUnavailable': '当前没有可保存的视频文件',
      'videoSaveSuccess': '视频已保存到系统相册',
      'videoSaveFailed': '视频保存失败，请稍后重试',
      'videoComposeFailed': '视频处理失败，请重试保存',
      'videoComposeOutputMissing': '视频处理成功但输出文件不存在，请重试',
      'videoSavePermissionDenied': '未获得保存到相册的权限，请先在系统设置中开启',
      'videoSaveNotEnoughSpace': '本地存储空间不足，无法保存视频',
      'videoSaveNotSupportedFormat': '视频格式不受支持，无法保存',
      'videoSaveUnexpected': '保存时发生未知错误，请稍后重试',
      'videoPlaceholder': 'mock://video/latest.mp4',
    },
    'en': {
      'appTitle': '葡萄跳绳',
      'sessionTitle': 'Home',
      'bestRecord': 'Best Record',
      'bestRecordEmpty': 'No training records yet',
      'bestRecordDetail': 'Highest jump count in history',
      'duration': 'Jump Duration',
      'durationAdjustHint':
          'Adjust by 30 seconds each tap, starting from 10 seconds',
      'seconds': 'sec',
      'jumpCount': 'Jumps',
      'remaining': 'Remaining',
      'start': 'Start',
      'pause': 'Pause',
      'resume': 'Resume',
      'finish': 'Finish & Save',
      'simulateJump': 'Manual +1',
      'history': 'History',
      'emptyHistory': 'No records yet',
      'statusIdle': 'Idle',
      'statusRunning': 'Running',
      'statusPaused': 'Paused',
      'statusCompleted': 'Completed',
      'statusError': 'Error',
      'recording': 'Recording',
      'notRecording': 'Not recording',
      'parentPhoto': 'Parent Camera',
      'parentPhotoHint': 'Open the parent camera page to start recording',
      'parentCameraGuide': 'Keep the jumper inside the frame',
      'parentCameraStart': 'Start Recording',
      'parentCameraExit': 'Exit',
      'parentCameraReadyHint':
          'Keep the full body in frame. Recording starts after a 3-second countdown',
      'parentCameraCountdownHint':
          'Recording will start automatically after the countdown',
      'parentCameraRecordingHint':
          'Recording the jump rope session. Exit will stop recording',
      'parentCameraLoading': 'Starting camera',
      'parentCameraInitializing': 'Preparing camera',
      'parentCameraRecording': 'Recording',
      'parentCameraProcessingVideo': 'Preparing local video',
      'parentCameraAnalysisPreparing': 'Preparing pose model',
      'parentCameraAnalysisSearching': 'Keep one full body inside the frame',
      'parentCameraAnalysisTracking': 'Counting jumps in real time',
      'parentCameraAnalysisLowConfidence':
          'Image quality is unstable. Counting paused',
      'parentCameraAnalysisMultiplePeople':
          'Multiple people detected. Counting paused',
      'parentCameraAnalysisPaused': 'Detection paused after an analyzer error',
      'parentCameraAnalysisCorrection': 'Refining the final jump count',
      'parentCameraAnalysisCompleted': 'Counting finished',
      'parentCameraAnalysisMetrics': 'Analysis',
      'parentCameraSummaryTitle': 'Session Summary',
      'parentCameraSummaryScore': 'Score',
      'parentCameraSummaryTime': 'Time',
      'parentCameraSummaryVideo': 'Video',
      'parentCameraSummaryPending': 'Pending',
      'parentCameraSummarySave': 'Save to library',
      'parentCameraSummarySaving': 'Saving to library',
      'parentCameraSummarySaved': 'Saved to library',
      'parentCameraSummaryRetry': 'Retry',
      'parentCameraOverlayStartJump': 'Start jumping',
      'parentCameraOverlayRhythmStable': 'Steady rhythm',
      'parentCameraOverlayRhythmFast': 'Rhythm speeding up',
      'parentCameraOverlayRhythmSlow': 'Rhythm slowing down',
      'cameraUnavailable': 'No available camera detected',
      'cameraPermissionDenied':
          'Camera permission was denied. Please enable it in settings',
      'cameraAccessRestricted': 'Camera access is restricted on this device',
      'cameraInitFailed': 'Failed to start the camera. Please try again later',
      'cameraRecordStartFailed': 'Failed to start recording. Please try again',
      'cameraRecordStopFailed':
          'Failed to stop recording, but you can still go back',
      'jumpCounterInitFailed':
          'Failed to initialize jump counting. Please try again later',
      'jumpCounterRuntimeFailed':
          'Jump counting stopped after an analyzer error',
      'jumpCounterBackendUnsupported':
          'Selected pose backend is not supported on this build',
      'videoUnavailable': 'No video is available to save',
      'videoSaveSuccess': 'Video saved to photo library',
      'videoSaveFailed': 'Failed to save the video. Please try again later',
      'videoComposeFailed': 'Video processing failed. Please try saving again',
      'videoComposeOutputMissing':
          'Video processing succeeded but output file is missing',
      'videoSavePermissionDenied':
          'Photo library permission is required before saving the video',
      'videoSaveNotEnoughSpace':
          'Insufficient storage space. Unable to save the video',
      'videoSaveNotSupportedFormat':
          'Video format is not supported and cannot be saved',
      'videoSaveUnexpected':
          'An unexpected error occurred while saving the video',
      'videoPlaceholder': 'mock://video/latest.mp4',
    },
  };

  String _text(String key) {
    final languageCode = _localizedValues.containsKey(locale.languageCode)
        ? locale.languageCode
        : 'en';
    return _localizedValues[languageCode]?[key] ?? key;
  }

  String get appTitle => _text('appTitle');

  String get sessionTitle => _text('sessionTitle');

  String get bestRecord => _text('bestRecord');

  String get bestRecordEmpty => _text('bestRecordEmpty');

  String get bestRecordDetail => _text('bestRecordDetail');

  String get duration => _text('duration');

  String get durationAdjustHint => _text('durationAdjustHint');

  String get seconds => _text('seconds');

  String get jumpCount => _text('jumpCount');

  String get remaining => _text('remaining');

  String get start => _text('start');

  String get pause => _text('pause');

  String get resume => _text('resume');

  String get finish => _text('finish');

  String get simulateJump => _text('simulateJump');

  String get history => _text('history');

  String get emptyHistory => _text('emptyHistory');

  String get recording => _text('recording');

  String get notRecording => _text('notRecording');

  String get parentPhoto => _text('parentPhoto');

  String get parentPhotoHint => _text('parentPhotoHint');

  String get parentCameraGuide => _text('parentCameraGuide');

  String get parentCameraStart => _text('parentCameraStart');

  String get parentCameraExit => _text('parentCameraExit');

  String get parentCameraReadyHint => _text('parentCameraReadyHint');

  String get parentCameraCountdownHint => _text('parentCameraCountdownHint');

  String get parentCameraRecordingHint => _text('parentCameraRecordingHint');

  String get parentCameraLoading => _text('parentCameraLoading');

  String get parentCameraInitializing => _text('parentCameraInitializing');

  String get parentCameraRecording => _text('parentCameraRecording');

  String get parentCameraProcessingVideo =>
      _text('parentCameraProcessingVideo');

  String get parentCameraAnalysisPreparing =>
      _text('parentCameraAnalysisPreparing');

  String get parentCameraAnalysisSearching =>
      _text('parentCameraAnalysisSearching');

  String get parentCameraAnalysisTracking =>
      _text('parentCameraAnalysisTracking');

  String get parentCameraAnalysisLowConfidence =>
      _text('parentCameraAnalysisLowConfidence');

  String get parentCameraAnalysisMultiplePeople =>
      _text('parentCameraAnalysisMultiplePeople');

  String get parentCameraAnalysisPaused => _text('parentCameraAnalysisPaused');

  String get parentCameraAnalysisCorrection =>
      _text('parentCameraAnalysisCorrection');

  String get parentCameraAnalysisCompleted =>
      _text('parentCameraAnalysisCompleted');

  String get parentCameraAnalysisMetrics =>
      _text('parentCameraAnalysisMetrics');

  String get parentCameraSummaryTitle => _text('parentCameraSummaryTitle');

  String get parentCameraSummaryScore => _text('parentCameraSummaryScore');

  String get parentCameraSummaryTime => _text('parentCameraSummaryTime');

  String get parentCameraSummaryVideo => _text('parentCameraSummaryVideo');

  String get parentCameraSummaryPending => _text('parentCameraSummaryPending');

  String get parentCameraSummarySave => _text('parentCameraSummarySave');

  String get parentCameraSummarySaving => _text('parentCameraSummarySaving');

  String get parentCameraSummarySaved => _text('parentCameraSummarySaved');

  String get parentCameraSummaryRetry => _text('parentCameraSummaryRetry');

  String get parentCameraOverlayStartJump =>
      _text('parentCameraOverlayStartJump');

  String get parentCameraOverlayRhythmStable =>
      _text('parentCameraOverlayRhythmStable');

  String get parentCameraOverlayRhythmFast =>
      _text('parentCameraOverlayRhythmFast');

  String get parentCameraOverlayRhythmSlow =>
      _text('parentCameraOverlayRhythmSlow');

  String get cameraUnavailable => _text('cameraUnavailable');

  String get cameraPermissionDenied => _text('cameraPermissionDenied');

  String get cameraAccessRestricted => _text('cameraAccessRestricted');

  String get cameraInitFailed => _text('cameraInitFailed');

  String get cameraRecordStartFailed => _text('cameraRecordStartFailed');

  String get cameraRecordStopFailed => _text('cameraRecordStopFailed');

  String get jumpCounterInitFailed => _text('jumpCounterInitFailed');

  String get jumpCounterRuntimeFailed => _text('jumpCounterRuntimeFailed');

  String get jumpCounterBackendUnsupported =>
      _text('jumpCounterBackendUnsupported');

  String get videoUnavailable => _text('videoUnavailable');

  String get videoSaveSuccess => _text('videoSaveSuccess');

  String get videoSaveFailed => _text('videoSaveFailed');

  String get videoComposeFailed => _text('videoComposeFailed');

  String get videoComposeOutputMissing => _text('videoComposeOutputMissing');

  String get videoSavePermissionDenied => _text('videoSavePermissionDenied');

  String get videoSaveNotEnoughSpace => _text('videoSaveNotEnoughSpace');

  String get videoSaveNotSupportedFormat =>
      _text('videoSaveNotSupportedFormat');

  String get videoSaveUnexpected => _text('videoSaveUnexpected');

  String get videoPlaceholder => _text('videoPlaceholder');

  String cameraMessage(String key) {
    switch (key) {
      case 'cameraUnavailable':
        return cameraUnavailable;
      case 'cameraPermissionDenied':
        return cameraPermissionDenied;
      case 'cameraAccessRestricted':
        return cameraAccessRestricted;
      case 'cameraRecordStartFailed':
        return cameraRecordStartFailed;
      case 'cameraRecordStopFailed':
        return cameraRecordStopFailed;
      case 'jumpCounterInitFailed':
        return jumpCounterInitFailed;
      case 'jumpCounterRuntimeFailed':
        return jumpCounterRuntimeFailed;
      case 'jumpCounterBackendUnsupported':
        return jumpCounterBackendUnsupported;
      case 'cameraInitFailed':
      default:
        return cameraInitFailed;
    }
  }

  String parentCameraErrorMessage(String key) {
    switch (key) {
      case 'videoUnavailable':
        return videoUnavailable;
      case 'videoComposeFailed':
        return videoComposeFailed;
      case 'videoComposeOutputMissing':
        return videoComposeOutputMissing;
      case 'videoSavePermissionDenied':
        return videoSavePermissionDenied;
      case 'videoSaveNotEnoughSpace':
        return videoSaveNotEnoughSpace;
      case 'videoSaveNotSupportedFormat':
        return videoSaveNotSupportedFormat;
      case 'videoSaveUnexpected':
        return videoSaveUnexpected;
      case 'videoSaveFailed':
        return videoSaveFailed;
      default:
        return cameraMessage(key);
    }
  }

  String parentCameraFeedback(String key) {
    switch (key) {
      case 'videoUnavailable':
        return videoUnavailable;
      case 'videoSaveSuccess':
        return videoSaveSuccess;
      case 'videoSavePermissionDenied':
        return videoSavePermissionDenied;
      case 'videoSaveNotEnoughSpace':
        return videoSaveNotEnoughSpace;
      case 'videoSaveNotSupportedFormat':
        return videoSaveNotSupportedFormat;
      case 'videoSaveUnexpected':
        return videoSaveUnexpected;
      case 'videoComposeFailed':
        return videoComposeFailed;
      case 'videoComposeOutputMissing':
        return videoComposeOutputMissing;
      case 'videoSaveFailed':
      default:
        return videoSaveFailed;
    }
  }

  String parentCameraOverlayText(String key) {
    switch (key) {
      case 'parentCameraOverlayStartJump':
        return parentCameraOverlayStartJump;
      case 'parentCameraOverlayRhythmStable':
        return parentCameraOverlayRhythmStable;
      case 'parentCameraOverlayRhythmFast':
        return parentCameraOverlayRhythmFast;
      case 'parentCameraOverlayRhythmSlow':
        return parentCameraOverlayRhythmSlow;
      default:
        return key;
    }
  }

  String parentCameraAnalysisStatus(String key) {
    switch (key) {
      case 'parentCameraAnalysisPreparing':
        return parentCameraAnalysisPreparing;
      case 'parentCameraAnalysisTracking':
        return parentCameraAnalysisTracking;
      case 'parentCameraAnalysisLowConfidence':
        return parentCameraAnalysisLowConfidence;
      case 'parentCameraAnalysisMultiplePeople':
        return parentCameraAnalysisMultiplePeople;
      case 'parentCameraAnalysisPaused':
        return parentCameraAnalysisPaused;
      case 'parentCameraAnalysisCorrection':
        return parentCameraAnalysisCorrection;
      case 'parentCameraAnalysisCompleted':
        return parentCameraAnalysisCompleted;
      case 'parentCameraAnalysisSearching':
      default:
        return parentCameraAnalysisSearching;
    }
  }

  String sessionStatusLabel(String status) {
    switch (status) {
      case 'running':
        return _text('statusRunning');
      case 'paused':
        return _text('statusPaused');
      case 'completed':
        return _text('statusCompleted');
      case 'error':
        return _text('statusError');
      case 'idle':
      default:
        return _text('statusIdle');
    }
  }
}

class _AppI18nDelegate extends LocalizationsDelegate<AppI18n> {
  const _AppI18nDelegate();

  @override
  bool isSupported(Locale locale) {
    return AppI18n.supportedLocales
        .map((item) => item.languageCode)
        .contains(locale.languageCode);
  }

  @override
  Future<AppI18n> load(Locale locale) async {
    return AppI18n(locale);
  }

  @override
  bool shouldReload(covariant LocalizationsDelegate<AppI18n> old) {
    return false;
  }
}

extension AppI18nX on BuildContext {
  AppI18n get i18n => AppI18n.of(this);
}
