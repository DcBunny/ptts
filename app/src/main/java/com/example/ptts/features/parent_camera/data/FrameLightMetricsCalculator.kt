package com.example.ptts.features.parent_camera.data

import com.example.ptts.features.parent_camera.domain.BodyLandmark
import com.example.ptts.features.parent_camera.domain.FrameLightMetrics
import com.example.ptts.features.parent_camera.domain.LightMeasurementRegion
import com.example.ptts.features.parent_camera.domain.PosePoint
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max

/** A view over the Y plane. The buffer is consumed synchronously before ImageProxy is closed. */
data class LumaPlaneView(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropWidth: Int = width,
    val cropHeight: Int = height,
)

/** Upright normalized rectangle used for measurement and CameraX AE metering. */
data class LightRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val source: LightMeasurementRegion,
    val ageMs: Long = 0L,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Low-cost luma sampler. It deliberately samples the Y plane rather than converting a full
 * camera frame to RGB. Rotation is applied when mapping the upright person rectangle back to the
 * sensor plane; row and pixel strides are honored for both packed and interleaved Y planes.
 */
class FrameLightMetricsCalculator(
    private val regionExpiryMs: Long = 900L,
    private val personConfidenceThreshold: Float = 0.40f,
    private val sampleColumns: Int = 20,
    private val sampleRows: Int = 14,
) {
    private var lastPersonRegion: LightRegion? = null
    private var lastPersonRegionTimestampMs: Long = 0L

    fun updatePersonRegion(landmarks: Map<BodyLandmark, PosePoint>, timestampMs: Long) {
        val points = listOf(
            landmarks[BodyLandmark.LeftShoulder],
            landmarks[BodyLandmark.RightShoulder],
            landmarks[BodyLandmark.LeftHip],
            landmarks[BodyLandmark.RightHip],
        ).filter { point ->
            point != null && point.confidence >= personConfidenceThreshold
        }.map { it!! }
        if (points.size < 2) return

        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
        val width = max(right - left, 0.12f)
        val height = max(bottom - top, 0.20f)
        val horizontalPadding = width * 0.55f
        val verticalPadding = height * 0.65f
        lastPersonRegion = LightRegion(
            left = (left - horizontalPadding).coerceIn(0f, 1f),
            top = (top - verticalPadding).coerceIn(0f, 1f),
            right = (right + horizontalPadding).coerceIn(0f, 1f),
            bottom = (bottom + verticalPadding).coerceIn(0f, 1f),
            source = LightMeasurementRegion.Person,
            ageMs = 0L,
        )
        lastPersonRegionTimestampMs = timestampMs
    }

    fun measure(
        plane: LumaPlaneView,
        rotationDegrees: Int,
        timestampMs: Long,
    ): FrameLightMetrics {
        val tracked = lastPersonRegion
            ?.takeIf { timestampMs - lastPersonRegionTimestampMs <= regionExpiryMs }
        val region = tracked?.copy(ageMs = (timestampMs - lastPersonRegionTimestampMs).coerceAtLeast(0L))
            ?: LightRegion(
                left = 0.25f,
                top = 0.12f,
                right = 0.75f,
                bottom = 0.92f,
                source = LightMeasurementRegion.Center,
            )

        val regionSamples = sampleRect(
            plane = plane,
            uprightRect = region,
            rotationDegrees = rotationDegrees,
            columns = sampleColumns,
            rows = sampleRows,
        )
        val frameSamples = sampleRect(
            plane = plane,
            uprightRect = LightRegion(
                left = 0f,
                top = 0f,
                right = 1f,
                bottom = 1f,
                source = LightMeasurementRegion.Center,
            ),
            rotationDegrees = rotationDegrees,
            columns = 12,
            rows = 8,
        )
        val regionCount = regionSamples.size.coerceAtLeast(1)
        val frameCount = frameSamples.size.coerceAtLeast(1)
        val regionMean = regionSamples.sum() / regionCount
        val frameMean = frameSamples.sum() / frameCount
        val darkRatio = regionSamples.count { it < DARK_LUMA }.toFloat() / regionCount
        val overexposedRatio = regionSamples.count { it > OVEREXPOSED_LUMA }.toFloat() / regionCount
        return FrameLightMetrics(
            timestampMs = timestampMs,
            meanLuma = frameMean,
            regionMeanLuma = regionMean,
            darkPixelRatio = darkRatio,
            overexposedRatio = overexposedRatio,
            region = region.source,
            regionCenterX = region.centerX,
            regionCenterY = region.centerY,
            regionWidth = region.width,
            regionHeight = region.height,
            regionAgeMs = region.ageMs,
            sampleCount = regionCount,
        )
    }

    fun reset() {
        lastPersonRegion = null
        lastPersonRegionTimestampMs = 0L
    }

    private fun sampleRect(
        plane: LumaPlaneView,
        uprightRect: LightRegion,
        rotationDegrees: Int,
        columns: Int,
        rows: Int,
    ): List<Float> {
        val samples = ArrayList<Float>(columns * rows)
        for (row in 0 until rows) {
            val uprightY = uprightRect.top + (row + 0.5f) / rows * uprightRect.height
            for (column in 0 until columns) {
                val uprightX = uprightRect.left + (column + 0.5f) / columns * uprightRect.width
                val (cropX, cropY) = uprightToRaw(uprightX, uprightY, rotationDegrees)
                val x = (plane.cropLeft + cropX * plane.cropWidth).toPixelIndex(plane.width)
                val y = (plane.cropTop + cropY * plane.cropHeight).toPixelIndex(plane.height)
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index in 0 until plane.buffer.limit()) {
                    samples += (plane.buffer.get(index).toInt() and 0xFF) / 255f
                }
            }
        }
        return samples
    }

    private fun uprightToRaw(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> {
        return when ((rotationDegrees % 360 + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
    }

    private fun Float.toPixelIndex(size: Int): Int {
        return floor((this.coerceIn(0f, 0.999999f) * size).toDouble())
            .toInt()
            .coerceIn(0, size - 1)
    }

    private companion object {
        const val DARK_LUMA = 0.12f
        const val OVEREXPOSED_LUMA = 0.94f
    }
}
