import 'dart:io';

import 'package:flutter/services.dart';
import 'package:tiaosheng/features/parent_camera/data/session_video_overlay_timeline.dart';

abstract class SessionVideoOverlayProcessor {
  Future<String> process({
    required String inputPath,
    required List<SessionVideoOverlayItem> overlayItems,
  });
}

class NativeSessionVideoOverlayProcessor
    implements SessionVideoOverlayProcessor {
  static const MethodChannel _channel = MethodChannel(
    'tiaosheng/session_video_overlay',
  );

  @override
  Future<String> process({
    required String inputPath,
    required List<SessionVideoOverlayItem> overlayItems,
  }) async {
    final inputFile = File(inputPath);
    final existsInput = await inputFile.exists();
    if (existsInput == false) {
      throw const SessionVideoOverlayException('videoUnavailable');
    }

    try {
      final outputPath = await _channel
          .invokeMethod<String>('composeWithOverlay', <String, Object>{
            'inputPath': inputPath,
            'overlayItems': overlayItems.map((item) => item.toMap()).toList(),
          });
      if (outputPath == null || outputPath.isEmpty) {
        throw const SessionVideoOverlayException('videoComposeOutputMissing');
      }

      final outputFile = File(outputPath);
      final existsOutput = await outputFile.exists();
      if (existsOutput == false) {
        throw const SessionVideoOverlayException('videoComposeOutputMissing');
      }
      return outputPath;
    } on MissingPluginException catch (error) {
      throw SessionVideoOverlayException(
        'videoComposeFailed',
        detail: 'missing_plugin:${error.message ?? 'unknown'}',
      );
    } on PlatformException catch (error) {
      throw SessionVideoOverlayException(
        _resolveErrorKey(error.code),
        detail: _buildPlatformErrorDetail(error),
      );
    } catch (error) {
      throw SessionVideoOverlayException(
        'videoComposeFailed',
        detail: 'unexpected:$error',
      );
    }
  }

  String _resolveErrorKey(String code) {
    switch (code) {
      case 'videoUnavailable':
      case 'videoComposeOutputMissing':
      case 'videoComposeFailed':
        return code;
      default:
        return 'videoComposeFailed';
    }
  }

  String _buildPlatformErrorDetail(PlatformException error) {
    final message = error.message?.trim();
    if (message != null && message.isNotEmpty) {
      return message;
    }
    final details = error.details?.toString().trim();
    if (details != null && details.isNotEmpty) {
      return details;
    }
    return error.code;
  }
}

class SessionVideoOverlayException implements Exception {
  const SessionVideoOverlayException(this.errorKey, {this.detail});

  final String errorKey;
  final String? detail;
}
