abstract class LocalStorage {
  Future<void> setString(String key, String value);

  String? getString(String key);
}
