import 'dart:typed_data';

import 'package:camera/camera.dart';

class ParentCameraFrame {
  const ParentCameraFrame({
    required this.width,
    required this.height,
    required this.rotationDegrees,
    required this.timestampMs,
    required this.format,
    required this.planes,
  });

  factory ParentCameraFrame.fromCameraImage({
    required CameraImage image,
    required int rotationDegrees,
    required int timestampMs,
  }) {
    return ParentCameraFrame(
      width: image.width,
      height: image.height,
      rotationDegrees: rotationDegrees,
      timestampMs: timestampMs,
      format: _resolveFormatName(image.format.group),
      planes: image.planes
          .map(
            (plane) => ParentCameraFramePlane(
              bytes: plane.bytes,
              bytesPerRow: plane.bytesPerRow,
              bytesPerPixel: plane.bytesPerPixel,
            ),
          )
          .toList(growable: false),
    );
  }

  final int width;
  final int height;
  final int rotationDegrees;
  final int timestampMs;
  final String format;
  final List<ParentCameraFramePlane> planes;

  ParentCameraFrame copyWith({
    int? width,
    int? height,
    int? rotationDegrees,
    int? timestampMs,
    String? format,
    List<ParentCameraFramePlane>? planes,
  }) {
    return ParentCameraFrame(
      width: width ?? this.width,
      height: height ?? this.height,
      rotationDegrees: rotationDegrees ?? this.rotationDegrees,
      timestampMs: timestampMs ?? this.timestampMs,
      format: format ?? this.format,
      planes: planes ?? this.planes,
    );
  }

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'width': width,
      'height': height,
      'rotationDegrees': rotationDegrees,
      'timestampMs': timestampMs,
      'format': format,
      'planes': planes.map((plane) => plane.toMap()).toList(growable: false),
    };
  }

  static String _resolveFormatName(ImageFormatGroup format) {
    switch (format) {
      case ImageFormatGroup.bgra8888:
        return 'bgra8888';
      case ImageFormatGroup.nv21:
        return 'nv21';
      case ImageFormatGroup.yuv420:
        return 'yuv420';
      case ImageFormatGroup.jpeg:
        return 'jpeg';
      case ImageFormatGroup.unknown:
        return 'unknown';
    }
  }
}

class ParentCameraFramePlane {
  const ParentCameraFramePlane({
    required this.bytes,
    required this.bytesPerRow,
    required this.bytesPerPixel,
  });

  final Uint8List bytes;
  final int bytesPerRow;
  final int? bytesPerPixel;

  Map<String, Object?> toMap() {
    return <String, Object?>{
      'bytes': bytes,
      'bytesPerRow': bytesPerRow,
      'bytesPerPixel': bytesPerPixel,
    };
  }
}
