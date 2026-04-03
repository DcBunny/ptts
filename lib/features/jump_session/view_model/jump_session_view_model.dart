import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:tiaosheng/core/storage/local_storage.dart';
import 'package:tiaosheng/features/jump_session/data/jump_session_models.dart';
import 'package:tiaosheng/features/jump_session/data/jump_session_repository.dart';

final localStorageProvider = Provider<LocalStorage>((ref) {
  throw UnimplementedError('请在 main.dart 中覆盖 localStorageProvider');
});

final jumpSessionRepositoryProvider = Provider<JumpSessionRepository>((ref) {
  final storage = ref.watch(localStorageProvider);
  return LocalJumpSessionRepository(storage);
});

final jumpSessionViewModelProvider =
    NotifierProvider<JumpSessionViewModel, JumpSessionState>(
      JumpSessionViewModel.new,
    );

class JumpSessionViewModel extends Notifier<JumpSessionState> {
  static const minDurationSeconds = 10;
  static const durationStepSeconds = 30;

  @override
  JumpSessionState build() {
    Future<void>.microtask(loadHistory);
    return JumpSessionState.initial();
  }

  Future<void> loadHistory() async {
    final repository = ref.read(jumpSessionRepositoryProvider);
    try {
      final previousState = state;
      final history = await repository.loadHistory();
      if (!identical(state, previousState)) {
        return;
      }
      state = state.copyWith(history: history, clearError: true);
    } catch (_) {}
  }

  void selectDuration(int seconds) {
    _updateDuration(seconds);
  }

  void increaseDuration() {
    if (state.selectedDurationSeconds == minDurationSeconds) {
      _updateDuration(durationStepSeconds);
      return;
    }
    _updateDuration(state.selectedDurationSeconds + durationStepSeconds);
  }

  void decreaseDuration() {
    if (state.selectedDurationSeconds <= durationStepSeconds) {
      if (state.selectedDurationSeconds > minDurationSeconds) {
        _updateDuration(minDurationSeconds);
      }
      return;
    }
    final next = state.selectedDurationSeconds - durationStepSeconds;
    _updateDuration(next);
  }

  void _updateDuration(int seconds) {
    state = state.copyWith(selectedDurationSeconds: seconds, clearError: true);
  }
}
