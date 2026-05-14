package com.example.ptts.features.parent_camera.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTimelineTest {
    @Test
    fun stateAtZero_usesInitialCountdownAndCount() {
        val timeline = OverlayTimeline(
            listOf(
                OverlayFrameState(elapsedMs = 0L, remainingSeconds = 10, jumpCount = 0),
                OverlayFrameState(elapsedMs = 1_000L, remainingSeconds = 9, jumpCount = 1),
            ),
        )

        assertEquals(
            OverlayFrameState(elapsedMs = 0L, remainingSeconds = 10, jumpCount = 0),
            timeline.stateAt(0L),
        )
    }

    @Test
    fun stateBetweenSamples_usesPreviousState() {
        val timeline = OverlayTimeline(
            listOf(
                OverlayFrameState(elapsedMs = 0L, remainingSeconds = 10, jumpCount = 0),
                OverlayFrameState(elapsedMs = 1_000L, remainingSeconds = 9, jumpCount = 1),
                OverlayFrameState(elapsedMs = 2_000L, remainingSeconds = 8, jumpCount = 3),
            ),
        )

        assertEquals(
            OverlayFrameState(elapsedMs = 1_000L, remainingSeconds = 9, jumpCount = 1),
            timeline.stateAt(1_500L),
        )
    }

    @Test
    fun stateAfterLastSample_usesFinalScore() {
        val timeline = OverlayTimeline(
            listOf(
                OverlayFrameState(elapsedMs = 0L, remainingSeconds = 10, jumpCount = 0),
                OverlayFrameState(elapsedMs = 10_000L, remainingSeconds = 0, jumpCount = 24),
            ),
        )

        assertEquals(
            OverlayFrameState(elapsedMs = 10_000L, remainingSeconds = 0, jumpCount = 24),
            timeline.stateAt(12_000L),
        )
    }
}
