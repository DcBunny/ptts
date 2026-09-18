package com.example.ptts.features.parent_camera.data

import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.CameraMotion
import com.example.ptts.features.parent_camera.domain.AutoLowLightState
import com.example.ptts.features.parent_camera.domain.FrameLightMetrics
import com.example.ptts.features.parent_camera.domain.LightMeasurementRegion
import com.example.ptts.features.parent_camera.domain.JumpCounter
import com.example.ptts.features.parent_camera.domain.JumpCounterResult
import com.example.ptts.features.parent_camera.domain.PoseFrame
import com.example.ptts.features.parent_camera.domain.PosePoint
import java.io.File

/** Runs the production counter against a recorded landmark stream. */
class JumpFrameReplay(
    private val counterFactory: () -> JumpCounter = { JumpCounter() },
) {
    fun run(frames: Iterable<PoseFrame>): ReplayResult {
        val counter = counterFactory()
        val results = frames.map { counter.accept(it) }
        return ReplayResult(results = results, finalResult = results.lastOrNull())
    }

    /**
     * Reads the debug export produced by [JumpSessionDiagnosticRecorder] so a real session can
     * be replayed through the same counter without manually rebuilding synthetic frames.
     */
    fun readDiagnosticFile(file: File): List<PoseFrame> {
        return file.useLines { lines ->
            lines.mapNotNull(::parseDiagnosticLine).toList()
        }
    }

    /** Replays a full export, applying calibration frames before recording frames. */
    fun runDiagnosticFile(file: File): ReplayResult {
        val counter = counterFactory()
        val results = mutableListOf<JumpCounterResult>()
        var recordingStarted = false
        readDiagnosticFile(file).forEach { frame ->
            if (frame.sessionStage == "Recording") {
                if (!recordingStarted) {
                    counter.startSession()
                    recordingStarted = true
                }
                results += counter.accept(frame)
            } else if (!recordingStarted) {
                counter.calibrate(frame)
            }
        }
        return ReplayResult(results = results, finalResult = results.lastOrNull())
    }

    private fun parseDiagnosticLine(line: String): PoseFrame? {
        val fields = line.split('\t')
        // The first 23 fields are the original diagnostic columns. New replay metadata starts
        // at field 23; older exports are intentionally ignored because they have no raw pose.
        if (fields.size < ReplayMetadataStart + 10) return null
        val timestampMs = fields[0].toLongOrNull() ?: return null
        val motion = CameraMotion(
            offsetX = fields[27].toFloatOrNull() ?: 0f,
            offsetY = fields[28].toFloatOrNull() ?: 0f,
            magnitude = fields[29].toFloatOrNull() ?: 0f,
            available = fields[30].toBooleanStrictOrNull() ?: false,
            reliable = fields[31].toBooleanStrictOrNull() ?: false,
        )
        val landmarks = fields[32]
            .split(';')
            .mapNotNull { token ->
                val values = token.split(',')
                if (values.size != 4) return@mapNotNull null
                val landmark = runCatching { BodyLandmark.valueOf(values[0]) }.getOrNull() ?: return@mapNotNull null
                val x = values[1].toFloatOrNull() ?: return@mapNotNull null
                val y = values[2].toFloatOrNull() ?: return@mapNotNull null
                val confidence = values[3].toFloatOrNull() ?: return@mapNotNull null
                landmark to PosePoint(x = x, y = y, confidence = confidence)
            }
            .toMap()
        val lightMetrics = fields.getOrNull(33)?.split(',')?.let { values ->
            if (values.size != 12) return@let null
            FrameLightMetrics(
                timestampMs = values[0].toLongOrNull() ?: timestampMs,
                meanLuma = values[1].toFloatOrNull() ?: 0f,
                regionMeanLuma = values[2].toFloatOrNull() ?: 0f,
                darkPixelRatio = values[3].toFloatOrNull() ?: 0f,
                overexposedRatio = values[4].toFloatOrNull() ?: 0f,
                region = runCatching { LightMeasurementRegion.valueOf(values[5]) }
                    .getOrDefault(LightMeasurementRegion.Center),
                regionCenterX = values[6].toFloatOrNull() ?: 0.5f,
                regionCenterY = values[7].toFloatOrNull() ?: 0.5f,
                regionWidth = values[8].toFloatOrNull() ?: 0.5f,
                regionHeight = values[9].toFloatOrNull() ?: 0.7f,
                regionAgeMs = values[10].toLongOrNull() ?: 0L,
                sampleCount = values[11].toIntOrNull() ?: 0,
            )
        }
        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = landmarks,
            cameraMotion = motion,
            lightMetrics = lightMetrics,
            sessionStage = fields[23].ifEmpty { null },
            autoLowLightState = fields.getOrNull(34)?.takeIf { it.isNotEmpty() }?.let {
                runCatching { AutoLowLightState.valueOf(it) }.getOrNull()
            },
            autoLowLightLevel = fields.getOrNull(35)?.toIntOrNull(),
            autoLowLightReason = fields.getOrNull(36)?.ifEmpty { null },
        )
    }

    private companion object {
        const val ReplayMetadataStart = 23
    }
}

data class ReplayResult(
    val results: List<JumpCounterResult>,
    val finalResult: JumpCounterResult?,
)
