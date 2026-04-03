import 'dart:io';

import 'package:gal/gal.dart';

abstract class VideoLibrarySaver {
  Future<void> saveVideo(String videoPath);
}

class GalVideoLibrarySaver implements VideoLibrarySaver {
  static const _albumName = '葡萄跳绳';

  @override
  Future<void> saveVideo(String videoPath) async {
    if (videoPath.isEmpty || videoPath.startsWith('mock://')) {
      throw const VideoLibrarySaveException('videoUnavailable');
    }

    final file = File(videoPath);
    if (!await file.exists()) {
      throw const VideoLibrarySaveException('videoUnavailable');
    }

    try {
      final hasAccess = await Gal.hasAccess(toAlbum: true);
      if (!hasAccess) {
        final granted = await Gal.requestAccess(toAlbum: true);
        if (!granted) {
          throw const VideoLibrarySaveException(
            'videoSavePermissionDenied',
            detail: 'gallery_permission_denied',
          );
        }
      }

      await Gal.putVideo(videoPath, album: _albumName);
    } on GalException catch (error) {
      if (error.type == GalExceptionType.accessDenied) {
        throw VideoLibrarySaveException(
          'videoSavePermissionDenied',
          detail: _errorDetail(error),
        );
      }
      if (error.type == GalExceptionType.notEnoughSpace) {
        throw VideoLibrarySaveException(
          'videoSaveNotEnoughSpace',
          detail: _errorDetail(error),
        );
      }
      if (error.type == GalExceptionType.notSupportedFormat) {
        throw VideoLibrarySaveException(
          'videoSaveNotSupportedFormat',
          detail: _errorDetail(error),
        );
      }
      throw VideoLibrarySaveException(
        'videoSaveUnexpected',
        detail: _errorDetail(error),
      );
    } catch (_) {
      throw const VideoLibrarySaveException('videoSaveFailed');
    }
  }

  String _errorDetail(GalException error) {
    final message = error.platformException.message ?? 'empty_message';
    return 'type=${error.type.code} message=$message';
  }
}

class VideoLibrarySaveException implements Exception {
  const VideoLibrarySaveException(this.errorKey, {this.detail});

  final String errorKey;
  final String? detail;
}
