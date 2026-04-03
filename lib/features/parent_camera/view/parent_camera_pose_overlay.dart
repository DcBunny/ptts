import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_models.dart';

class ParentCameraPoseOverlay extends StatelessWidget {
  const ParentCameraPoseOverlay({
    super.key,
    required this.poses,
    required this.previewSize,
    required this.analysisStatusKey,
  });

  final List<JumpRopePose> poses;
  final Size? previewSize;
  final String analysisStatusKey;

  @override
  Widget build(BuildContext context) {
    if (poses.isEmpty) {
      return const SizedBox.shrink();
    }

    return IgnorePointer(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final overlayModels = _buildOverlayModels(
            viewportSize: constraints.biggest,
            previewSize: previewSize,
            poses: poses,
          );
          if (overlayModels.isEmpty) {
            return const SizedBox.shrink();
          }

          return CustomPaint(
            painter: _ParentCameraPoseOverlayPainter(
              overlays: overlayModels,
              palette: _PoseOverlayPalette.fromStatus(analysisStatusKey),
            ),
          );
        },
      ),
    );
  }
}

List<_PoseOverlayModel> _buildOverlayModels({
  required Size viewportSize,
  required Size? previewSize,
  required List<JumpRopePose> poses,
}) {
  final previewRect = _resolvePreviewRect(
    viewportSize: viewportSize,
    previewSize: previewSize,
  );
  final overlayModels = poses
      .map((pose) => _PoseOverlayModel.fromPose(pose, previewRect))
      .whereType<_PoseOverlayModel>()
      .toList(growable: false);
  overlayModels.sort(
    (left, right) => right.bounds.height.compareTo(left.bounds.height),
  );
  return overlayModels;
}

Rect _resolvePreviewRect({
  required Size viewportSize,
  required Size? previewSize,
}) {
  if (previewSize == null ||
      viewportSize.width <= 0 ||
      viewportSize.height <= 0) {
    return Offset.zero & viewportSize;
  }

  final rotatedPreviewWidth = previewSize.height;
  final rotatedPreviewHeight = previewSize.width;
  final scale = math.max(
    viewportSize.width / rotatedPreviewWidth,
    viewportSize.height / rotatedPreviewHeight,
  );
  final fittedWidth = rotatedPreviewWidth * scale;
  final fittedHeight = rotatedPreviewHeight * scale;
  final left = (viewportSize.width - fittedWidth) / 2;
  final top = (viewportSize.height - fittedHeight) / 2;
  return Rect.fromLTWH(left, top, fittedWidth, fittedHeight);
}

class _PoseOverlayModel {
  const _PoseOverlayModel({
    required this.points,
    required this.bounds,
    required this.averageConfidence,
  });

  final Map<JumpRopeLandmarkType, Offset> points;
  final Rect bounds;
  final double averageConfidence;

  static const _minimumRenderableConfidence = 0.2;
  static const _minimumBoundsConfidence = 0.3;

  static _PoseOverlayModel? fromPose(JumpRopePose pose, Rect previewRect) {
    final points = <JumpRopeLandmarkType, Offset>{};
    final reliableXs = <double>[];
    final reliableYs = <double>[];
    var confidenceSum = 0.0;
    var confidenceCount = 0;

    for (final landmarkType in JumpRopeLandmarkType.values) {
      final landmark = pose.landmark(landmarkType);
      if (landmark == null) {
        continue;
      }
      final confidence = landmark.confidence;
      if (confidence < _minimumRenderableConfidence) {
        continue;
      }

      final point = Offset(
        previewRect.left + landmark.x.clamp(0.0, 1.0) * previewRect.width,
        previewRect.top + landmark.y.clamp(0.0, 1.0) * previewRect.height,
      );
      points[landmarkType] = point;
      confidenceSum += confidence;
      confidenceCount += 1;

      if (confidence >= _minimumBoundsConfidence) {
        reliableXs.add(point.dx);
        reliableYs.add(point.dy);
      }
    }

    if (points.length < 4 || reliableXs.isEmpty || reliableYs.isEmpty) {
      return null;
    }

    final rawBounds = Rect.fromLTRB(
      reliableXs.reduce(math.min),
      reliableYs.reduce(math.min),
      reliableXs.reduce(math.max),
      reliableYs.reduce(math.max),
    );
    final padding = math.max(rawBounds.height * 0.08, 12.0);
    final bounds = rawBounds.inflate(padding);
    return _PoseOverlayModel(
      points: points,
      bounds: bounds,
      averageConfidence: confidenceCount == 0
          ? 0
          : confidenceSum / confidenceCount,
    );
  }
}

class _ParentCameraPoseOverlayPainter extends CustomPainter {
  const _ParentCameraPoseOverlayPainter({
    required this.overlays,
    required this.palette,
  });

  final List<_PoseOverlayModel> overlays;
  final _PoseOverlayPalette palette;

  static const _segments = <(JumpRopeLandmarkType, JumpRopeLandmarkType)>[
    (JumpRopeLandmarkType.leftEar, JumpRopeLandmarkType.rightEar),
    (JumpRopeLandmarkType.leftShoulder, JumpRopeLandmarkType.rightShoulder),
    (JumpRopeLandmarkType.leftShoulder, JumpRopeLandmarkType.leftElbow),
    (JumpRopeLandmarkType.leftElbow, JumpRopeLandmarkType.leftWrist),
    (JumpRopeLandmarkType.rightShoulder, JumpRopeLandmarkType.rightElbow),
    (JumpRopeLandmarkType.rightElbow, JumpRopeLandmarkType.rightWrist),
    (JumpRopeLandmarkType.leftShoulder, JumpRopeLandmarkType.leftHip),
    (JumpRopeLandmarkType.rightShoulder, JumpRopeLandmarkType.rightHip),
    (JumpRopeLandmarkType.leftHip, JumpRopeLandmarkType.rightHip),
    (JumpRopeLandmarkType.leftHip, JumpRopeLandmarkType.leftKnee),
    (JumpRopeLandmarkType.leftKnee, JumpRopeLandmarkType.leftAnkle),
    (JumpRopeLandmarkType.rightHip, JumpRopeLandmarkType.rightKnee),
    (JumpRopeLandmarkType.rightKnee, JumpRopeLandmarkType.rightAnkle),
    (JumpRopeLandmarkType.leftAnkle, JumpRopeLandmarkType.leftHeel),
    (JumpRopeLandmarkType.leftHeel, JumpRopeLandmarkType.leftFootIndex),
    (JumpRopeLandmarkType.rightAnkle, JumpRopeLandmarkType.rightHeel),
    (JumpRopeLandmarkType.rightHeel, JumpRopeLandmarkType.rightFootIndex),
  ];

  @override
  void paint(Canvas canvas, Size size) {
    for (var index = overlays.length - 1; index >= 0; index--) {
      final overlay = overlays[index];
      final isPrimary = index == 0;
      final emphasis = isPrimary ? 1.0 : 0.76;
      _paintBounds(canvas, overlay, emphasis, isPrimary);
      _paintSkeleton(canvas, overlay, emphasis);
      _paintHeadHalo(canvas, overlay, emphasis);
    }
  }

  void _paintBounds(
    Canvas canvas,
    _PoseOverlayModel overlay,
    double emphasis,
    bool isPrimary,
  ) {
    final bounds = overlay.bounds;
    final radius = Radius.circular(math.max(bounds.width * 0.14, 24));
    final rRect = RRect.fromRectAndRadius(bounds, radius);
    final alpha = _alphaFor(overlay);
    final glowPaint = Paint()
      ..color = palette.glow.withValues(
        alpha: (0.26 * emphasis * alpha).clamp(0.0, 1.0).toDouble(),
      )
      ..style = PaintingStyle.stroke
      ..strokeWidth = 18
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 18);
    final fillPaint = Paint()
      ..shader = LinearGradient(
        colors: [
          palette.fill.withValues(
            alpha: (0.18 * emphasis * alpha).clamp(0.0, 1.0).toDouble(),
          ),
          Colors.transparent,
        ],
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
      ).createShader(bounds);
    final borderPaint = Paint()
      ..color = palette.stroke.withValues(
        alpha: (0.88 * emphasis * alpha).clamp(0.0, 1.0).toDouble(),
      )
      ..style = PaintingStyle.stroke
      ..strokeWidth = isPrimary ? 2.8 : 2.0;

    canvas.drawRRect(rRect, glowPaint);
    canvas.drawRRect(rRect, fillPaint);
    canvas.drawRRect(rRect, borderPaint);
  }

  void _paintSkeleton(
    Canvas canvas,
    _PoseOverlayModel overlay,
    double emphasis,
  ) {
    final alpha = _alphaFor(overlay);
    final linePaint = Paint()
      ..color = palette.stroke.withValues(
        alpha: (0.9 * emphasis * alpha).clamp(0.0, 1.0).toDouble(),
      )
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..strokeWidth = emphasis > 0.9 ? 4.6 : 3.4;
    final jointPaint = Paint()
      ..color = palette.point.withValues(
        alpha: (0.95 * emphasis * alpha).clamp(0.0, 1.0).toDouble(),
      )
      ..style = PaintingStyle.fill;

    for (final (startType, endType) in _segments) {
      final start = overlay.points[startType];
      final end = overlay.points[endType];
      if (start == null || end == null) {
        continue;
      }
      canvas.drawLine(start, end, linePaint);
    }

    for (final point in overlay.points.values) {
      canvas.drawCircle(point, emphasis > 0.9 ? 3.4 : 2.8, jointPaint);
    }
  }

  void _paintHeadHalo(
    Canvas canvas,
    _PoseOverlayModel overlay,
    double emphasis,
  ) {
    final nose = overlay.points[JumpRopeLandmarkType.nose];
    final leftEar = overlay.points[JumpRopeLandmarkType.leftEar];
    final rightEar = overlay.points[JumpRopeLandmarkType.rightEar];
    if (nose == null && leftEar == null && rightEar == null) {
      return;
    }

    final headPoints = <Offset>[?nose, ?leftEar, ?rightEar];
    final headCenter = Offset(
      headPoints
              .map((point) => point.dx)
              .reduce((left, right) => left + right) /
          headPoints.length,
      headPoints
              .map((point) => point.dy)
              .reduce((left, right) => left + right) /
          headPoints.length,
    );
    final earDistance = leftEar != null && rightEar != null
        ? (rightEar.dx - leftEar.dx).abs()
        : overlay.bounds.width * 0.18;
    final radius = math.max(earDistance * 0.55, 14).toDouble();
    final headPaint = Paint()
      ..color = palette.point.withValues(
        alpha: (0.4 * emphasis * _alphaFor(overlay)).clamp(0.0, 1.0).toDouble(),
      )
      ..style = PaintingStyle.stroke
      ..strokeWidth = emphasis > 0.9 ? 3.0 : 2.4;
    canvas.drawCircle(headCenter, radius, headPaint);
  }

  double _alphaFor(_PoseOverlayModel overlay) {
    return overlay.averageConfidence.clamp(0.35, 1.0).toDouble();
  }

  @override
  bool shouldRepaint(covariant _ParentCameraPoseOverlayPainter oldDelegate) {
    return oldDelegate.overlays != overlays || oldDelegate.palette != palette;
  }
}

class _PoseOverlayPalette {
  const _PoseOverlayPalette({
    required this.stroke,
    required this.glow,
    required this.fill,
    required this.point,
  });

  final Color stroke;
  final Color glow;
  final Color fill;
  final Color point;

  factory _PoseOverlayPalette.fromStatus(String analysisStatusKey) {
    switch (analysisStatusKey) {
      case 'parentCameraAnalysisMultiplePeople':
        return const _PoseOverlayPalette(
          stroke: Color(0xFFFF7A5C),
          glow: Color(0x99FF7A5C),
          fill: Color(0x44FF7A5C),
          point: Color(0xFFFFD2C8),
        );
      case 'parentCameraAnalysisLowConfidence':
      case 'parentCameraAnalysisSearching':
        return const _PoseOverlayPalette(
          stroke: Color(0xFFFFC65C),
          glow: Color(0x99FFC65C),
          fill: Color(0x33FFC65C),
          point: Color(0xFFFFF0D0),
        );
      case 'parentCameraAnalysisPaused':
        return const _PoseOverlayPalette(
          stroke: Color(0xFFA6AFBF),
          glow: Color(0x80A6AFBF),
          fill: Color(0x2AA6AFBF),
          point: Color(0xFFE5E9F0),
        );
      case 'parentCameraAnalysisTracking':
      default:
        return const _PoseOverlayPalette(
          stroke: Color(0xFF66F2CD),
          glow: Color(0x9966F2CD),
          fill: Color(0x3366F2CD),
          point: Color(0xFFE3FFF8),
        );
    }
  }

  @override
  bool operator ==(Object other) {
    return other is _PoseOverlayPalette &&
        other.stroke == stroke &&
        other.glow == glow &&
        other.fill == fill &&
        other.point == point;
  }

  @override
  int get hashCode => Object.hash(stroke, glow, fill, point);
}
