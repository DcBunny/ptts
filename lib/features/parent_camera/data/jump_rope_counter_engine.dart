import 'package:tiaosheng/features/parent_camera/data/jump_rope_counter_models.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_counter_processor.dart';
import 'package:tiaosheng/features/parent_camera/data/jump_rope_pose_models.dart';

export 'package:tiaosheng/features/parent_camera/data/jump_rope_counter_models.dart';

class JumpRopeCounterEngine {
  JumpRopeCounterEngine({JumpRopeTemporalEnhancer? temporalEnhancer})
    : _onlineProcessor = JumpRopeCounterProcessor.online(),
      _temporalEnhancer =
          temporalEnhancer ?? const NoopJumpRopeTemporalEnhancer();

  final JumpRopeCounterProcessor _onlineProcessor;
  final JumpRopeTemporalEnhancer _temporalEnhancer;
  final List<JumpRopePoseFrameResult> _samples = <JumpRopePoseFrameResult>[];

  void reset() {
    _samples.clear();
    _onlineProcessor.reset();
  }

  JumpRopeCounterUpdate ingest(JumpRopePoseFrameResult sample) {
    _samples.add(sample);
    return _onlineProcessor.consume(sample);
  }

  JumpRopeCounterSessionResult finalizeSession() {
    final offlineProcessor = JumpRopeCounterProcessor.offline();
    for (final sample in _samples) {
      offlineProcessor.consume(sample);
    }

    final correctedResult = offlineProcessor.buildResult(
      correctionApplied: true,
    );
    final onlineResult = _onlineProcessor.buildResult(correctionApplied: false);
    final selectedResult = _selectPreferredResult(
      onlineResult: onlineResult,
      correctedResult: correctedResult,
    );
    return _temporalEnhancer.enhance(
      context: JumpRopeTemporalEnhancementContext(
        samples: List<JumpRopePoseFrameResult>.unmodifiable(_samples),
        onlineResult: onlineResult,
        correctedResult: correctedResult,
        selectedResult: selectedResult,
      ),
    );
  }

  JumpRopeCounterSessionResult _selectPreferredResult({
    required JumpRopeCounterSessionResult onlineResult,
    required JumpRopeCounterSessionResult correctedResult,
  }) {
    if (correctedResult.jumpCount == 0 && onlineResult.jumpCount > 0) {
      return onlineResult;
    }

    final delta = (correctedResult.jumpCount - onlineResult.jumpCount).abs();
    if (delta > 3) {
      return onlineResult;
    }
    return correctedResult;
  }
}
