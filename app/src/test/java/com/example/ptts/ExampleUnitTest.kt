package com.example.ptts

import com.example.ptts.features.jump_session.presentation.formatDuration
import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun formatDuration_formatsMinutesAndSeconds() {
        assertEquals("1:00", formatDuration(60))
        assertEquals("0:10", formatDuration(10))
        assertEquals("2:05", formatDuration(125))
    }
}
