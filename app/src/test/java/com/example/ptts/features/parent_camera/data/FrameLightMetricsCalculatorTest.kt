package com.example.ptts.features.parent_camera.data

import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.LightMeasurementRegion
import com.example.ptts.features.parent_camera.domain.PosePoint
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLightMetricsCalculatorTest {
    @Test
    fun reliablePersonRegion_isUsedThenExpires() {
        val calculator = FrameLightMetricsCalculator(regionExpiryMs = 900L)
        val landmarks = mapOf(
            BodyLandmark.LeftShoulder to PosePoint(0.40f, 0.35f, 0.9f),
            BodyLandmark.RightShoulder to PosePoint(0.60f, 0.35f, 0.9f),
            BodyLandmark.LeftHip to PosePoint(0.43f, 0.58f, 0.9f),
            BodyLandmark.RightHip to PosePoint(0.57f, 0.58f, 0.9f),
        )
        calculator.updatePersonRegion(landmarks, timestampMs = 100L)

        val tracked = calculator.measure(plane(80), rotationDegrees = 0, timestampMs = 500L)
        val expired = calculator.measure(plane(80), rotationDegrees = 0, timestampMs = 1_001L)

        assertEquals(LightMeasurementRegion.Person, tracked.region)
        assertEquals(400L, tracked.regionAgeMs)
        assertEquals(LightMeasurementRegion.Center, expired.region)
    }

    @Test
    fun sampler_honorsStrideRotationAndReportsRatios() {
        val calculator = FrameLightMetricsCalculator()
        val buffer = ByteBuffer.allocate(8 * 4)
        for (index in 0 until buffer.capacity() step 2) {
            buffer.put(index, 255.toByte())
        }

        val metrics = calculator.measure(
            plane = LumaPlaneView(
                buffer = buffer,
                width = 2,
                height = 4,
                rowStride = 8,
                pixelStride = 2,
            ),
            rotationDegrees = 90,
            timestampMs = 1L,
        )

        assertTrue(metrics.regionMeanLuma > 0.9f)
        assertTrue(metrics.overexposedRatio > 0.9f)
        assertEquals(0f, metrics.darkPixelRatio, 0.001f)
    }

    private fun plane(value: Int): LumaPlaneView {
        val buffer = ByteBuffer.allocate(8 * 4)
        for (index in 0 until buffer.capacity() step 2) buffer.put(index, value.toByte())
        return LumaPlaneView(
            buffer = buffer,
            width = 4,
            height = 4,
            rowStride = 8,
            pixelStride = 2,
        )
    }
}
