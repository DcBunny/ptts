import 'dart:convert';

import 'package:tiaosheng/core/storage/local_storage.dart';

import 'jump_session_models.dart';

abstract class JumpSessionRepository {
  Future<List<JumpSessionRecord>> loadHistory();

  Future<void> saveRecord(JumpSessionRecord record);

  Future<void> updateRecordVideoPath({
    required String recordId,
    required String videoPath,
  });
}

class LocalJumpSessionRepository implements JumpSessionRepository {
  LocalJumpSessionRepository(this._storage);

  final LocalStorage _storage;

  static const _historyKey = 'jump_session_history';

  @override
  Future<List<JumpSessionRecord>> loadHistory() async {
    final rawValue = _storage.getString(_historyKey);
    if (rawValue == null || rawValue.isEmpty) {
      return [];
    }

    final decoded = jsonDecode(rawValue) as List<dynamic>;
    return decoded
        .map((item) => JumpSessionRecord.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<void> saveRecord(JumpSessionRecord record) async {
    final history = await loadHistory();
    final updated = [record, ...history.where((item) => item.id != record.id)];
    await _saveHistory(updated);
  }

  @override
  Future<void> updateRecordVideoPath({
    required String recordId,
    required String videoPath,
  }) async {
    final history = await loadHistory();
    final updated = history
        .map(
          (item) =>
              item.id == recordId ? item.copyWith(videoPath: videoPath) : item,
        )
        .toList();
    await _saveHistory(updated);
  }

  Future<void> _saveHistory(List<JumpSessionRecord> history) async {
    final encoded = jsonEncode(history.map((item) => item.toJson()).toList());
    await _storage.setString(_historyKey, encoded);
  }
}
