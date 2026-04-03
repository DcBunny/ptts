import 'package:flutter/services.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_models.dart';
import 'package:tiaosheng/features/parent_camera/data/parent_camera_frame.dart';

enum JumpRopePoseEstimatorBackend { mediaPipePose, moveNet }

class JumpRopePoseAnalyzerConfig {
  const JumpRopePoseAnalyzerConfig({
    this.backend = JumpRopePoseEstimatorBackend.mediaPipePose,
  });

  final JumpRopePoseEstimatorBackend backend;

  Map<String, Object?> toMap() {
    return <String, Object?>{'backend': backend.name};
  }
}

abstract class JumpRopePoseAnalyzer {
  Future<void> initialize();

  Future<void> startSession();

  Future<JumpRopePoseFrameResult> analyzeFrame(ParentCameraFrame frame);

  Future<void> stopSession();

  Future<void> dispose();
}

class NativeJumpRopePoseAnalyzer implements JumpRopePoseAnalyzer {
  NativeJumpRopePoseAnalyzer({
    this.config = const JumpRopePoseAnalyzerConfig(),
  });

  static const _channel = MethodChannel('tiaosheng/jump_rope_pose_analyzer');

  final JumpRopePoseAnalyzerConfig config;
  var _isInitialized = false;
  var _isSessionStarted = false;

  @override
  Future<void> dispose() async {
    try {
      await _channel.invokeMethod<void>('dispose');
    } on PlatformException catch (error) {
      throw JumpRopePoseAnalyzerException.fromPlatformException(error);
    } finally {
      _isInitialized = false;
      _isSessionStarted = false;
    }
  }

  @override
  Future<void> initialize() async {
    if (_isInitialized) {
      return;
    }
    if (config.backend != JumpRopePoseEstimatorBackend.mediaPipePose) {
      throw const JumpRopePoseAnalyzerException(
        'jumpCounterBackendUnsupported',
      );
    }

    try {
      await _channel.invokeMethod<void>('initialize', config.toMap());
      _isInitialized = true;
    } on PlatformException catch (error) {
      throw JumpRopePoseAnalyzerException.fromPlatformException(error);
    }
  }

  @override
  Future<JumpRopePoseFrameResult> analyzeFrame(ParentCameraFrame frame) async {
    if (!_isInitialized) {
      throw const JumpRopePoseAnalyzerException('jumpCounterInitFailed');
    }
    if (!_isSessionStarted) {
      throw const JumpRopePoseAnalyzerException('jumpCounterRuntimeFailed');
    }

    try {
      final rawResult = await _channel.invokeMethod<Map<Object?, Object?>>(
        'analyzeFrame',
        frame.toMap(),
      );
      if (rawResult == null) {
        throw const JumpRopePoseAnalyzerException('jumpCounterRuntimeFailed');
      }
      return JumpRopePoseFrameResult.fromMap(rawResult);
    } on PlatformException catch (error) {
      throw JumpRopePoseAnalyzerException.fromPlatformException(error);
    }
  }

  @override
  Future<void> startSession() async {
    if (!_isInitialized) {
      throw const JumpRopePoseAnalyzerException('jumpCounterInitFailed');
    }
    try {
      await _channel.invokeMethod<void>('startSession');
      _isSessionStarted = true;
    } on PlatformException catch (error) {
      throw JumpRopePoseAnalyzerException.fromPlatformException(error);
    }
  }

  @override
  Future<void> stopSession() async {
    if (!_isSessionStarted) {
      return;
    }
    try {
      await _channel.invokeMethod<void>('stopSession');
    } on PlatformException catch (error) {
      throw JumpRopePoseAnalyzerException.fromPlatformException(error);
    } finally {
      _isSessionStarted = false;
    }
  }
}

class JumpRopePoseAnalyzerException implements Exception {
  const JumpRopePoseAnalyzerException(this.errorKey, {this.detail});

  factory JumpRopePoseAnalyzerException.fromPlatformException(
    PlatformException error,
  ) {
    return JumpRopePoseAnalyzerException(
      error.code.isEmpty ? 'jumpCounterRuntimeFailed' : error.code,
      detail: error.message ?? error.details?.toString(),
    );
  }

  final String errorKey;
  final String? detail;
}
