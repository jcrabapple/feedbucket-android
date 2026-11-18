package me.ash.reader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.ui.ext.MotionExt
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Wave H (D17 / UI-CONTRACT §7): animator duration scale 0 is honored —
 * reduced motion is detected in composition and spatial motion is disabled.
 */
class MotionReducedInstrumentedTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun setAnimatorScale(scale: String) {
        instrumentation.uiAutomation.executeShellCommand(
            "settings put global ${MotionExt.ANIMATOR_DURATION_SCALE} $scale"
        )
        instrumentation.waitForIdleSync()
    }

    @Before
    fun setUp() {
        setAnimatorScale("0")
    }

    @After
    fun tearDown() {
        setAnimatorScale("1")
    }

    @Composable
    private fun SampleSurface(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface) { content() }
        }
    }

    @Test
    fun animatorScaleZeroIsDetectedInComposition() {
        val detected = mutableStateOf<Boolean?>(null)
        val scaleRead = mutableStateOf<Float?>(null)

        composeTestRule.setContent {
            SampleSurface {
                detected.value = MotionExt.rememberReducedMotion()
                scaleRead.value =
                    MotionExt.animatorDurationScale(
                        InstrumentationRegistry.getInstrumentation().targetContext
                    )
                Text("motion probe")
            }
        }
        composeTestRule.waitForIdle()

        assertTrue("animator scale readback should be 0.0, was ${scaleRead.value}", scaleRead.value == 0f)
        assertTrue("rememberReducedMotion should be true at scale 0", detected.value == true)
        assertFalse(MotionExt.useSpatialMotion(scaleRead.value))
        // Fade-only fallbacks build without spatial motion.
        MotionExt.fadeEnter()
        MotionExt.fadeExit()
    }

    @Test
    fun standardScaleKeepsSpatialMotion() {
        setAnimatorScale("1")
        val scaleRead = mutableStateOf<Float?>(null)

        composeTestRule.setContent {
            SampleSurface {
                scaleRead.value =
                    MotionExt.animatorDurationScale(
                        InstrumentationRegistry.getInstrumentation().targetContext
                    )
                Text("motion probe")
            }
        }
        composeTestRule.waitForIdle()

        assertTrue("animator scale readback should be 1.0, was ${scaleRead.value}", scaleRead.value == 1f)
        assertTrue(MotionExt.useSpatialMotion(scaleRead.value))
    }
}
