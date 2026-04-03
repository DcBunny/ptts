enum JumpRopeLandmarkType {
  nose,
  leftEyeInner,
  leftEye,
  leftEyeOuter,
  rightEyeInner,
  rightEye,
  rightEyeOuter,
  leftEar,
  rightEar,
  mouthLeft,
  mouthRight,
  leftShoulder,
  rightShoulder,
  leftElbow,
  rightElbow,
  leftWrist,
  rightWrist,
  leftPinky,
  rightPinky,
  leftIndex,
  rightIndex,
  leftThumb,
  rightThumb,
  leftHip,
  rightHip,
  leftKnee,
  rightKnee,
  leftAnkle,
  rightAnkle,
  leftHeel,
  rightHeel,
  leftFootIndex,
  rightFootIndex,
}

class JumpRopePoseLandmark {
  const JumpRopePoseLandmark({
    required this.x,
    required this.y,
    required this.z,
    required this.visibility,
    required this.presence,
  });

  factory JumpRopePoseLandmark.fromMap(Map<Object?, Object?> map) {
    return JumpRopePoseLandmark(
      x: (map['x'] as num?)?.toDouble() ?? 0,
      y: (map['y'] as num?)?.toDouble() ?? 0,
      z: (map['z'] as num?)?.toDouble() ?? 0,
      visibility: (map['visibility'] as num?)?.toDouble() ?? 1,
      presence: (map['presence'] as num?)?.toDouble() ?? 1,
    );
  }

  final double x;
  final double y;
  final double z;
  final double visibility;
  final double presence;

  double get confidence => (visibility + presence) / 2;
}

class JumpRopePose {
  const JumpRopePose({required this.landmarks});

  factory JumpRopePose.fromMap(Map<Object?, Object?> map) {
    final rawLandmarks =
        (map['landmarks'] as List<Object?>? ?? const <Object?>[]);
    return JumpRopePose(
      landmarks: rawLandmarks
          .map(
            (item) =>
                JumpRopePoseLandmark.fromMap(item as Map<Object?, Object?>),
          )
          .toList(growable: false),
    );
  }

  final List<JumpRopePoseLandmark> landmarks;

  JumpRopePoseLandmark? landmark(JumpRopeLandmarkType type) {
    final index = type.index;
    if (index < 0 || index >= landmarks.length) {
      return null;
    }
    return landmarks[index];
  }
}

class JumpRopePoseFrameResult {
  const JumpRopePoseFrameResult({
    required this.timestampMs,
    required this.analysisLatencyMs,
    required this.poses,
  });

  factory JumpRopePoseFrameResult.fromMap(Map<Object?, Object?> map) {
    final rawPoses = (map['poses'] as List<Object?>? ?? const <Object?>[]);
    return JumpRopePoseFrameResult(
      timestampMs: (map['timestampMs'] as num?)?.toInt() ?? 0,
      analysisLatencyMs: (map['analysisLatencyMs'] as num?)?.toInt() ?? 0,
      poses: rawPoses
          .map((item) => JumpRopePose.fromMap(item as Map<Object?, Object?>))
          .toList(growable: false),
    );
  }

  final int timestampMs;
  final int analysisLatencyMs;
  final List<JumpRopePose> poses;
}
