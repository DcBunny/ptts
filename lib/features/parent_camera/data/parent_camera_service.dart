import 'dart:io';

import 'package:camera/camera.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:tiaosheng/features/parent_camera/data/parent_camera_frame.dart';

abstract class ParentCameraService {
  Future<ParentCameraDevice> createDefaultDevice();
}

typedef ParentCameraFrameListener = void Function(ParentCameraFrame frame);

abstract class ParentCameraDevice {
  bool get isInitialized;

  bool get isRecording;

  int get sensorOrientation;

  Size? get previewSize;

  Widget buildPreview();

  Future<void> startRecording({ParentCameraFrameListener? onFrameAvailable});

  Future<String?> stopRecording();

  Future<void> dispose();
}

class CameraParentCameraService implements ParentCameraService {
  @override
  Future<ParentCameraDevice> createDefaultDevice() async {
    CameraController? controller;

    try {
      final cameras = await availableCameras();
      if (cameras.isEmpty) {
        throw const ParentCameraException('cameraUnavailable');
      }

      controller = CameraController(
        _pickPreferredCamera(cameras),
        ResolutionPreset.high,
        enableAudio: false,
        imageFormatGroup: Platform.isIOS
            ? ImageFormatGroup.bgra8888
            : ImageFormatGroup.nv21,
      );

      await controller.initialize();
      final minZoomLevel = await controller.getMinZoomLevel();
      await controller.setZoomLevel(minZoomLevel);
      await controller.lockCaptureOrientation(DeviceOrientation.portraitUp);
      return CameraPluginParentCameraDevice(controller);
    } on CameraException catch (error) {
      await controller?.dispose();
      throw ParentCameraException.fromCode(error.code);
    } catch (_) {
      await controller?.dispose();
      rethrow;
    }
  }

  CameraDescription _pickPreferredCamera(List<CameraDescription> cameras) {
    for (final camera in cameras) {
      if (camera.lensDirection == CameraLensDirection.back) {
        return camera;
      }
    }
    return cameras.first;
  }
}

class CameraPluginParentCameraDevice implements ParentCameraDevice {
  CameraPluginParentCameraDevice(this._controller);

  final CameraController _controller;

  @override
  bool get isInitialized => _controller.value.isInitialized;

  @override
  bool get isRecording => _controller.value.isRecordingVideo;

  @override
  int get sensorOrientation => _controller.description.sensorOrientation;

  @override
  Size? get previewSize => _controller.value.previewSize;

  @override
  Widget buildPreview() {
    return CameraPreview(_controller);
  }

  @override
  Future<void> startRecording({ParentCameraFrameListener? onFrameAvailable}) {
    return _controller.startVideoRecording(
      onAvailable: onFrameAvailable == null
          ? null
          : (image) {
              final timestampMs = DateTime.now().millisecondsSinceEpoch;
              onFrameAvailable(
                ParentCameraFrame.fromCameraImage(
                  image: image,
                  rotationDegrees: sensorOrientation,
                  timestampMs: timestampMs,
                ),
              );
            },
    );
  }

  @override
  Future<String?> stopRecording() async {
    if (!_controller.value.isRecordingVideo) {
      return null;
    }
    final file = await _controller.stopVideoRecording();
    return file.path;
  }

  @override
  Future<void> dispose() {
    return _controller.dispose();
  }
}

class ParentCameraException implements Exception {
  const ParentCameraException(this.errorKey);

  final String errorKey;

  factory ParentCameraException.fromCode(String code) {
    switch (code) {
      case 'CameraAccessDenied':
      case 'CameraAccessDeniedWithoutPrompt':
        return const ParentCameraException('cameraPermissionDenied');
      case 'CameraAccessRestricted':
        return const ParentCameraException('cameraAccessRestricted');
      default:
        return const ParentCameraException('cameraInitFailed');
    }
  }
}
