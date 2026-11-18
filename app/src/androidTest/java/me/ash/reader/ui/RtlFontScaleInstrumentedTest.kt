package me.ash.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Wave H (D17 / UI-CONTRACT §8.3): chrome mirrors under RTL, and 200% font
 * scale keeps >=48dp dp targets while labels grow.
 */
class RtlFontScaleInstrumentedTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Composable
    private fun SampleSurface(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface) { content() }
        }
    }

    @Test
    fun chromeMirrorsUnderRtl() {
        composeTestRule.setContent {
            SampleSurface {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Row(
                        Modifier.fillMaxWidth().testTag("chrome_row"),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            modifier =
                                Modifier.padding(start = 16.dp).testTag("chrome_icon"),
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val rowBounds =
            composeTestRule.onNodeWithTag("chrome_row").fetchSemanticsNode().boundsInRoot
        val iconBounds =
            composeTestRule.onNodeWithTag("chrome_icon").fetchSemanticsNode().boundsInRoot
        val rowCenterX = (rowBounds.left + rowBounds.right) / 2f

        // Under RTL, the start-padded chrome icon lands on the right half.
        assertTrue(
            "icon should mirror to the right half under RTL (left=${iconBounds.left}, center=$rowCenterX)",
            iconBounds.left > rowCenterX,
        )
    }

    @Test
    fun chromeStaysStartAlignedUnderLtr() {
        composeTestRule.setContent {
            SampleSurface {
                Row(
                    Modifier.fillMaxWidth().testTag("chrome_row"),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.padding(start = 16.dp).testTag("chrome_icon"),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val rowBounds =
            composeTestRule.onNodeWithTag("chrome_row").fetchSemanticsNode().boundsInRoot
        val iconBounds =
            composeTestRule.onNodeWithTag("chrome_icon").fetchSemanticsNode().boundsInRoot
        val rowCenterX = (rowBounds.left + rowBounds.right) / 2f

        assertTrue(
            "icon should stay on the left half under Ltr (left=${iconBounds.left}, center=$rowCenterX)",
            iconBounds.left < rowCenterX,
        )
    }

    @Test
    fun fontScale2xSmokeKeepsDpTargets() {
        composeTestRule.setContent {
            val base = LocalDensity.current
            SampleSurface {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 2f)
                ) {
                    FilterChip(
                        modifier =
                            Modifier.width(200.dp)
                                .padding(top = 48.dp)
                                .testTag("scope_chip_2x"),
                        selected = false,
                        onClick = {},
                        label = { Text("Recently read with a long wrapping label") },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scope_chip_2x").assertIsDisplayed()
        val bounds =
            composeTestRule.onNodeWithTag("scope_chip_2x").fetchSemanticsNode().boundsInRoot
        val heightDp = bounds.height / composeTestRule.density.density
        assertTrue("chip height ${heightDp}dp < 48 at 200% font", heightDp >= 48f)
    }
}
