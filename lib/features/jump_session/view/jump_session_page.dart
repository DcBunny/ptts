import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:tiaosheng/core/i18n/app_i18n.dart';
import 'package:tiaosheng/features/jump_session/view_model/jump_session_view_model.dart';

class JumpSessionPage extends ConsumerWidget {
  const JumpSessionPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final i18n = context.i18n;
    final state = ref.watch(jumpSessionViewModelProvider);
    final viewModel = ref.read(jumpSessionViewModelProvider.notifier);
    final bestRecord = state.bestRecord;
    final displayedSeconds = state.selectedDurationSeconds;

    return Scaffold(
      backgroundColor: const Color(0xFFF8F3EA),
      appBar: AppBar(
        backgroundColor: const Color(0xFFF8F3EA),
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: true,
        title: Text(i18n.appTitle),
      ),
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            colors: [Color(0xFFF8F3EA), Color(0xFFFDEAD8)],
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
          ),
        ),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          children: [
            _SectionCard(
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          i18n.bestRecord,
                          style: Theme.of(context).textTheme.titleMedium
                              ?.copyWith(
                                fontWeight: FontWeight.w700,
                                color: const Color(0xFF504B46),
                              ),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          bestRecord == null ? '0' : '${bestRecord.jumpCount}',
                          style: Theme.of(context).textTheme.displayMedium
                              ?.copyWith(
                                fontWeight: FontWeight.w800,
                                color: const Color(0xFF191919),
                              ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          bestRecord == null
                              ? i18n.bestRecordEmpty
                              : '${i18n.bestRecordDetail} · ${_formatRecordDate(bestRecord.startedAt)}',
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(color: const Color(0xFF7A726B)),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 16),
                  Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(
                      color: const Color(0xFFFFF2CF),
                      borderRadius: BorderRadius.circular(24),
                    ),
                    child: const Icon(
                      Icons.workspace_premium_rounded,
                      color: Color(0xFFE2A11B),
                      size: 40,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            _SectionCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    i18n.duration,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: const Color(0xFF504B46),
                    ),
                  ),
                  const SizedBox(height: 12),
                  Center(
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        color: const Color(0xFFFFF4EA),
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        i18n.durationAdjustHint,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: const Color(0xFFDE6D34),
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  Row(
                    children: [
                      _DurationButton(
                        icon: Icons.remove_rounded,
                        onPressed:
                            state.selectedDurationSeconds >
                                JumpSessionViewModel.minDurationSeconds
                            ? viewModel.decreaseDuration
                            : null,
                      ),
                      Expanded(
                        child: Column(
                          children: [
                            Text(
                              _formatDuration(displayedSeconds),
                              key: const Key('duration-display'),
                              style: Theme.of(context).textTheme.displayLarge
                                  ?.copyWith(
                                    fontWeight: FontWeight.w800,
                                    color: const Color(0xFF191919),
                                  ),
                            ),
                            const SizedBox(height: 10),
                            Text(
                              i18n.parentPhotoHint,
                              style: Theme.of(context).textTheme.bodyMedium
                                  ?.copyWith(color: const Color(0xFF7A726B)),
                              textAlign: TextAlign.center,
                            ),
                          ],
                        ),
                      ),
                      _DurationButton(
                        icon: Icons.add_rounded,
                        onPressed: viewModel.increaseDuration,
                      ),
                    ],
                  ),
                  const SizedBox(height: 28),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () => context.push('/parent-camera'),
                      style: FilledButton.styleFrom(
                        backgroundColor: const Color(0xFFFF6D3A),
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 18),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(24),
                        ),
                      ),
                      icon: const Icon(Icons.camera_alt_rounded),
                      label: Text(
                        i18n.parentPhoto,
                        style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ),
                  if (state.errorMessage != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      state.errorMessage!,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: const Color(0xFFC94A31),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  static String _formatDuration(int seconds) {
    final minutes = seconds ~/ 60;
    final remainingSeconds = seconds % 60;
    final minuteText = minutes.toString().padLeft(1, '0');
    final secondText = remainingSeconds.toString().padLeft(2, '0');
    return '$minuteText:$secondText';
  }

  static String _formatRecordDate(DateTime dateTime) {
    final year = dateTime.year.toString();
    final month = dateTime.month.toString().padLeft(2, '0');
    final day = dateTime.day.toString().padLeft(2, '0');
    return '$year.$month.$day';
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(28),
        boxShadow: const [
          BoxShadow(
            color: Color(0x14000000),
            blurRadius: 24,
            offset: Offset(0, 12),
          ),
        ],
      ),
      child: child,
    );
  }
}

class _DurationButton extends StatelessWidget {
  const _DurationButton({required this.icon, required this.onPressed});

  final IconData icon;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return IconButton.filledTonal(
      onPressed: onPressed,
      style: IconButton.styleFrom(
        backgroundColor: const Color(0xFFF4EFE8),
        disabledBackgroundColor: const Color(0xFFF4EFE8),
        minimumSize: const Size(56, 56),
      ),
      icon: Icon(icon, size: 30, color: const Color(0xFF6D665E)),
    );
  }
}
