class JumpSessionRecord {
  const JumpSessionRecord({
    required this.id,
    required this.startedAt,
    required this.durationSeconds,
    required this.jumpCount,
    required this.videoPath,
  });

  final String id;
  final DateTime startedAt;
  final int durationSeconds;
  final int jumpCount;
  final String videoPath;

  JumpSessionRecord copyWith({
    String? id,
    DateTime? startedAt,
    int? durationSeconds,
    int? jumpCount,
    String? videoPath,
  }) {
    return JumpSessionRecord(
      id: id ?? this.id,
      startedAt: startedAt ?? this.startedAt,
      durationSeconds: durationSeconds ?? this.durationSeconds,
      jumpCount: jumpCount ?? this.jumpCount,
      videoPath: videoPath ?? this.videoPath,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'startedAt': startedAt.toIso8601String(),
      'durationSeconds': durationSeconds,
      'jumpCount': jumpCount,
      'videoPath': videoPath,
    };
  }

  factory JumpSessionRecord.fromJson(Map<String, dynamic> json) {
    return JumpSessionRecord(
      id: json['id'] as String,
      startedAt: DateTime.parse(json['startedAt'] as String),
      durationSeconds: json['durationSeconds'] as int,
      jumpCount: json['jumpCount'] as int,
      videoPath: json['videoPath'] as String,
    );
  }
}

class JumpSessionState {
  const JumpSessionState({
    required this.selectedDurationSeconds,
    required this.history,
    required this.errorMessage,
  });

  factory JumpSessionState.initial() {
    const defaultDuration = 60;
    return const JumpSessionState(
      selectedDurationSeconds: defaultDuration,
      history: [],
      errorMessage: null,
    );
  }

  final int selectedDurationSeconds;
  final List<JumpSessionRecord> history;
  final String? errorMessage;

  JumpSessionRecord? get bestRecord {
    if (history.isEmpty) {
      return null;
    }

    JumpSessionRecord? best;
    for (final record in history) {
      if (best == null) {
        best = record;
        continue;
      }

      final isBetter = record.jumpCount > best.jumpCount;
      final isSameCountButNewer =
          record.jumpCount == best.jumpCount &&
          record.startedAt.isAfter(best.startedAt);
      if (isBetter || isSameCountButNewer) {
        best = record;
      }
    }
    return best;
  }

  JumpSessionState copyWith({
    int? selectedDurationSeconds,
    List<JumpSessionRecord>? history,
    String? errorMessage,
    bool clearError = false,
  }) {
    return JumpSessionState(
      selectedDurationSeconds:
          selectedDurationSeconds ?? this.selectedDurationSeconds,
      history: history ?? this.history,
      errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
    );
  }
}
