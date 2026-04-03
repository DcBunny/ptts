import 'package:flutter_test/flutter_test.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_counter_engine.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_models.dart';

void main() {
  test('稳定单人全身入镜时可以识别一次完整起跳', () {
    final engine = JumpRopeCounterEngine();

    for (final sample in _buildWarmupSamples()) {
      engine.ingest(sample);
    }
    for (final sample in _buildJumpSamples(startMs: 320)) {
      engine.ingest(sample);
    }

    final result = engine.finalizeSession();
    expect(result.jumpCount, 1);
    expect(result.jumpEventMillis, hasLength(1));
    expect(result.analysisFps, greaterThan(10));
  });

  test('多人入镜时宁可漏计，也不会继续增加错误计数', () {
    final engine = JumpRopeCounterEngine();

    for (final sample in _buildWarmupSamples()) {
      engine.ingest(sample);
    }
    for (final sample in _buildJumpSamples(startMs: 320)) {
      engine.ingest(sample);
    }
    for (final sample in _buildMultiplePoseSamples(startMs: 1000)) {
      engine.ingest(sample);
    }

    final result = engine.finalizeSession();
    expect(result.jumpCount, 1);
    expect(result.statusKey, 'parentCameraAnalysisMultiplePeople');
  });

  test('结束时会调用时序增强器并使用增强后的结果', () {
    final enhancer = _FixedTemporalEnhancer(overrideJumpCount: 6);
    final engine = JumpRopeCounterEngine(temporalEnhancer: enhancer);

    for (final sample in _buildWarmupSamples()) {
      engine.ingest(sample);
    }
    for (final sample in _buildJumpSamples(startMs: 320)) {
      engine.ingest(sample);
    }

    final result = engine.finalizeSession();
    expect(enhancer.callCount, 1);
    expect(result.jumpCount, 6);
    expect(result.correctionApplied, isTrue);
  });
}

List<JumpRopePoseFrameResult> _buildWarmupSamples() {
  return List<JumpRopePoseFrameResult>.generate(4, (index) {
    return _singlePoseSample(timestampMs: index * 80, hipLift: 0, ankleLift: 0);
  });
}

List<JumpRopePoseFrameResult> _buildJumpSamples({required int startMs}) {
  const hipLifts = <double>[0, 0.018, 0.036, 0.05, 0.038, 0.018, 0, 0, 0];
  const ankleLifts = <double>[0, 0.012, 0.028, 0.04, 0.028, 0.012, 0, 0, 0];
  return List<JumpRopePoseFrameResult>.generate(hipLifts.length, (index) {
    return _singlePoseSample(
      timestampMs: startMs + (index * 80),
      hipLift: hipLifts[index],
      ankleLift: ankleLifts[index],
    );
  });
}

List<JumpRopePoseFrameResult> _buildMultiplePoseSamples({
  required int startMs,
}) {
  return List<JumpRopePoseFrameResult>.generate(4, (index) {
    return JumpRopePoseFrameResult(
      timestampMs: startMs + (index * 80),
      analysisLatencyMs: 45,
      poses: <JumpRopePose>[
        _buildPose(hipLift: 0.025, ankleLift: 0.018),
        _buildPose(hipLift: 0.01, ankleLift: 0.006),
      ],
    );
  });
}

JumpRopePoseFrameResult _singlePoseSample({
  required int timestampMs,
  required double hipLift,
  required double ankleLift,
}) {
  return JumpRopePoseFrameResult(
    timestampMs: timestampMs,
    analysisLatencyMs: 42,
    poses: <JumpRopePose>[_buildPose(hipLift: hipLift, ankleLift: ankleLift)],
  );
}

JumpRopePose _buildPose({required double hipLift, required double ankleLift}) {
  const shoulderY = 0.35;
  final hipY = 0.65 - hipLift;
  final ankleY = 0.82 - ankleLift;
  final landmarks = List<JumpRopePoseLandmark>.generate(
    JumpRopeLandmarkType.values.length,
    (_) => const JumpRopePoseLandmark(
      x: 0.5,
      y: 0.5,
      z: 0,
      visibility: 0.98,
      presence: 0.98,
    ),
  );

  landmarks[JumpRopeLandmarkType.leftShoulder.index] =
      const JumpRopePoseLandmark(
        x: 0.42,
        y: shoulderY,
        z: 0,
        visibility: 0.98,
        presence: 0.98,
      );
  landmarks[JumpRopeLandmarkType.rightShoulder.index] =
      const JumpRopePoseLandmark(
        x: 0.58,
        y: shoulderY,
        z: 0,
        visibility: 0.98,
        presence: 0.98,
      );
  landmarks[JumpRopeLandmarkType.leftHip.index] = JumpRopePoseLandmark(
    x: 0.46,
    y: hipY,
    z: 0,
    visibility: 0.98,
    presence: 0.98,
  );
  landmarks[JumpRopeLandmarkType.rightHip.index] = JumpRopePoseLandmark(
    x: 0.54,
    y: hipY,
    z: 0,
    visibility: 0.98,
    presence: 0.98,
  );
  landmarks[JumpRopeLandmarkType.leftAnkle.index] = JumpRopePoseLandmark(
    x: 0.47,
    y: ankleY,
    z: 0,
    visibility: 0.98,
    presence: 0.98,
  );
  landmarks[JumpRopeLandmarkType.rightAnkle.index] = JumpRopePoseLandmark(
    x: 0.53,
    y: ankleY,
    z: 0,
    visibility: 0.98,
    presence: 0.98,
  );
  landmarks[JumpRopeLandmarkType.leftHeel.index] = JumpRopePoseLandmark(
    x: 0.47,
    y: ankleY,
    z: 0,
    visibility: 0.98,
    presence: 0.98,
  );
  landmarks[JumpRopeLandmarkType.rightHeel.index] = JumpRopePoseLandmark(
    x: 0.53,
    y: ankleY,
    z: 0,
    visibility: 0.98,
    presence: 0.98,
  );
  return JumpRopePose(landmarks: landmarks);
}

class _FixedTemporalEnhancer implements JumpRopeTemporalEnhancer {
  _FixedTemporalEnhancer({required this.overrideJumpCount});

  final int overrideJumpCount;
  int callCount = 0;

  @override
  JumpRopeCounterSessionResult enhance({
    required JumpRopeTemporalEnhancementContext context,
  }) {
    callCount += 1;
    final selectedResult = context.selectedResult;
    return JumpRopeCounterSessionResult(
      jumpCount: overrideJumpCount,
      jumpEventMillis: selectedResult.jumpEventMillis,
      analysisFps: selectedResult.analysisFps,
      analysisLatencyMs: selectedResult.analysisLatencyMs,
      statusKey: selectedResult.statusKey,
      correctionApplied: true,
    );
  }
}
