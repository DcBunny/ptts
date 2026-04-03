import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:tiaosheng/core/i18n/app_i18n.dart';
import 'package:tiaosheng/features/parent_camera/data/parent_camera_service.dart';
import 'package:tiaosheng/features/parent_camera/view_model/parent_camera_state.dart';
import 'package:tiaosheng/features/parent_camera/view_model/parent_camera_view_model.dart';

class ParentCameraPage extends ConsumerWidget {
  const ParentCameraPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final i18n = context.i18n;
    final state = ref.watch(parentCameraViewModelProvider);
    final viewModel = ref.read(parentCameraViewModelProvider.notifier);
    final summary = state.summary;

    ref.listen<String?>(
      parentCameraViewModelProvider.select((value) => value.feedbackKey),
      (_, next) {
        if (next == null) {
          return;
        }
        final messenger = ScaffoldMessenger.maybeOf(context);
        messenger?.hideCurrentSnackBar();
        messenger?.showSnackBar(
          SnackBar(content: Text(i18n.parentCameraFeedback(next))),
        );
        viewModel.clearFeedback();
      },
    );

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          _CameraBackground(
            device: state.device,
            isInitializing: state.isInitializing,
          ),
          const DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [
                  Color(0x66000000),
                  Color(0x22000000),
                  Color(0xAA000000),
                ],
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
              ),
            ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
              child: Column(
                children: [
                  if (state.isRecording)
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _InfoBadge(
                          value: _formatRecordCountdown(
                            state.remainingRecordSeconds,
                          ),
                        ),
                        const Spacer(),
                        GestureDetector(
                          onTap: viewModel.incrementJumpCount,
                          child: _InfoBadge(value: '${state.jumpCount}'),
                        ),
                      ],
                    )
                  else
                    Align(
                      alignment: Alignment.topCenter,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 18,
                          vertical: 12,
                        ),
                        decoration: BoxDecoration(
                          color: const Color(0xCC2E2D31),
                          borderRadius: BorderRadius.circular(22),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              Icons.accessibility_new_rounded,
                              color: Colors.white,
                              size: 20,
                            ),
                            const SizedBox(width: 8),
                            Text(
                              i18n.parentCameraGuide,
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 14,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  const SizedBox(height: 16),
                  Expanded(
                    child: LayoutBuilder(
                      builder: (context, stageConstraints) {
                        return Stack(
                          fit: StackFit.expand,
                          children: [
                            if (!state.isRecording)
                              Center(
                                child: _FocusFrame(
                                  maxWidth: stageConstraints.maxWidth,
                                  maxHeight: stageConstraints.maxHeight,
                                  showPersonFrame: state.isFrameVisible,
                                ),
                              ),
                            if (state.isCountdownActive &&
                                state.countdownValue != null)
                              Center(
                                child: Text(
                                  '${state.countdownValue}',
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontSize: 92,
                                    fontWeight: FontWeight.w800,
                                    shadows: [
                                      Shadow(
                                        color: Color(0x80000000),
                                        blurRadius: 24,
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            if (state.isProcessingVideo)
                              Center(
                                child: _ProcessingCard(
                                  title: i18n.parentCameraProcessingVideo,
                                ),
                              ),
                            if (summary != null)
                              Center(
                                child: _SummaryCard(
                                  summary: summary,
                                  isSavingVideo: state.isSavingVideo,
                                  isVideoSaved: state.isVideoSaved,
                                  canSaveVideo: state.canSaveVideoToLibrary,
                                  onSavePressed: viewModel.saveVideoToLibrary,
                                  onRetryPressed: viewModel.retryRecording,
                                ),
                              ),
                          ],
                        );
                      },
                    ),
                  ),
                  if (state.errorKey != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 10, bottom: 14),
                      child: Column(
                        children: [
                          Text(
                            i18n.parentCameraErrorMessage(state.errorKey!),
                            textAlign: TextAlign.center,
                            style: const TextStyle(
                              color: Color(0xFFFFD8CD),
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          if (kDebugMode &&
                              state.errorDetail != null &&
                              state.errorDetail!.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 8),
                              child: Text(
                                state.errorDetail!,
                                textAlign: TextAlign.center,
                                style: const TextStyle(
                                  color: Color(0xB3FFFFFF),
                                  fontSize: 11,
                                  height: 1.4,
                                ),
                              ),
                            ),
                        ],
                      ),
                    )
                  else
                    const SizedBox(height: 24),
                  Row(
                    children: [
                      if (summary == null) ...[
                        const SizedBox(width: 68),
                        Expanded(
                          child: FilledButton(
                            onPressed: state.canStart
                                ? viewModel.startCountdownAndRecording
                                : null,
                            style: FilledButton.styleFrom(
                              backgroundColor: const Color(0xFFF2F0E8),
                              disabledBackgroundColor: const Color(0x99F2F0E8),
                              foregroundColor: const Color(0xFF2E2D31),
                              disabledForegroundColor: const Color(0xAA2E2D31),
                              padding: const EdgeInsets.symmetric(vertical: 20),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(999),
                              ),
                            ),
                            child: Text(
                              _startButtonText(i18n, state),
                              style: const TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ),
                        ),
                      ] else
                        const Spacer(),
                      const SizedBox(width: 14),
                      SizedBox(
                        width: 54,
                        height: 54,
                        child: IconButton.filled(
                          onPressed: () async {
                            await viewModel.prepareToLeave();
                            if (context.mounted) {
                              context.pop();
                            }
                          },
                          style: IconButton.styleFrom(
                            backgroundColor: const Color(0xFF1F1E22),
                            foregroundColor: Colors.white,
                          ),
                          icon: const Icon(Icons.logout_rounded),
                          tooltip: i18n.parentCameraExit,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Align(
                    alignment: Alignment.centerRight,
                    child: Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: Text(
                        i18n.parentCameraExit,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  static String _startButtonText(AppI18n i18n, ParentCameraState state) {
    if (state.isInitializing) {
      return i18n.parentCameraInitializing;
    }
    if (state.isRecording) {
      return i18n.parentCameraRecording;
    }
    if (state.isProcessingVideo) {
      return i18n.parentCameraProcessingVideo;
    }
    return i18n.parentCameraStart;
  }
}

class _ProcessingCard extends StatelessWidget {
  const _ProcessingCard({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: const BoxConstraints(maxWidth: 280),
      padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
      decoration: BoxDecoration(
        color: const Color(0xE61B1B1F),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: const Color(0x26FFFFFF)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const CircularProgressIndicator(color: Colors.white),
          const SizedBox(height: 18),
          Text(
            title,
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 16,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _SummaryCard extends StatelessWidget {
  const _SummaryCard({
    required this.summary,
    required this.isSavingVideo,
    required this.isVideoSaved,
    required this.canSaveVideo,
    required this.onSavePressed,
    required this.onRetryPressed,
  });

  final ParentCameraSummary summary;
  final bool isSavingVideo;
  final bool isVideoSaved;
  final bool canSaveVideo;
  final VoidCallback onSavePressed;
  final VoidCallback onRetryPressed;

  @override
  Widget build(BuildContext context) {
    final i18n = context.i18n;
    final theme = Theme.of(context);

    return Container(
      constraints: const BoxConstraints(maxWidth: 320),
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 22),
      decoration: BoxDecoration(
        color: const Color(0xE61B1B1F),
        borderRadius: BorderRadius.circular(28),
        border: Border.all(color: const Color(0x26FFFFFF)),
        boxShadow: const [
          BoxShadow(
            color: Color(0x70000000),
            blurRadius: 40,
            offset: Offset(0, 24),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            i18n.parentCameraSummaryTitle,
            style: theme.textTheme.titleMedium?.copyWith(
              color: Colors.white,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 18),
          Container(height: 1, color: const Color(0x24FFFFFF)),
          const SizedBox(height: 22),
          Text(
            i18n.parentCameraSummaryScore,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: const Color(0xB3FFFFFF),
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            '${summary.jumpCount}',
            style: theme.textTheme.displayLarge?.copyWith(
              color: Colors.white,
              fontWeight: FontWeight.w800,
              height: 0.95,
            ),
          ),
          const SizedBox(height: 20),
          Row(
            children: [
              Expanded(
                child: _SummaryMetric(
                  label: i18n.parentCameraSummaryTime,
                  value: _formatDuration(summary.durationSeconds),
                ),
              ),
            ],
          ),
          const SizedBox(height: 22),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: !canSaveVideo || isVideoSaved
                      ? null
                      : onSavePressed,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Colors.white,
                    disabledForegroundColor: const Color(0xFF8F8F97),
                    backgroundColor: const Color(0x00000000),
                    disabledBackgroundColor: const Color(0x33FFFFFF),
                    side: BorderSide(
                      color: isVideoSaved || isSavingVideo
                          ? const Color(0x55FFFFFF)
                          : const Color(0x8CFFFFFF),
                    ),
                    padding: const EdgeInsets.symmetric(vertical: 15),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(18),
                    ),
                  ),
                  child: Text(_saveButtonText(i18n)),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton(
                  onPressed: isSavingVideo ? null : onRetryPressed,
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFFFF6436),
                    foregroundColor: Colors.white,
                    disabledBackgroundColor: const Color(0x99FF6436),
                    padding: const EdgeInsets.symmetric(vertical: 15),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(18),
                    ),
                  ),
                  child: Text(i18n.parentCameraSummaryRetry),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  String _saveButtonText(AppI18n i18n) {
    if (isVideoSaved) {
      return i18n.parentCameraSummarySaved;
    }
    if (isSavingVideo) {
      return i18n.parentCameraSummarySaving;
    }
    return i18n.parentCameraSummarySave;
  }

  static String _formatDuration(int seconds) {
    final minutes = seconds ~/ 60;
    final remainingSeconds = seconds % 60;
    final minuteText = minutes.toString().padLeft(1, '0');
    final secondText = remainingSeconds.toString().padLeft(2, '0');
    return '$minuteText:$secondText';
  }
}

class _SummaryMetric extends StatelessWidget {
  const _SummaryMetric({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: theme.textTheme.bodySmall?.copyWith(
            color: const Color(0x99FFFFFF),
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          value,
          style: theme.textTheme.titleLarge?.copyWith(
            color: Colors.white,
            fontWeight: FontWeight.w800,
          ),
        ),
      ],
    );
  }
}

class _CameraBackground extends StatelessWidget {
  const _CameraBackground({required this.device, required this.isInitializing});

  final ParentCameraDevice? device;
  final bool isInitializing;

  @override
  Widget build(BuildContext context) {
    final previewDevice = device;
    if (previewDevice == null || !previewDevice.isInitialized) {
      return DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            colors: [Color(0xFF3C403D), Color(0xFF181818)],
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
          ),
        ),
        child: Center(
          child: isInitializing
              ? const CircularProgressIndicator(color: Colors.white)
              : const Icon(
                  Icons.videocam_off_rounded,
                  color: Colors.white70,
                  size: 52,
                ),
        ),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final previewSize = previewDevice.previewSize;
        final viewWidth = constraints.maxWidth;
        final viewHeight = constraints.maxHeight;
        if (previewSize == null || viewWidth <= 0 || viewHeight <= 0) {
          return previewDevice.buildPreview();
        }

        final rotatedPreviewWidth = previewSize.height;
        final rotatedPreviewHeight = previewSize.width;
        final scale = math.max(
          viewWidth / rotatedPreviewWidth,
          viewHeight / rotatedPreviewHeight,
        );
        final fittedWidth = rotatedPreviewWidth * scale;
        final fittedHeight = rotatedPreviewHeight * scale;

        return ClipRect(
          child: OverflowBox(
            alignment: Alignment.center,
            minWidth: fittedWidth,
            maxWidth: fittedWidth,
            minHeight: fittedHeight,
            maxHeight: fittedHeight,
            child: SizedBox(
              width: fittedWidth,
              height: fittedHeight,
              child: previewDevice.buildPreview(),
            ),
          ),
        );
      },
    );
  }
}

class _FocusFrame extends StatelessWidget {
  const _FocusFrame({
    required this.maxWidth,
    required this.maxHeight,
    required this.showPersonFrame,
  });

  final double maxWidth;
  final double maxHeight;
  final bool showPersonFrame;

  @override
  Widget build(BuildContext context) {
    const frameAspectRatio = 316 / 560;
    final allowedWidth = math.min(maxWidth * 0.94, 316.0);
    var frameHeight = math.min(maxHeight * 0.94, 560.0);
    var frameWidth = frameHeight * frameAspectRatio;
    if (frameWidth > allowedWidth) {
      frameWidth = allowedWidth;
      frameHeight = frameWidth / frameAspectRatio;
    }

    return IgnorePointer(
      child: SizedBox(
        width: frameWidth,
        height: frameHeight,
        child: Stack(
          children: [
            if (showPersonFrame)
              Positioned.fill(
                child: Padding(
                  padding: EdgeInsets.symmetric(
                    horizontal: frameWidth * 0.12,
                    vertical: frameHeight * 0.06,
                  ),
                  child: Image.asset(
                    'assets/images/parent_frame.png',
                    fit: BoxFit.contain,
                    opacity: const AlwaysStoppedAnimation(0.45),
                  ),
                ),
              ),
            const Positioned(top: 0, left: 0, child: _FrameCorner()),
            const Positioned(
              top: 0,
              right: 0,
              child: _FrameCorner(rotationQuarterTurns: 1),
            ),
            const Positioned(
              bottom: 0,
              right: 0,
              child: _FrameCorner(rotationQuarterTurns: 2),
            ),
            const Positioned(
              bottom: 0,
              left: 0,
              child: _FrameCorner(rotationQuarterTurns: 3),
            ),
          ],
        ),
      ),
    );
  }
}

class _FrameCorner extends StatelessWidget {
  const _FrameCorner({this.rotationQuarterTurns = 0});

  final int rotationQuarterTurns;

  @override
  Widget build(BuildContext context) {
    return RotatedBox(
      quarterTurns: rotationQuarterTurns,
      child: SizedBox(
        width: 58,
        height: 58,
        child: CustomPaint(painter: _FrameCornerPainter()),
      ),
    );
  }
}

class _FrameCornerPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0xF5FFFFFF)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 6
      ..strokeCap = StrokeCap.round;
    final path = Path()
      ..moveTo(size.width * 0.78, 3)
      ..lineTo(18, 3)
      ..quadraticBezierTo(3, 3, 3, 18)
      ..lineTo(3, size.height * 0.78);
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) {
    return false;
  }
}

class _InfoBadge extends StatelessWidget {
  const _InfoBadge({required this.value});

  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: const BoxConstraints(minWidth: 122),
      padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 18),
      decoration: BoxDecoration(
        color: const Color(0xCC111111),
        borderRadius: BorderRadius.circular(20),
        boxShadow: const [
          BoxShadow(
            color: Color(0x24000000),
            blurRadius: 14,
            offset: Offset(0, 8),
          ),
        ],
      ),
      child: Text(
        value,
        textAlign: TextAlign.center,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 36,
          fontWeight: FontWeight.w900,
          height: 1,
        ),
      ),
    );
  }
}

String _formatRecordCountdown(int seconds) {
  final minuteText = (seconds ~/ 60).toString();
  final secondText = (seconds % 60).toString().padLeft(2, '0');
  return '$minuteText:$secondText';
}
