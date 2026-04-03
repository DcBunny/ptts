import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tiaosheng/core/i18n/app_i18n.dart';

void main() {
  test('家长拍摄页错误文案会正确映射视频处理失败', () {
    const i18n = AppI18n(Locale('zh'));

    expect(i18n.parentCameraErrorMessage('videoComposeFailed'), '视频处理失败，请重试保存');
    expect(i18n.parentCameraErrorMessage('cameraInitFailed'), '相机启动失败，请稍后重试');
  });
}
