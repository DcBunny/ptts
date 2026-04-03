import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:tiaosheng/core/storage/local_storage.dart';
import 'package:tiaosheng/features/jump_session/view_model/jump_session_view_model.dart';
import 'package:tiaosheng/features/parent_camera/data/parent_camera_service.dart';
import 'package:tiaosheng/features/parent_camera/data/session_video_overlay_processor.dart';
import 'package:tiaosheng/features/parent_camera/data/session_video_overlay_timeline.dart';
import 'package:tiaosheng/features/parent_camera/data/video_library_saver.dart';
import 'package:tiaosheng/features/parent_camera/view_model/parent_camera_state.dart';
import 'package:tiaosheng/features/parent_camera/view_model/parent_camera_view_model.dart';

void main() {
  test('倒计时结束后才启动底层采集并进入正式录制', () async {
    final context = _TestContext.create(videoPath: '/tmp/parent_camera_a.mp4');
    addTearDown(context.dispose);

    await context.waitForCameraReady();
    final viewModel = context.container.read(
      parentCameraViewModelProvider.notifier,
    );

    unawaited(viewModel.startCountdownAndRecording());
    await Future<void>.delayed(const Duration(milliseconds: 100));

    final countdownState = context.readState();
    expect(context.device.startRecordingCalls, 0);
    expect(countdownState.isCountdownActive, isTrue);
    expect(countdownState.isRecording, isFalse);
    expect(countdownState.countdownValue, 3);

    await Future<void>.delayed(const Duration(seconds: 3, milliseconds: 100));

    final recordingState = context.readState();
    expect(context.device.startRecordingCalls, 1);
    expect(recordingState.isRecording, isTrue);
    expect(recordingState.isCountdownActive, isFalse);
    expect(recordingState.countdownValue, isNull);
  });

  test('录制结束后会导出包含左上时间和右上计数标签的本地视频，再保存到相册', () async {
    final context = _TestContext.create(videoPath: '/tmp/parent_camera_b.mp4');
    addTearDown(context.dispose);

    await context.waitForCameraReady();
    final viewModel = context.container.read(
      parentCameraViewModelProvider.notifier,
    );

    await viewModel.startCountdownAndRecording();
    viewModel.incrementJumpCount();
    await Future<void>.delayed(const Duration(milliseconds: 20));
    viewModel.incrementJumpCount();
    await viewModel.stopRecordingSession();

    final state = context.readState();
    final history = await context.container
        .read(jumpSessionRepositoryProvider)
        .loadHistory();
    expect(context.device.startRecordingCalls, 1);
    expect(context.device.stopRecordingCalls, 1);
    expect(context.overlayProcessor.lastInputPath, '/tmp/parent_camera_b.mp4');
    final overlayItems = context.overlayProcessor.lastOverlayItems;
    expect(overlayItems, isNotNull);
    expect(
      overlayItems!
          .where(
            (item) =>
                item.style == SessionVideoOverlayStyle.badge &&
                item.position == SessionVideoOverlayPosition.topLeft,
          )
          .any((item) => RegExp(r'^\d+:\d{2}$').hasMatch(item.text)),
      isTrue,
    );
    expect(
      overlayItems
          .where((item) => item.style == SessionVideoOverlayStyle.countdown)
          .length,
      0,
    );
    expect(
      overlayItems.any(
        (item) =>
            item.style == SessionVideoOverlayStyle.badge &&
            item.position == SessionVideoOverlayPosition.topRight,
      ),
      isTrue,
    );
    expect(
      overlayItems.any(
        (item) =>
            item.style == SessionVideoOverlayStyle.subtitle &&
            item.position == SessionVideoOverlayPosition.bottomCenter &&
            item.text.isNotEmpty,
      ),
      isTrue,
    );
    expect(state.summary?.videoPath, '/tmp/parent_camera_b_overlay.mp4');
    expect(state.isVideoSaved, isFalse);
    expect(state.feedbackKey, isNull);
    expect(history, hasLength(1));
    expect(history.first.jumpCount, 2);
    expect(history.first.videoPath, '/tmp/parent_camera_b_overlay.mp4');

    await viewModel.saveVideoToLibrary();
    final savedState = context.readState();
    expect(context.videoLibrarySaver.savedPaths, [
      '/tmp/parent_camera_b_overlay.mp4',
    ]);
    expect(savedState.isVideoSaved, isTrue);
    expect(savedState.feedbackKey, 'videoSaveSuccess');
  });

  test('本地视频导出失败时不会写入历史记录，也不会允许保存到相册', () async {
    final context = _TestContext.create(
      videoPath: '/tmp/parent_camera_c.mp4',
      overlayError: const SessionVideoOverlayException('videoComposeFailed'),
    );
    addTearDown(context.dispose);

    await context.waitForCameraReady();
    final viewModel = context.container.read(
      parentCameraViewModelProvider.notifier,
    );

    await viewModel.startCountdownAndRecording();
    viewModel.incrementJumpCount();
    await viewModel.stopRecordingSession();
    await viewModel.saveVideoToLibrary();

    final state = context.readState();
    final history = await context.container
        .read(jumpSessionRepositoryProvider)
        .loadHistory();

    expect(state.summary?.videoPath, isNull);
    expect(state.errorKey, 'videoComposeFailed');
    expect(state.canSaveVideoToLibrary, isFalse);
    expect(context.videoLibrarySaver.savedPaths, isEmpty);
    expect(history, isEmpty);
  });
}

class _TestContext {
  _TestContext({
    required this.container,
    required this.subscription,
    required this.device,
    required this.overlayProcessor,
    required this.videoLibrarySaver,
  });

  final ProviderContainer container;
  final ProviderSubscription<ParentCameraState> subscription;
  final _FakeParentCameraDevice device;
  final _FakeSessionVideoOverlayProcessor overlayProcessor;
  final _FakeVideoLibrarySaver videoLibrarySaver;

  static _TestContext create({
    required String videoPath,
    SessionVideoOverlayException? overlayError,
  }) {
    final device = _FakeParentCameraDevice(videoPath: videoPath);
    final overlayProcessor = _FakeSessionVideoOverlayProcessor(
      outputPath: videoPath.replaceFirst('.mp4', '_overlay.mp4'),
      error: overlayError,
    );
    final videoLibrarySaver = _FakeVideoLibrarySaver();
    final container = ProviderContainer(
      overrides: [
        localStorageProvider.overrideWithValue(_InMemoryLocalStorage()),
        parentCameraServiceProvider.overrideWithValue(
          _FakeParentCameraService(device),
        ),
        sessionVideoOverlayProcessorProvider.overrideWithValue(
          overlayProcessor,
        ),
        videoLibrarySaverProvider.overrideWithValue(videoLibrarySaver),
      ],
    );
    final subscription = container.listen<ParentCameraState>(
      parentCameraViewModelProvider,
      (previous, next) {},
      fireImmediately: true,
    );
    return _TestContext(
      container: container,
      subscription: subscription,
      device: device,
      overlayProcessor: overlayProcessor,
      videoLibrarySaver: videoLibrarySaver,
    );
  }

  ParentCameraState readState() {
    return container.read(parentCameraViewModelProvider);
  }

  Future<void> waitForCameraReady() async {
    while (readState().isInitializing) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
  }

  Future<void> dispose() async {
    await container
        .read(parentCameraViewModelProvider.notifier)
        .prepareToLeave();
    subscription.close();
    container.dispose();
  }
}

class _InMemoryLocalStorage implements LocalStorage {
  final Map<String, String> _values = <String, String>{};

  @override
  String? getString(String key) {
    return _values[key];
  }

  @override
  Future<void> setString(String key, String value) async {
    _values[key] = value;
  }
}

class _FakeParentCameraService implements ParentCameraService {
  _FakeParentCameraService(this.device);

  final _FakeParentCameraDevice device;

  @override
  Future<ParentCameraDevice> createDefaultDevice() async {
    return device;
  }
}

class _FakeParentCameraDevice implements ParentCameraDevice {
  _FakeParentCameraDevice({required this.videoPath});

  final String videoPath;
  var _isRecording = false;
  var startRecordingCalls = 0;
  var stopRecordingCalls = 0;
  var disposeCalls = 0;

  @override
  bool get isInitialized => true;

  @override
  bool get isRecording => _isRecording;

  @override
  Size? get previewSize => const Size(720, 1280);

  @override
  Widget buildPreview() {
    return const SizedBox.shrink();
  }

  @override
  Future<void> dispose() async {
    disposeCalls += 1;
  }

  @override
  Future<void> startRecording() async {
    startRecordingCalls += 1;
    _isRecording = true;
  }

  @override
  Future<String?> stopRecording() async {
    stopRecordingCalls += 1;
    _isRecording = false;
    return videoPath;
  }
}

class _FakeVideoLibrarySaver implements VideoLibrarySaver {
  final List<String> savedPaths = <String>[];

  @override
  Future<void> saveVideo(String videoPath) async {
    savedPaths.add(videoPath);
  }
}

class _FakeSessionVideoOverlayProcessor
    implements SessionVideoOverlayProcessor {
  _FakeSessionVideoOverlayProcessor({required this.outputPath, this.error});

  final String outputPath;
  final SessionVideoOverlayException? error;
  String? lastInputPath;
  List<SessionVideoOverlayItem>? lastOverlayItems;

  @override
  Future<String> process({
    required String inputPath,
    required List<SessionVideoOverlayItem> overlayItems,
  }) async {
    lastInputPath = inputPath;
    lastOverlayItems = List<SessionVideoOverlayItem>.from(overlayItems);
    if (error != null) {
      throw error!;
    }
    return outputPath;
  }
}
