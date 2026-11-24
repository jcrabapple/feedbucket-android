package me.ash.reader.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.ash.reader.ui.component.markdown.MarkdownContent
import me.ash.reader.ui.page.home.reading.AiSummaryOverlayContent
import me.ash.reader.ui.page.home.reading.AiSummaryUiState
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import me.ash.reader.ui.theme.palette.LocalTonalPalettes
import me.ash.reader.ui.theme.palette.TonalPalettes
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSummaryOverlayInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun markdownContentRendersRichBlocksWithoutCrash() {
        val markdown = """
            # Summary Heading

            **TL;DR:** Key takeaway with `inlineCode`.

            - First bullet with [source](https://example.com)
            - Second bullet

            1. Ordered step

            > Highlighted quote

            ```
            val ok = true
            ```
        """.trimIndent()

        composeRule.setContent {
            MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
                CompositionLocalProvider(LocalTonalPalettes provides TonalPalettes(238.36, 15.0)) {
                    MarkdownContent(markdown = markdown)
                }
            }
        }

        composeRule.onNodeWithText("Summary Heading").assertIsDisplayed()
        composeRule.onNodeWithText("TL;DR: Key takeaway with inlineCode.").assertIsDisplayed()
        composeRule.onNodeWithText("First bullet with source").assertIsDisplayed()
        composeRule.onNodeWithText("Ordered step").assertIsDisplayed()
        composeRule.onNodeWithText("Highlighted quote").assertIsDisplayed()
        composeRule.onNodeWithText("val ok = true").assertIsDisplayed()
    }

    @Test
    fun aiSummaryOverlaySuccessStateRendersMarkdownAndActions() {
        val state = AiSummaryUiState.Success(
            summary = "## Overview\n\n- **Point one:** Ready\n- Point two",
            model = "gpt-4o-mini",
            fromCache = false,
        )

        composeRule.setContent {
            MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
                CompositionLocalProvider(LocalTonalPalettes provides TonalPalettes(238.36, 15.0)) {
                    AiSummaryOverlayContent(
                        state = state,
                        onNavigateToAiSettings = {},
                        onDismiss = {},
                        onRetry = {},
                        onRegenerate = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Overview").assertIsDisplayed()
        composeRule.onNodeWithText("Point one: Ready").assertIsDisplayed()
        composeRule.onNodeWithText("Generated with gpt-4o-mini").assertIsDisplayed()
        composeRule.onNodeWithText("Copy").assertIsDisplayed()
        composeRule.onNodeWithText("Regenerate").assertIsDisplayed()
    }

    @Test
    fun aiSummaryOverlayAllowsTallerHeightOnTallScreens() {
        val longSummary = (1..25).joinToString("\n\n") { idx ->
            "- **Takeaway $idx:** Detailed summary item explaining key context."
        }
        val state = AiSummaryUiState.Success(
            summary = longSummary,
            model = "gpt-4o-mini",
            fromCache = false,
        )
        val tallConfig = Configuration().apply { screenHeightDp = 900 }

        composeRule.setContent {
            MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
                CompositionLocalProvider(
                    LocalTonalPalettes provides TonalPalettes(238.36, 15.0),
                    LocalConfiguration provides tallConfig,
                ) {
                    AiSummaryOverlayContent(
                        state = state,
                        onNavigateToAiSettings = {},
                        onDismiss = {},
                        onRetry = {},
                        onRegenerate = {},
                    )
                }
            }
        }

        val bounds = composeRule.onNodeWithTag("ai_summary_modal_column").fetchSemanticsNode().boundsInRoot
        val heightDp = bounds.height / composeRule.density.density
        assertTrue("Expected modal height > 480dp on 900dp screen, was $heightDp", heightDp > 480f)
        assertTrue("Expected modal height <= 645dp on 900dp screen, was $heightDp", heightDp <= 645f)
    }
}
