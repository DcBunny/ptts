import 'dart:math' as math;

typedef SessionVideoOverlayTextResolver = String Function(String key);

enum SessionVideoOverlayPosition { center, topLeft, topRight, bottomCenter }

enum SessionVideoOverlayStyle { countdown, badge, subtitle }

class SessionVideoOverlayItem {
  const SessionVideoOverlayItem({
    required this.text,
    required this.startMs,
    required this.endMs,
    required this.position,
    required this.style,
  });

  final String text;
  final int startMs;
  final int endMs;
  final SessionVideoOverlayPosition position;
  final SessionVideoOverlayStyle style;

  Map<String, Object> toMap() {
    return <String, Object>{
      'text': text,
      'startMs': startMs,
      'endMs': endMs,
      'position': position.name,
      'style': style.name,
    };
  }
}

class SessionVideoOverlayTimelineBuilder {
  const SessionVideoOverlayTimelineBuilder();

  List<SessionVideoOverlayItem> build({
    required int countdownSeconds,
    required int activeDurationSeconds,
    required List<int> jumpEventMillis,
    required SessionVideoOverlayTextResolver resolveText,
  }) {
    final activeStartMs = countdownSeconds * 1000;
    final totalDurationMs = math.max(
      activeStartMs + (activeDurationSeconds * 1000),
      activeStartMs,
    );
    final sortedJumpEvents = List<int>.from(jumpEventMillis)
      ..removeWhere((value) => value < activeStartMs || value > totalDurationMs)
      ..sort();

    return <SessionVideoOverlayItem>[
      ..._buildCountdownItems(countdownSeconds),
      ..._buildRecordTimeItems(
        activeStartMs: activeStartMs,
        totalDurationMs: totalDurationMs,
      ),
      ..._buildJumpCountItems(
        activeStartMs: activeStartMs,
        totalDurationMs: totalDurationMs,
        jumpEventMillis: sortedJumpEvents,
      ),
      ..._buildTechniqueItems(
        activeStartMs: activeStartMs,
        totalDurationMs: totalDurationMs,
        jumpEventMillis: sortedJumpEvents,
        resolveText: resolveText,
      ),
    ];
  }

  List<SessionVideoOverlayItem> _buildCountdownItems(int countdownSeconds) {
    final items = <SessionVideoOverlayItem>[];
    for (var second = 0; second < countdownSeconds; second++) {
      final value = countdownSeconds - second;
      items.add(
        SessionVideoOverlayItem(
          text: '$value',
          startMs: second * 1000,
          endMs: (second + 1) * 1000,
          position: SessionVideoOverlayPosition.center,
          style: SessionVideoOverlayStyle.countdown,
        ),
      );
    }
    return items;
  }

  List<SessionVideoOverlayItem> _buildRecordTimeItems({
    required int activeStartMs,
    required int totalDurationMs,
  }) {
    if (totalDurationMs <= activeStartMs) {
      return const <SessionVideoOverlayItem>[];
    }

    final items = <SessionVideoOverlayItem>[];
    final activeDurationSeconds = ((totalDurationMs - activeStartMs) / 1000)
        .ceil();

    for (var second = 0; second < activeDurationSeconds; second++) {
      final startMs = activeStartMs + (second * 1000);
      final endMs = math.min(startMs + 1000, totalDurationMs);
      items.add(
        SessionVideoOverlayItem(
          text: _formatRecordCountdown(activeDurationSeconds - second),
          startMs: startMs,
          endMs: endMs,
          position: SessionVideoOverlayPosition.topLeft,
          style: SessionVideoOverlayStyle.badge,
        ),
      );
    }

    return items;
  }

  List<SessionVideoOverlayItem> _buildJumpCountItems({
    required int activeStartMs,
    required int totalDurationMs,
    required List<int> jumpEventMillis,
  }) {
    if (totalDurationMs <= activeStartMs) {
      return const <SessionVideoOverlayItem>[];
    }

    final items = <SessionVideoOverlayItem>[];
    var currentStartMs = activeStartMs;
    var currentCount = 0;

    for (final eventMs in jumpEventMillis) {
      if (eventMs > currentStartMs) {
        items.add(
          _makeJumpCountItem(
            count: currentCount,
            startMs: currentStartMs,
            endMs: eventMs,
          ),
        );
      }
      currentStartMs = eventMs;
      currentCount += 1;
    }

    if (totalDurationMs > currentStartMs) {
      items.add(
        _makeJumpCountItem(
          count: currentCount,
          startMs: currentStartMs,
          endMs: totalDurationMs,
        ),
      );
    }
    return items;
  }

  SessionVideoOverlayItem _makeJumpCountItem({
    required int count,
    required int startMs,
    required int endMs,
  }) {
    return SessionVideoOverlayItem(
      text: '$count',
      startMs: startMs,
      endMs: endMs,
      position: SessionVideoOverlayPosition.topRight,
      style: SessionVideoOverlayStyle.badge,
    );
  }

  List<SessionVideoOverlayItem> _buildTechniqueItems({
    required int activeStartMs,
    required int totalDurationMs,
    required List<int> jumpEventMillis,
    required SessionVideoOverlayTextResolver resolveText,
  }) {
    if (totalDurationMs <= activeStartMs) {
      return const <SessionVideoOverlayItem>[];
    }

    final items = <SessionVideoOverlayItem>[
      SessionVideoOverlayItem(
        text: resolveText('parentCameraOverlayStartJump'),
        startMs: activeStartMs,
        endMs: math.min(activeStartMs + 1400, totalDurationMs),
        position: SessionVideoOverlayPosition.bottomCenter,
        style: SessionVideoOverlayStyle.subtitle,
      ),
    ];

    for (var jumpIndex = 2; jumpIndex < jumpEventMillis.length; jumpIndex++) {
      final labelKey = _resolveTechniqueKey(jumpEventMillis, jumpIndex);
      if (labelKey == null) {
        continue;
      }

      final startMs = jumpEventMillis[jumpIndex];
      final nextStartMs = jumpIndex + 1 < jumpEventMillis.length
          ? jumpEventMillis[jumpIndex + 1]
          : totalDurationMs;
      final endMs = math.min(startMs + 1400, nextStartMs);
      if (endMs - startMs < 250) {
        continue;
      }

      items.add(
        SessionVideoOverlayItem(
          text: resolveText(labelKey),
          startMs: startMs,
          endMs: endMs,
          position: SessionVideoOverlayPosition.bottomCenter,
          style: SessionVideoOverlayStyle.subtitle,
        ),
      );
    }

    return _mergeSubtitleItems(items);
  }

  String? _resolveTechniqueKey(List<int> jumpEventMillis, int jumpIndex) {
    final intervals = <int>[];
    final startIndex = math.max(1, jumpIndex - 2);
    for (var index = startIndex; index <= jumpIndex; index++) {
      intervals.add(jumpEventMillis[index] - jumpEventMillis[index - 1]);
    }
    if (intervals.length < 2) {
      return null;
    }

    final latestInterval = intervals.last;
    final averageInterval =
        intervals.reduce((left, right) => left + right) / intervals.length;
    final spread = intervals.reduce(math.max) - intervals.reduce(math.min);

    if (intervals.length >= 3 && spread <= 140) {
      return 'parentCameraOverlayRhythmStable';
    }
    if (latestInterval < averageInterval * 0.82) {
      return 'parentCameraOverlayRhythmFast';
    }
    if (latestInterval > averageInterval * 1.18) {
      return 'parentCameraOverlayRhythmSlow';
    }
    return null;
  }

  List<SessionVideoOverlayItem> _mergeSubtitleItems(
    List<SessionVideoOverlayItem> items,
  ) {
    if (items.length < 2) {
      return items;
    }

    final merged = <SessionVideoOverlayItem>[items.first];
    for (final item in items.skip(1)) {
      final last = merged.last;
      final shouldMerge =
          last.style == SessionVideoOverlayStyle.subtitle &&
          item.style == SessionVideoOverlayStyle.subtitle &&
          last.text == item.text &&
          item.startMs - last.endMs <= 150;
      if (!shouldMerge) {
        merged.add(item);
        continue;
      }

      merged[merged.length - 1] = SessionVideoOverlayItem(
        text: last.text,
        startMs: last.startMs,
        endMs: math.max(last.endMs, item.endMs),
        position: last.position,
        style: last.style,
      );
    }
    return merged;
  }
}

String _formatRecordCountdown(int seconds) {
  final minuteText = (seconds ~/ 60).toString();
  final secondText = (seconds % 60).toString().padLeft(2, '0');
  return '$minuteText:$secondText';
}
