import 'package:flutter/widgets.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:tiaosheng/core/app/tiaosheng_app.dart';
import 'package:tiaosheng/core/storage/shared_preferences_storage.dart';
import 'package:tiaosheng/features/jump_session/view_model/jump_session_view_model.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final storage = await SharedPreferencesStorage.create();
  runApp(
    ProviderScope(
      overrides: [localStorageProvider.overrideWithValue(storage)],
      child: const TiaoShengApp(),
    ),
  );
}
