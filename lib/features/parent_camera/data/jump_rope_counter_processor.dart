import 'dart:math' as math;

import 'package:tiaosheng/features/parent_camera/data/jump_rope_counter_models.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_models.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_rule_state_machine.dart';

class JumpRopeCounterProcessor {
  JumpRopeCounterProcessor.online()
    : _signalPipeline = _JumpRopeSignalPipeline(
        config: const JumpRopeCounterConfig.online(),
      ),
      _stateMachine = JumpRopeRuleStateMachine(
        config: const JumpRopeCounterConfig.online(),
      );

  JumpRopeCounterProcessor.offline()
    : _signalPipeline = _JumpRopeSignalPipeline(
        config: const JumpRopeCounterConfig.offline(),
      ),
      _stateMachine = JumpRopeRuleStateMachine(
        config: const JumpRopeCounterConfig.offline(),
      );

  final _JumpRopeSignalPipeline _signalPipeline;
  final JumpRopeRuleStateMachine _stateMachine;

  int? _lastTimestampMs;
  int _latencyAccumulatorMs = 0;
  int _latencySampleCount = 0;
  double _smoothedFps = 0;

  void reset() {
    _signalPipeline.reset();
    _stateMachine.reset();
    _lastTimestampMs = null;
    _latencyAccumulatorMs = 0;
    _latencySampleCount = 0;
    _smoothedFps = 0;
  }

  JumpRopeCounterUpdate consume(JumpRopePoseFrameResult sample) {
    _updateFps(sample.timestampMs);
    _latencyAccumulatorMs += sample.analysisLatencyMs;
    _latencySampleCount += 1;

    final signalUpdate = _signalPipeline.consume(sample);
    final stateMachineUpdate = _stateMachine.consume(signalUpdate);
    return JumpRopeCounterUpdate(
      jumpCount: stateMachineUpdate.jumpCount,
      analysisFps: _smoothedFps,
      analysisLatencyMs: sample.analysisLatencyMs,
      statusKey: signalUpdate.statusKey,
      newJumpEventMillis: stateMachineUpdate.newJumpEventMillis,
    );
  }

  JumpRopeCounterSessionResult buildResult({required bool correctionApplied}) {
    return JumpRopeCounterSessionResult(
      jumpCount: _stateMachine.jumpCount,
      jumpEventMillis: List<int>.unmodifiable(_stateMachine.jumpEventMillis),
      analysisFps: _smoothedFps,
      analysisLatencyMs: _averageLatencyMs(),
      statusKey: _signalPipeline.statusKey,
      correctionApplied: correctionApplied,
    );
  }

  void _updateFps(int timestampMs) {
    final lastTimestampMs = _lastTimestampMs;
    _lastTimestampMs = timestampMs;
    if (lastTimestampMs == null) {
      return;
    }

    final deltaMs = timestampMs - lastTimestampMs;
    if (deltaMs <= 0) {
      return;
    }

    final instantFps = 1000 / deltaMs;
    _smoothedFps = _smoothedFps == 0
        ? instantFps
        : (_smoothedFps * 0.7) + (instantFps * 0.3);
  }

  int _averageLatencyMs() {
    if (_latencySampleCount == 0) {
      return 0;
    }
    return (_latencyAccumulatorMs / _latencySampleCount).round();
  }
}

class _JumpRopeSignalPipeline {
  _JumpRopeSignalPipeline({required this.config});

  final JumpRopeCounterConfig config;

  int _stableFrameCount = 0;
  double? _smoothedHipY;
  double? _smoothedAnkleY;
  double? _baselineHipY;
  double? _baselineAnkleY;
  String _statusKey = 'parentCameraAnalysisSearching';

  String get statusKey => _statusKey;

  void reset() {
    _stableFrameCount = 0;
    _smoothedHipY = null;
    _smoothedAnkleY = null;
    _baselineHipY = null;
    _baselineAnkleY = null;
    _statusKey = 'parentCameraAnalysisSearching';
  }

  JumpRopeSignalUpdate consume(JumpRopePoseFrameResult sample) {
    final frameData = _selectReliableFrame(sample);
    if (frameData == null) {
      _stableFrameCount = 0;
      return JumpRopeSignalUpdate.unreliable(
        timestampMs: sample.timestampMs,
        statusKey: _statusKey,
      );
    }

    _statusKey = 'parentCameraAnalysisTracking';
    _stableFrameCount += 1;
    _smoothSignals(frameData);
    _updateBaseline();

    return JumpRopeSignalUpdate.reliable(
      timestampMs: sample.timestampMs,
      statusKey: _statusKey,
      signal: _resolveJumpSignal(),
      canCount: _stableFrameCount >= config.minStableFrames,
    );
  }

  _FrameData? _selectReliableFrame(JumpRopePoseFrameResult sample) {
    if (sample.poses.isEmpty) {
      _statusKey = 'parentCameraAnalysisSearching';
      return null;
    }
    if (sample.poses.length > 1) {
      _statusKey = 'parentCameraAnalysisMultiplePeople';
      return null;
    }

    final pose = sample.poses.first;
    final leftShoulder = pose.landmark(JumpRopeLandmarkType.leftShoulder);
    final rightShoulder = pose.landmark(JumpRopeLandmarkType.rightShoulder);
    final leftHip = pose.landmark(JumpRopeLandmarkType.leftHip);
    final rightHip = pose.landmark(JumpRopeLandmarkType.rightHip);
    final leftAnkle = pose.landmark(JumpRopeLandmarkType.leftAnkle);
    final rightAnkle = pose.landmark(JumpRopeLandmarkType.rightAnkle);
    final leftHeel = pose.landmark(JumpRopeLandmarkType.leftHeel) ?? leftAnkle;
    final rightHeel =
        pose.landmark(JumpRopeLandmarkType.rightHeel) ?? rightAnkle;

    final requiredLandmarks = <JumpRopePoseLandmark?>[
      leftShoulder,
      rightShoulder,
      leftHip,
      rightHip,
      leftAnkle,
      rightAnkle,
      leftHeel,
      rightHeel,
    ];
    if (requiredLandmarks.any((landmark) => landmark == null)) {
      _statusKey = 'parentCameraAnalysisLowConfidence';
      return null;
    }

    final confidences = requiredLandmarks
        .map((landmark) => landmark!.confidence)
        .toList(growable: false);
    final averageConfidence =
        confidences.reduce((left, right) => left + right) / confidences.length;
    if (averageConfidence < config.minAverageConfidence) {
      _statusKey = 'parentCameraAnalysisLowConfidence';
      return null;
    }

    final hipY = (((leftHip!.y + rightHip!.y) / 2).clamp(0, 1)).toDouble();
    final ankleY = (((leftHeel!.y + rightHeel!.y) / 2).clamp(0, 1)).toDouble();
    return _FrameData(hipY: hipY, ankleY: ankleY);
  }

  void _smoothSignals(_FrameData frameData) {
    _smoothedHipY = _blend(
      _smoothedHipY,
      frameData.hipY,
      config.smoothingAlpha,
    );
    _smoothedAnkleY = _blend(
      _smoothedAnkleY,
      frameData.ankleY,
      config.smoothingAlpha,
    );
    _baselineHipY ??= _smoothedHipY;
    _baselineAnkleY ??= _smoothedAnkleY;
  }

  void _updateBaseline() {
    final hipY = _smoothedHipY;
    final ankleY = _smoothedAnkleY;
    if (hipY == null || ankleY == null) {
      return;
    }

    final signal = _resolveJumpSignal();
    if (signal > config.maxBaselineSignal) {
      return;
    }

    _baselineHipY = _blend(_baselineHipY, hipY, config.baselineAlpha);
    _baselineAnkleY = _blend(_baselineAnkleY, ankleY, config.baselineAlpha);
  }

  double _resolveJumpSignal() {
    final hipY = _smoothedHipY;
    final ankleY = _smoothedAnkleY;
    final baselineHipY = _baselineHipY;
    final baselineAnkleY = _baselineAnkleY;
    if (hipY == null ||
        ankleY == null ||
        baselineHipY == null ||
        baselineAnkleY == null) {
      return 0;
    }

    final bodyScale = math.max(
      baselineAnkleY - baselineHipY,
      config.minBodyScale,
    );
    final hipLift = math.max(baselineHipY - hipY, 0) / bodyScale;
    final ankleLift = math.max(baselineAnkleY - ankleY, 0) / bodyScale;
    return hipLift * config.hipWeight + ankleLift * config.ankleWeight;
  }

  double _blend(double? previous, double current, double alpha) {
    if (previous == null) {
      return current;
    }
    return (previous * (1 - alpha)) + (current * alpha);
  }
}

class _FrameData {
  const _FrameData({required this.hipY, required this.ankleY});

  final double hipY;
  final double ankleY;
}
