import 'package:shared_preferences/shared_preferences.dart';

import 'local_storage.dart';

class SharedPreferencesStorage implements LocalStorage {
  SharedPreferencesStorage(this._preferences);

  final SharedPreferences _preferences;

  static Future<SharedPreferencesStorage> create() async {
    final preferences = await SharedPreferences.getInstance();
    return SharedPreferencesStorage(preferences);
  }

  @override
  String? getString(String key) {
    return _preferences.getString(key);
  }

  @override
  Future<void> setString(String key, String value) async {
    await _preferences.setString(key, value);
  }
}
