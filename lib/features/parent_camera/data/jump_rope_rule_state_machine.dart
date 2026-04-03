class JumpRopeCounterConfig {
  const JumpRopeCounterConfig({
    required this.smoothingAlpha,
    required this.baselineAlpha,
    required this.highThreshold,
    required this.lowThreshold,
    required this.minPeakSignal,
    required this.landingDropThreshold,
    required this.minAirborneMs,
    required this.maxAirborneMs,
    required this.minJumpIntervalMs,
    required this.cooldownMs,
    required this.minAverageConfidence,
    required this.minStableFrames,
    required this.maxBaselineSignal,
    required this.minBodyScale,
    required this.hipWeight,
    required this.ankleWeight,
  });

  const JumpRopeCounterConfig.online()
    : this(
        smoothingAlpha: 0.35,
        baselineAlpha: 0.12,
        highThreshold: 0.014,
        lowThreshold: 0.006,
        minPeakSignal: 0.02,
        landingDropThreshold: 0.01,
        minAirborneMs: 140,
        maxAirborneMs: 1200,
        minJumpIntervalMs: 260,
        cooldownMs: 120,
        minAverageConfidence: 0.5,
        minStableFrames: 4,
        maxBaselineSignal: 0.008,
        minBodyScale: 0.12,
        hipWeight: 0.7,
        ankleWeight: 0.3,
      );

  const JumpRopeCounterConfig.offline()
    : this(
        smoothingAlpha: 0.24,
        baselineAlpha: 0.1,
        highThreshold: 0.012,
        lowThreshold: 0.005,
        minPeakSignal: 0.016,
        landingDropThreshold: 0.008,
        minAirborneMs: 120,
        maxAirborneMs: 1300,
        minJumpIntervalMs: 230,
        cooldownMs: 90,
        minAverageConfidence: 0.45,
        minStableFrames: 3,
        maxBaselineSignal: 0.007,
        minBodyScale: 0.12,
        hipWeight: 0.68,
        ankleWeight: 0.32,
      );

  final double smoothingAlpha;
  final double baselineAlpha;
  final double highThreshold;
  final double lowThreshold;
  final double minPeakSignal;
  final double landingDropThreshold;
  final int minAirborneMs;
  final int maxAirborneMs;
  final int minJumpIntervalMs;
  final int cooldownMs;
  final double minAverageConfidence;
  final int minStableFrames;
  final double maxBaselineSignal;
  final double minBodyScale;
  final double hipWeight;
  final double ankleWeight;
}

class JumpRopeSignalUpdate {
  const JumpRopeSignalUpdate({
    required this.timestampMs,
    required this.statusKey,
    required this.signal,
    required this.isReliableFrame,
    required this.canCount,
  });

  factory JumpRopeSignalUpdate.reliable({
    required int timestampMs,
    required String statusKey,
    required double signal,
    required bool canCount,
  }) {
    return JumpRopeSignalUpdate(
      timestampMs: timestampMs,
      statusKey: statusKey,
      signal: signal,
      isReliableFrame: true,
      canCount: canCount,
    );
  }

  factory JumpRopeSignalUpdate.unreliable({
    required int timestampMs,
    required String statusKey,
  }) {
    return JumpRopeSignalUpdate(
      timestampMs: timestampMs,
      statusKey: statusKey,
      signal: 0,
      isReliableFrame: false,
      canCount: false,
    );
  }

  final int timestampMs;
  final String statusKey;
  final double signal;
  final bool isReliableFrame;
  final bool canCount;
}

class JumpRopeRuleStateMachineUpdate {
  const JumpRopeRuleStateMachineUpdate({
    required this.jumpCount,
    this.newJumpEventMillis,
  });

  final int jumpCount;
  final int? newJumpEventMillis;
}

class JumpRopeRuleStateMachine {
  JumpRopeRuleStateMachine({required this.config});

  final JumpRopeCounterConfig config;
  final List<int> _jumpEventMillis = <int>[];

  _JumpRopePhase _phase = _JumpRopePhase.searching;
  int _jumpCount = 0;
  int? _lastJumpTimestampMs;
  int? _airborneStartedAtMs;
  int? _peakTimestampMs;
  double _peakSignal = 0;

  int get jumpCount => _jumpCount;

  List<int> get jumpEventMillis => _jumpEventMillis;

  void reset() {
    _jumpEventMillis.clear();
    _phase = _JumpRopePhase.searching;
    _jumpCount = 0;
    _lastJumpTimestampMs = null;
    _airborneStartedAtMs = null;
    _peakTimestampMs = null;
    _peakSignal = 0;
  }

  JumpRopeRuleStateMachineUpdate consume(JumpRopeSignalUpdate update) {
    if (!update.isReliableFrame) {
      _handleUnreliableFrame(update.timestampMs);
      return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }

    if (!update.canCount) {
      _phase = _JumpRopePhase.grounded;
      return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }

    switch (_phase) {
      case _JumpRopePhase.searching:
        _phase = _JumpRopePhase.grounded;
        return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
      case _JumpRopePhase.grounded:
        return _handleGroundedSignal(update.signal, update.timestampMs);
      case _JumpRopePhase.airborne:
        return _handleAirborneSignal(update.signal, update.timestampMs);
      case _JumpRopePhase.cooldown:
        _handleCooldownSignal(update.signal, update.timestampMs);
        return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }
  }

  void _handleUnreliableFrame(int timestampMs) {
    final airborneStartedAtMs = _airborneStartedAtMs;
    final shouldDropAirborne =
        _phase == _JumpRopePhase.airborne &&
        airborneStartedAtMs != null &&
        timestampMs - airborneStartedAtMs > config.maxAirborneMs;
    if (shouldDropAirborne) {
      _phase = _JumpRopePhase.searching;
      _airborneStartedAtMs = null;
      _peakTimestampMs = null;
      _peakSignal = 0;
      return;
    }
    if (_phase != _JumpRopePhase.cooldown) {
      _phase = _JumpRopePhase.searching;
    }
  }

  JumpRopeRuleStateMachineUpdate _handleGroundedSignal(
    double signal,
    int timestampMs,
  ) {
    if (signal < config.highThreshold) {
      return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }

    final lastJumpTimestampMs = _lastJumpTimestampMs;
    if (lastJumpTimestampMs != null &&
        timestampMs - lastJumpTimestampMs < config.minJumpIntervalMs) {
      return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }

    _phase = _JumpRopePhase.airborne;
    _airborneStartedAtMs = timestampMs;
    _peakTimestampMs = timestampMs;
    _peakSignal = signal;
    return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
  }

  JumpRopeRuleStateMachineUpdate _handleAirborneSignal(
    double signal,
    int timestampMs,
  ) {
    if (signal > _peakSignal) {
      _peakSignal = signal;
      _peakTimestampMs = timestampMs;
    }

    final airborneStartedAtMs = _airborneStartedAtMs;
    if (airborneStartedAtMs == null) {
      _phase = _JumpRopePhase.grounded;
      return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }

    if (timestampMs - airborneStartedAtMs > config.maxAirborneMs) {
      _phase = _JumpRopePhase.grounded;
      _peakSignal = 0;
      return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }

    final hasDroppedFromPeak =
        (_peakSignal - signal) >= config.landingDropThreshold;
    if (signal > config.lowThreshold && !hasDroppedFromPeak) {
      return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }

    final airborneDurationMs = timestampMs - airborneStartedAtMs;
    final isValidJump =
        airborneDurationMs >= config.minAirborneMs &&
        _peakSignal >= config.minPeakSignal;
    _phase = isValidJump ? _JumpRopePhase.cooldown : _JumpRopePhase.grounded;
    if (!isValidJump) {
      _peakSignal = 0;
      return JumpRopeRuleStateMachineUpdate(jumpCount: _jumpCount);
    }

    _jumpCount += 1;
    _lastJumpTimestampMs = timestampMs;
    final eventMillis = _peakTimestampMs ?? timestampMs;
    _jumpEventMillis.add(eventMillis);
    _peakSignal = 0;
    return JumpRopeRuleStateMachineUpdate(
      jumpCount: _jumpCount,
      newJumpEventMillis: eventMillis,
    );
  }

  void _handleCooldownSignal(double signal, int timestampMs) {
    final lastJumpTimestampMs = _lastJumpTimestampMs;
    if (lastJumpTimestampMs == null) {
      _phase = _JumpRopePhase.grounded;
      return;
    }

    final canLeaveCooldown =
        signal <= config.highThreshold &&
        timestampMs - lastJumpTimestampMs >= config.cooldownMs;
    if (canLeaveCooldown) {
      _phase = _JumpRopePhase.grounded;
    }
  }
}

enum _JumpRopePhase { searching, grounded, airborne, cooldown }
