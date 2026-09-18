package com.example.ptts.features.parent_camera.data

import android.util.Log
import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.FrameLightMetrics
import com.example.ptts.features.parent_camera.domain.JumpDiagnostic
import com.example.ptts.features.parent_camera.domain.PosePoint
import java.io.File

/**
 * Optional, bounded diagnostic recorder used by debug/replay builds.
 *
 * Every frame is also persisted to [outputDir] when recording finishes, so a session's real
 * pose stream can be replayed offline. Without that loop the counter thresholds can only be
 * tuned by guessing.
 */
class JumpSessionDiagnosticRecorder(
    private val enabled: Boolean = false,
    private val maxEvents: Int = 20_000,
    private val outputDir: File? = null,
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

    /** Persists the captured session next to the app cache for offline replay. */
    @Synchronized
    fun exportForSession(label: Long) {
        if (!enabled) return
        val dir = outputDir ?: return
        if (events.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val file = File(dir, "jump_diagnostics_$label.txt")
            exportTo(file)
            pruneOldExports(dir)
        }.onFailure { error ->
            Log.w("JumpDiagnostics", "exportForSession failed", error)
        }
    }

    private fun pruneOldExports(dir: File) {
        val exports = dir.listFiles { file -> file.name.startsWith(EXPORT_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        exports.drop(MaxExportedSessions).forEach { it.delete() }
    }

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
                    .append(event.event)
                    .append('\t')
                    .append(event.sampleIntervalMs?.toString() ?: "")
                    .append('\t')
                    .append(event.rawLift?.toString() ?: "")
                    .append('\t')
                    .append(event.smoothedLift?.toString() ?: "")
                    .append('\t')
                    .append(event.peakLift?.toString() ?: "")
                    .append('\t')
                    .append(event.jumpDurationMs?.toString() ?: "")
                    .append('\t')
                    .append(event.rejectionReason ?: "")
                    .append('\t')
                    .append(event.recoveryStage ?: "")
                    .append('\t')
                    .append(event.recoveryBaselineBodyY?.toString() ?: "")
                    .append('\t')
                    .append(event.recoveryBaselineFootY?.toString() ?: "")
                    .append('\t')
                    .append(event.recoveryScale?.toString() ?: "")
                    .append('\t')
                    .append(event.recoveryCycleMs?.toString() ?: "")
                    .append('\t')
                    .append(event.recoveryValidPeakThreshold?.toString() ?: "")
                    .append('\t')
                    .append(event.recoveryMatchedEstimate.toString())
                    .append('\t')
                    .append(event.medianFrameIntervalMs?.toString() ?: "")
                    .append('\t')
                    .append(event.typicalWorstFrameIntervalMs?.toString() ?: "")
                    .append('\t')
                    .append(event.adaptivePeakLift?.toString() ?: "")
                    .append('\t')
                    .append(event.sessionStage ?: "")
                    .append('\t')
                    .append(event.inputQuality ?: "")
                    .append('\t')
                    .append(event.bodySignal ?: "")
                    .append('\t')
                    .append(event.footSignal ?: "")
                    .append('\t')
                    .append(event.cameraMotion.offsetX.toString())
                    .append('\t')
                    .append(event.cameraMotion.offsetY.toString())
                    .append('\t')
                    .append(event.cameraMotion.magnitude.toString())
                    .append('\t')
                    .append(event.cameraMotion.available.toString())
                    .append('\t')
                    .append(event.cameraMotion.reliable.toString())
                    .append('\t')
                    .append(serializeLandmarks(event.landmarks))
                    .append('\t')
                    .append(event.lightMetrics?.let(::serializeLightMetrics) ?: "")
                    .append('\t')
                    .append(event.autoLowLightState?.name ?: "")
                    .append('\t')
                    .append(event.autoLowLightLevel?.toString() ?: "")
                    .append('\t')
                    .appendLine(event.autoLowLightReason?.replace('\t', ' ') ?: "")
            }
        }
    }

    private fun serializeLandmarks(landmarks: Map<BodyLandmark, PosePoint>): String {
        return landmarks.entries
            .sortedBy { it.key.ordinal }
            .joinToString(";") { (landmark, point) ->
                "${landmark.name},${point.x},${point.y},${point.confidence}"
            }
    }

    private fun serializeLightMetrics(metrics: FrameLightMetrics): String {
        return listOf(
            metrics.timestampMs,
            metrics.meanLuma,
            metrics.regionMeanLuma,
            metrics.darkPixelRatio,
            metrics.overexposedRatio,
            metrics.region.name,
            metrics.regionCenterX,
            metrics.regionCenterY,
            metrics.regionWidth,
            metrics.regionHeight,
            metrics.regionAgeMs,
            metrics.sampleCount,
        ).joinToString(",")
    }

    private companion object {
        const val EXPORT_PREFIX = "jump_diagnostics_"
        const val MaxExportedSessions = 20
    }
}
