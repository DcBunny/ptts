package com.example.ptts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.ptts", appContext.packageName)
    }

    @Test
    fun homeScreen_displaysCoreUi() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.app_name))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.best_record))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.best_record_empty))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.parent_photo))
            .assertIsDisplayed()
    }

    @Test
    fun durationButtons_respectStepAndMinDuration() {
        val durationDescription = composeRule.activity.getString(R.string.duration_display)
        val decreaseDescription = composeRule.activity.getString(R.string.duration_decrease)
        val increaseDescription = composeRule.activity.getString(R.string.duration_increase)

        composeRule.onNodeWithContentDescription(durationDescription)
            .assertTextEquals("1:00")

        composeRule.onNodeWithContentDescription(decreaseDescription).performClick()
        composeRule.onNodeWithContentDescription(durationDescription)
            .assertTextEquals("0:30")

        composeRule.onNodeWithContentDescription(decreaseDescription).performClick()
        composeRule.onNodeWithContentDescription(durationDescription)
            .assertTextEquals("0:10")
        composeRule.onNodeWithContentDescription(decreaseDescription)
            .assertIsNotEnabled()

        composeRule.onNodeWithContentDescription(increaseDescription).performClick()
        composeRule.onNodeWithContentDescription(durationDescription)
            .assertTextEquals("0:40")
    }

    @Test
    fun parentCamera_navigatesFromHomeAndExitsBack() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.parent_photo))
            .performClick()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.parent_camera_guide))
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.parent_camera_exit),
        ).performClick()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.app_name))
            .assertIsDisplayed()
    }
}
