// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tiaosheng/core/app/tiaosheng_app.dart';
import 'package:tiaosheng/core/storage/shared_preferences_storage.dart';
import 'package:tiaosheng/features/jump_session/view_model/jump_session_view_model.dart';
import 'package:tiaosheng/features/parent_camera/data/parent_camera_service.dart';
import 'package:tiaosheng/features/parent_camera/view_model/parent_camera_view_model.dart';

void main() {
  testWidgets('首页展示精简后的跳绳功能', (WidgetTester tester) async {
    SharedPreferences.setMockInitialValues({});
    final storage = await SharedPreferencesStorage.create();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          localStorageProvider.overrideWithValue(storage),
          parentCameraServiceProvider.overrideWithValue(
            _FakeParentCameraService(),
          ),
        ],
        child: const TiaoShengApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('葡萄跳绳'), findsOneWidget);
    expect(find.text('最佳记录'), findsOneWidget);
    expect(find.text('家长拍照'), findsOneWidget);
    expect(find.byKey(const Key('duration-display')), findsOneWidget);
    expect(find.text('1:00'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pump();
    expect(find.text('1:30'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.remove_rounded));
    await tester.pump();
    expect(find.text('1:00'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.remove_rounded));
    await tester.pump();
    expect(find.text('0:30'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.remove_rounded));
    await tester.pump();
    expect(find.text('0:10'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pump();
    expect(find.text('0:30'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.add_rounded));
    await tester.pump();
    expect(find.text('1:00'), findsOneWidget);

    await tester.tap(find.text('家长拍照'));
    await tester.pumpAndSettle();
    expect(find.text('开始记录'), findsOneWidget);
    expect(find.text('退出'), findsOneWidget);

    await tester.tap(find.byTooltip('退出'));
    await tester.pumpAndSettle();
    expect(find.text('最佳记录'), findsOneWidget);
  });
}

class _FakeParentCameraService implements ParentCameraService {
  @override
  Future<ParentCameraDevice> createDefaultDevice() async {
    return _FakeParentCameraDevice();
  }
}

class _FakeParentCameraDevice implements ParentCameraDevice {
  bool _isRecording = false;

  @override
  bool get isInitialized => true;

  @override
  bool get isRecording => _isRecording;

  @override
  Size? get previewSize => const Size(720, 1280);

  @override
  Widget buildPreview() {
    return const ColoredBox(color: Colors.black);
  }

  @override
  Future<void> dispose() async {}

  @override
  Future<void> startRecording() async {
    _isRecording = true;
  }

  @override
  Future<String?> stopRecording() async {
    _isRecording = false;
    return 'mock://parent_camera/latest.mp4';
  }
}
