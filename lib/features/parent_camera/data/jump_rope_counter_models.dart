import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_models.dart';

class JumpRopeCounterUpdate {
  const JumpRopeCounterUpdate({
    required this.jumpCount,
    required this.analysisFps,
    required this.analysisLatencyMs,
    required this.statusKey,
    required this.newJumpEventMillis,
  });

  final int jumpCount;
  final double analysisFps;
  final int analysisLatencyMs;
  final String statusKey;
  final int? newJumpEventMillis;
}

class JumpRopeCounterSessionResult {
  const JumpRopeCounterSessionResult({
    required this.jumpCount,
    required this.jumpEventMillis,
    required this.analysisFps,
    required this.analysisLatencyMs,
    required this.statusKey,
    required this.correctionApplied,
  });

  final int jumpCount;
  final List<int> jumpEventMillis;
  final double analysisFps;
  final int analysisLatencyMs;
  final String statusKey;
  final bool correctionApplied;
}

abstract class JumpRopeTemporalEnhancer {
  const JumpRopeTemporalEnhancer();

  JumpRopeCounterSessionResult enhance({
    required JumpRopeTemporalEnhancementContext context,
  });
}

class JumpRopeTemporalEnhancementContext {
  const JumpRopeTemporalEnhancementContext({
    required this.samples,
    required this.onlineResult,
    required this.correctedResult,
    required this.selectedResult,
  });

  final List<JumpRopePoseFrameResult> samples;
  final JumpRopeCounterSessionResult onlineResult;
  final JumpRopeCounterSessionResult correctedResult;
  final JumpRopeCounterSessionResult selectedResult;
}

class NoopJumpRopeTemporalEnhancer implements JumpRopeTemporalEnhancer {
  const NoopJumpRopeTemporalEnhancer();

  @override
  JumpRopeCounterSessionResult enhance({
    required JumpRopeTemporalEnhancementContext context,
  }) {
    return context.selectedResult;
  }
}
