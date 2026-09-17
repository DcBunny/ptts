package com.example.ptts.features.parent_camera.data

import com.example.ptts.features.parent_camera.domain.JumpDiagnostic
import java.io.File

/** Optional, bounded diagnostic recorder used by debug/replay builds. */
class JumpSessionDiagnosticRecorder(
    private val enabled: Boolean = false,
    private val maxEvents: Int = 20_000,
) {
    private val events = ArrayDeque<JumpDiagnostic>()

    @Synchronized
    fun record(event: JumpDiagnostic) {
        if (!enabled) return
        if (events.size >= maxEvents) events.removeFirst()
        events.addLast(event)
    }

    @Synchronized
    fun snapshot(): List<JumpDiagnostic> = events.toList()

    @Synchronized
    fun clear() = events.clear()

    /** Writes a compact, line-oriented format suitable for offline replay tooling. */
    @Synchronized
    fun exportTo(file: File) {
        if (!enabled) return
        file.bufferedWriter().use { writer ->
            snapshot().forEach { event ->
                writer.append(event.timestampMs.toString())
                    .append('\t')
                    .append(event.phase.name)
                    .append('\t')
                    .append(event.count.toString())
                    .append('\t')
                    .append(event.confirmedCount.toString())
                    .append('\t')
                    .append(event.estimatedCount.toString())
                    .append('\t')
                    .append(event.recovering.toString())
                    .append('\t')
                    .appendLine(event.event)
            }
        }
    }
}
