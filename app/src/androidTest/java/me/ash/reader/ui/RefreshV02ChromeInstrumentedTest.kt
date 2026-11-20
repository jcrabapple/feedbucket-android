package me.ash.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.infrastructure.android.TextToSpeechManager
import me.ash.reader.ui.ext.formatAsString
import me.ash.reader.ui.page.home.feeds.GroupExpandButton
import me.ash.reader.ui.page.home.reading.BottomBar
import me.ash.reader.ui.page.home.reading.Metadata
import me.ash.reader.ui.page.home.reading.tts.TtsButton
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Date
import kotlin.math.abs

/**
 * REFRESH-v0.2 sections 3-5 instrumented gates: 48dp touch bounds on the
 * group expand wrapper and the TTS button (idle + reading), the reader
 * bottom-bar active pill across Unread / Starred / Full Content / TTS
 * Reading (absent when inactive or extractFailed), and the reader Metadata
 * eyebrow + heading semantics + accent rule.
 */
class RefreshV02ChromeInstrumentedTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val primaryArgb = FeedBucketColorSchemes.Light.primary.toArgb()
    private val primaryContainerArgb = FeedBucketColorSchemes.Light.primaryContainer.toArgb()
    private val onPrimaryContainerArgb = FeedBucketColorSchemes.Light.onPrimaryContainer.toArgb()
    private val surfaceArgb = FeedBucketColorSchemes.Light.surface.toArgb()

    @Composable
    private fun SampleSurface(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun pixelAtDp(xDp: Float, yDp: Float): Int {
        val image = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val d = composeTestRule.density.density
        val x = (xDp * d).toInt().coerceIn(0, image.width - 1)
        val y = (yDp * d).toInt().coerceIn(0, image.height - 1)
        return image.getPixel(x, y)
    }

    private fun assertColorClose(actual: Int, expected: Int, message: String, tolerance: Int = 4) {
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (actual shr shift) and 0xFF
            val e = (expected shr shift) and 0xFF
            assertTrue(
                "$message: actual=0x${Integer.toHexString(actual)} expected=0x${Integer.toHexString(expected)}",
                abs(a - e) <= tolerance,
            )
        }
    }

    private fun dpBounds(tag: String): androidx.compose.ui.geometry.Rect {
        val b = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val d = composeTestRule.density.density
        return androidx.compose.ui.geometry.Rect(b.left / d, b.top / d, b.right / d, b.bottom / d)
    }

    @Test
    fun groupExpandButtonMeets48dpFloorAndToggles() {
        var clicked = false
        composeTestRule.setContent {
            SampleSurface {
                GroupExpandButton(
                    isExpanded = { false },
                    onExpanded = { clicked = true },
                    modifier = Modifier.testTag("expand_wrapper"),
                )
            }
        }
        composeTestRule.waitForIdle()

        val b = dpBounds("expand_wrapper")
        assertTrue("expand wrapper width ${b.width}dp < 48", b.width >= 48f)
        assertTrue("expand wrapper height ${b.height}dp < 48", b.height >= 48f)

        composeTestRule.onNodeWithContentDescription("Expand").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertTrue("expand wrapper tap did not fire onExpanded", clicked)
    }

    @Test
    fun ttsButtonTargetsAre48dpInBothStates() {
        composeTestRule.setContent {
            SampleSurface {
                androidx.compose.foundation.layout.Row {
                    TtsButton(
                        modifier = Modifier.testTag("tts_idle"),
                        onClick = {},
                        state = TextToSpeechManager.State.Idle,
                    )
                    TtsButton(
                        modifier = Modifier.testTag("tts_reading"),
                        onClick = {},
                        state = TextToSpeechManager.State.Reading(2, 4),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val idle = dpBounds("tts_idle")
        assertTrue("tts idle width ${idle.width}dp < 48", idle.width >= 48f)
        assertTrue("tts idle height ${idle.height}dp < 48", idle.height >= 48f)

        val reading = dpBounds("tts_reading")
        assertTrue("tts reading width ${reading.width}dp < 48", reading.width >= 48f)
        assertTrue("tts reading height ${reading.height}dp < 48", reading.height >= 48f)
    }

    @Test
    fun ttsReadingStateShowsPillRingAndStopColors() {
        composeTestRule.setContent {
            SampleSurface {
                TtsButton(
                    modifier = Modifier.testTag("tts_reading_px"),
                    onClick = {},
                    state = TextToSpeechManager.State.Reading(2, 4),
                )
            }
        }
        composeTestRule.waitForIdle()

        val b = dpBounds("tts_reading_px")
        val cx = b.center.x
        val cy = b.center.y
        // 36dp primaryContainer pill: sample inside the circle (radius 18dp)
        // but outside the 24dp ring (radius 12dp).
        assertColorClose(pixelAtDp(cx + 15f, cy), primaryContainerArgb, "tts pill ground")
        // Progress ring arc at 3 o'clock (50% progress covers the right half).
        assertColorClose(pixelAtDp(cx + 10.5f, cy), primaryArgb, "tts ring indicator")
        // Stop icon glyph center.
        assertColorClose(pixelAtDp(cx, cy), onPrimaryContainerArgb, "tts stop icon")
    }

    @Test
    fun bottomBarActivePillsFollowState() {
        composeTestRule.setContent {
            SampleSurface {
                BottomBar(
                    isShow = true,
                    isUnread = true,
                    isStarred = true,
                    isNextArticleAvailable = true,
                    isFullContent = true,
                    isBoldCharacters = false,
                    extractFailed = false,
                    ttsButton = {
                        TtsButton(onClick = {}, state = TextToSpeechManager.State.Idle)
                    },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Active buttons: pill ground 15dp right of the icon center (inside
        // the 36dp circle, outside the 24dp icon).
        for (desc in listOf("Mark as read", "Mark as unstarred", "Parse full content")) {
            val icon =
                composeTestRule.onNodeWithContentDescription(desc).fetchSemanticsNode().boundsInRoot
            val d = composeTestRule.density.density
            val cx = icon.center.x / d
            val cy = icon.center.y / d
            assertColorClose(pixelAtDp(cx + 15f, cy), primaryContainerArgb, "pill for $desc")
        }
    }

    @Test
    fun bottomBarInactiveAndExtractFailedShowNoPill() {
        composeTestRule.setContent {
            SampleSurface {
                BottomBar(
                    isShow = true,
                    isUnread = false,
                    isStarred = false,
                    isNextArticleAvailable = true,
                    isFullContent = true,
                    isBoldCharacters = false,
                    // E13: transient failure tint WITHOUT the pill.
                    extractFailed = true,
                    ttsButton = {
                        TtsButton(onClick = {}, state = TextToSpeechManager.State.Idle)
                    },
                )
            }
        }
        composeTestRule.waitForIdle()

        val d = composeTestRule.density.density
        for (desc in listOf("Mark as unread", "Mark as starred", "Parse full content")) {
            val icon =
                composeTestRule.onNodeWithContentDescription(desc).fetchSemanticsNode().boundsInRoot
            val cx = icon.center.x / d
            val cy = icon.center.y / d
            assertColorClose(pixelAtDp(cx + 15f, cy), surfaceArgb, "no pill for $desc")
        }
        // Stable contentDescription through the failure flash (E13).
        composeTestRule.onNodeWithContentDescription("Parse full content").assertIsDisplayed()
    }

    @Test
    fun metadataEyebrowHeadingAndAccentRule() {
        val published = Date(1654053729000L)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedDate = published.formatAsString(context, atHourMinute = true)

        composeTestRule.setContent {
            SampleSurface {
                Metadata(
                    feedName = "Example Feed",
                    title = "Article Title",
                    publishedDate = published,
                    modifier = Modifier.testTag("metadata"),
                    author = "Jane Doe",
                )
            }
        }
        composeTestRule.waitForIdle()

        // Eyebrow: feed name, nowrap separator, date.
        composeTestRule.onNodeWithText("Example Feed").assertIsDisplayed()
        composeTestRule.onNodeWithText(" · ").assertIsDisplayed()
        composeTestRule.onNodeWithText(expectedDate).assertIsDisplayed()
        // Heading semantics on the title.
        composeTestRule.onNodeWithText("Article Title").assert(isHeading())
        // Full-opacity author line.
        composeTestRule.onNodeWithText("Jane Doe").assertIsDisplayed()

        // Accent rule: 32x2dp primary bar 12dp below the author line
        // (author present: title -> 6dp -> author -> 12dp -> rule).
        val d = composeTestRule.density.density
        val title =
            composeTestRule.onNodeWithText("Article Title").fetchSemanticsNode().boundsInRoot
        val author =
            composeTestRule.onNodeWithText("Jane Doe").fetchSemanticsNode().boundsInRoot
        assertColorClose(
            pixelAtDp(title.left / d + 16f, author.bottom / d + 13f),
            primaryArgb,
            "accent rule center",
        )
    }

    @Test
    fun metadataReservesNoAuthorSpaceWhenAbsent() {
        composeTestRule.setContent {
            SampleSurface {
                Metadata(
                    feedName = "Example Feed",
                    title = "No Author Title",
                    publishedDate = Date(1654053729000L),
                    modifier = Modifier.testTag("metadata_no_author"),
                    author = null,
                )
            }
        }
        composeTestRule.waitForIdle()

        // Same 12dp gap + rule even without an author: no reserved spacer.
        val d = composeTestRule.density.density
        val title =
            composeTestRule.onNodeWithText("No Author Title").fetchSemanticsNode().boundsInRoot
        assertColorClose(
            pixelAtDp(title.left / d + 16f, title.bottom / d + 13f),
            primaryArgb,
            "accent rule without author",
        )
    }

    @Test
    fun metadataEyebrowPrecedesTitleInLayoutOrder() {
        composeTestRule.setContent {
            SampleSurface {
                Metadata(
                    feedName = "Eyebrow Feed",
                    title = "Ordered Title",
                    publishedDate = Date(1654053729000L),
                )
            }
        }
        composeTestRule.waitForIdle()
        val feed = composeTestRule.onNodeWithText("Eyebrow Feed").fetchSemanticsNode().boundsInRoot
        val title = composeTestRule.onNodeWithText("Ordered Title").fetchSemanticsNode().boundsInRoot
        assertTrue("eyebrow must sit above the title", feed.bottom <= title.top + 0.5f)
        assertEquals(feed.left, title.left, 0.5f)
    }
}
