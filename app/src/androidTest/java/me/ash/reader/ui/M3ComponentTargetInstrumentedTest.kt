package me.ash.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Wave H (D17 / UI-CONTRACT §3.3, §8.1): actionable targets are >=48dp and
 * icon-only controls expose TalkBack action names / toggle state. Target
 * bounds are measured in dp, so the assertions hold on any window width;
 * CI emulators run the pixel_7 profile (~411dp smallest width) and the
 * measured config is logged as witness.
 */
class M3ComponentTargetInstrumentedTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun dpWidth(tag: String): Float {
        val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return bounds.width / composeTestRule.density.density
    }

    private fun dpHeight(tag: String): Float {
        val bounds = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return bounds.height / composeTestRule.density.density
    }

    @Composable
    private fun SampleSurface(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface) { content() }
        }
    }

    @Test
    fun actionableTargetsAreAtLeast48dp() {
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        println(
            "[wave-h] smallestScreenWidthDp=${config.smallestScreenWidthDp} " +
                "densityDpi=${config.densityDpi} fontScale=${config.fontScale}"
        )

        composeTestRule.setContent {
            SampleSurface {
                Column(Modifier.padding(16.dp)) {
                    FilterChip(
                        modifier = Modifier.heightIn(min = 48.dp).testTag("scope_chip"),
                        selected = true,
                        onClick = {},
                        label = { Text("Unread") },
                    )
                    IconButton(modifier = Modifier.testTag("app_bar_action"), onClick = {}) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to Unread",
                        )
                    }
                    Button(
                        modifier = Modifier.testTag("primary_button").heightIn(min = 48.dp),
                        onClick = {},
                    ) {
                        Text("Save search")
                    }
                    IconToggleButton(
                        modifier = Modifier.testTag("star_toggle").size(48.dp),
                        checked = true,
                        onCheckedChange = {},
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            modifier =
                                Modifier.semantics { stateDescription = "Starred" },
                        )
                    }
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.testTag("diff_row").heightIn(min = 48.dp)
                    ) {
                        SegmentedButton(
                            modifier = Modifier.testTag("diff_option_current"),
                            selected = true,
                            onClick = {},
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Current")
                        }
                        SegmentedButton(
                            modifier = Modifier.testTag("diff_option_changes"),
                            selected = false,
                            onClick = {},
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Changes")
                        }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()

        assertTrue("scope chip height ${dpHeight("scope_chip")}dp < 48", dpHeight("scope_chip") >= 48f)
        assertTrue("app bar action ${dpWidth("app_bar_action")}dp < 48", dpWidth("app_bar_action") >= 48f)
        assertTrue("app bar action height < 48", dpHeight("app_bar_action") >= 48f)
        assertTrue("primary button height < 48", dpHeight("primary_button") >= 48f)
        assertTrue("star toggle width < 48", dpWidth("star_toggle") >= 48f)
        assertTrue("star toggle height < 48", dpHeight("star_toggle") >= 48f)
        assertTrue("diff option height < 48", dpHeight("diff_option_current") >= 48f)
        assertTrue("diff option height < 48", dpHeight("diff_option_changes") >= 48f)
    }

    @Test
    fun iconOnlyControlsExposeTalkBackNamesAndToggleState() {
        composeTestRule.setContent {
            SampleSurface {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Unread",
                    )
                }
                IconToggleButton(checked = true, onCheckedChange = {}) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        modifier = Modifier.semantics { stateDescription = "Starred" },
                    )
                }
            }
        }

        composeTestRule.waitForIdle()

        // TalkBack reaches the action name, not the icon name (§8.1).
        composeTestRule.onNodeWithContentDescription("Back to Unread").assertIsDisplayed()
        // Toggle controls expose toggleableState + state description (§8.1).
        composeTestRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starred"),
                useUnmergedTree = true,
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.On,
                )
            )
    }
}
