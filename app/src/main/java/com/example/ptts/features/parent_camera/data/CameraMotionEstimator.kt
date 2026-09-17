package com.example.ptts.features.parent_camera.data

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A small, allocation-bounded background matcher for hand-held capture.
 * It intentionally estimates translation only. When the background cannot
 * support a stable match, callers receive an unreliable estimate and should
 * keep the original landmarks.
 */
class CameraMotionEstimator(
    private val sampleWidth: Int = 48,
    private val sampleHeight: Int = 32,
) {
    private var previous: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0
    private var cumulativeX = 0f
    private var cumulativeY = 0f

    fun reset() {
        previous = null
        previousWidth = 0
        previousHeight = 0
        cumulativeX = 0f
        cumulativeY = 0f
    }

    fun estimate(image: ImageProxy): MotionEstimate {
        val current = readLuma(image)
        val old = previous
        val oldWidth = previousWidth
        val oldHeight = previousHeight
        previous = current.data
        previousWidth = current.width
        previousHeight = current.height
        if (old == null || current.width != oldWidth || current.height != oldHeight) {
            return MotionEstimate(cumulativeX, cumulativeY, reliable = false, magnitude = 0f)
        }

        var bestError = Float.MAX_VALUE
        var secondBest = Float.MAX_VALUE
        var bestDx = 0
        var bestDy = 0
        for (dy in -MaxSearchPixels..MaxSearchPixels) {
            for (dx in -MaxSearchPixels..MaxSearchPixels) {
                val error = matchError(old, current.data, current.width, current.height, dx, dy)
                if (error < bestError) {
                    secondBest = bestError
                    bestError = error
                    bestDx = dx
                    bestDy = dy
                } else if (error < secondBest) {
                    secondBest = error
                }
            }
        }

        val margin = (secondBest - bestError) / (secondBest + 1f)
        val reliable = bestError <= MaxMeanAbsoluteError && margin >= MinMatchMargin
        if (reliable) {
            cumulativeX = (cumulativeX + bestDx / current.width.toFloat()).coerceIn(-MaxCumulativeOffset, MaxCumulativeOffset)
            cumulativeY = (cumulativeY + bestDy / current.height.toFloat()).coerceIn(-MaxCumulativeOffset, MaxCumulativeOffset)
        }
        return MotionEstimate(
            offsetX = cumulativeX,
            offsetY = cumulativeY,
            reliable = reliable,
            magnitude = hypot(bestDx / current.width.toFloat(), bestDy / current.height.toFloat()),
        )
    }

    private fun matchError(
        previous: ByteArray,
        current: ByteArray,
        width: Int,
        height: Int,
        dx: Int,
        dy: Int,
    ): Float {
        var total = 0f
        var count = 0
        val border = 3
        for (y in border until height - border step 2) {
            for (x in border until width - border step 2) {
                // Border samples reduce contamination from the person in the center.
                if (x in width / 4..(width * 3 / 4) && y in height / 8..(height * 7 / 8)) continue
                val sourceX = x + dx
                val sourceY = y + dy
                if (sourceX !in 0 until width || sourceY !in 0 until height) continue
                total += abs(
                    (previous[y * width + x].toInt() and 0xFF) -
                        (current[sourceY * width + sourceX].toInt() and 0xFF),
                )
                count += 1
            }
        }
        return if (count == 0) Float.MAX_VALUE else total / count
    }

    private fun readLuma(image: ImageProxy): LumaFrame {
        val plane = image.planes.firstOrNull()
            ?: return LumaFrame(ByteArray(sampleWidth * sampleHeight), sampleWidth, sampleHeight)
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val result = ByteArray(sampleWidth * sampleHeight)
        for (y in 0 until sampleHeight) {
            val sourceY = (y * height / sampleHeight).coerceIn(0, height - 1)
            for (x in 0 until sampleWidth) {
                val sourceX = (x * width / sampleWidth).coerceIn(0, width - 1)
                val index = sourceY * plane.rowStride + sourceX * plane.pixelStride
                result[y * sampleWidth + x] = buffer.get(index)
            }
        }
        return LumaFrame(result, sampleWidth, sampleHeight)
    }

    data class MotionEstimate(
        val offsetX: Float,
        val offsetY: Float,
        val reliable: Boolean,
        val magnitude: Float,
    )

    private data class LumaFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val MaxSearchPixels = 4
        const val MaxMeanAbsoluteError = 24f
        const val MinMatchMargin = 0.015f
        const val MaxCumulativeOffset = 0.25f
    }
}
