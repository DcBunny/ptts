package com.example.ptts.features.parent_camera.data

import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.CameraMotion
import com.example.ptts.features.parent_camera.domain.AutoLowLightState
import com.example.ptts.features.parent_camera.domain.FrameLightMetrics
import com.example.ptts.features.parent_camera.domain.JumpDiagnostic
import com.example.ptts.features.parent_camera.domain.JumpPhase
import com.example.ptts.features.parent_camera.domain.LightMeasurementRegion
import com.example.ptts.features.parent_camera.domain.PosePoint
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpSessionDiagnosticsTest {
    @Test
    fun exportIncludesPoseAndCaptureMetadata() {
        val recorder = JumpSessionDiagnosticRecorder(enabled = true)
        recorder.record(
            JumpDiagnostic(
                timestampMs = 100L,
                phase = JumpPhase.Grounded,
                count = 0,
                confirmedCount = 0,
                estimatedCount = 0,
                recovering = false,
                event = "sample",
                landmarks = mapOf(
                    BodyLandmark.LeftAnkle to PosePoint(0.4f, 0.9f, 0.62f),
                ),
                cameraMotion = CameraMotion(
                    offsetX = 0.01f,
                    available = true,
                    reliable = true,
                    magnitude = 0.01f,
                ),
                inputQuality = "Poor",
                bodySignal = "hip_single",
                footSignal = "left_ankle",
                sessionStage = "Recording",
                lightMetrics = FrameLightMetrics(
                    timestampMs = 100L,
                    meanLuma = 0.18f,
                    regionMeanLuma = 0.12f,
                    darkPixelRatio = 0.75f,
                    overexposedRatio = 0.01f,
                    region = LightMeasurementRegion.Person,
                    regionCenterX = 0.5f,
                    regionCenterY = 0.45f,
                    sampleCount = 120,
                ),
                autoLowLightState = AutoLowLightState.Enhanced,
                autoLowLightLevel = 1,
                autoLowLightReason = "exposure",
            ),
        )

        val output = File.createTempFile("jump-diagnostics-test-", ".txt")
        try {
            recorder.exportTo(output)
            val line = output.readText()
            assertTrue(line.contains("Recording"))
            assertTrue(line.contains("Poor"))
            assertTrue(line.contains("hip_single"))
            assertTrue(line.contains("left_ankle"))
            assertTrue(line.contains("LeftAnkle,0.4,0.9,0.62"))

            val replayFrames = JumpFrameReplay().readDiagnosticFile(output)
            assertTrue(replayFrames.single().landmarks.containsKey(BodyLandmark.LeftAnkle))
            assertTrue(replayFrames.single().cameraMotion.available)
            assertTrue(replayFrames.single().cameraMotion.reliable)
            assertTrue(replayFrames.single().sessionStage == "Recording")
            assertTrue(replayFrames.single().lightMetrics?.region == LightMeasurementRegion.Person)
            assertTrue(replayFrames.single().autoLowLightState == AutoLowLightState.Enhanced)
            assertTrue(replayFrames.single().autoLowLightLevel == 1)
            assertTrue(JumpFrameReplay().runDiagnosticFile(output).finalResult != null)
        } finally {
            output.delete()
        }
    }
}
