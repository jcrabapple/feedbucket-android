package me.ash.reader.ui.component.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private val testCodeBg = Color(0xFFE0E0E0)
    private val testLinkColor = Color(0xFF0061A4)
    private val testLinkStyles = TextLinkStyles(
        style = SpanStyle(
            color = testLinkColor,
            textDecoration = TextDecoration.Underline,
        )
    )

    @Test
    fun emptyAndWhitespaceInputsProduceNoBlocks() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdownBlocks(""))
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdownBlocks("   \n\t\n  "))
    } 

    @Test
    fun plainTextWithoutMarkdownProducesSingleParagraph() {
        val blocks = parseMarkdownBlocks("Just a plain summary without any formatting.")
        assertEquals(
            listOf(MarkdownBlock.Paragraph("Just a plain summary without any formatting.")),
            blocks,
        )
    }

    @Test
    fun parsesHeadingsH1H2H3AndH4WithExactLevels() {
        val md = """
            # Main Title
            ## Key Takeaways
            ### Subpoint
            #### Deeper Heading ##
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(
            listOf(
                MarkdownBlock.Heading(1, "Main Title"),
                MarkdownBlock.Heading(2, "Key Takeaways"),
                MarkdownBlock.Heading(3, "Subpoint"),
                MarkdownBlock.Heading(4, "Deeper Heading"),
            ),
            blocks,
        )
    }

    @Test
    fun hashtagWithoutSpaceRemainsParagraph() {
        val blocks = parseMarkdownBlocks("#AndroidDev release notes")
        assertEquals(
            listOf(MarkdownBlock.Paragraph("#AndroidDev release notes")),
            blocks,
        )
    }

    @Test
    fun parsesBulletListsWithDashAsteriskAndPlusMarkers() {
        val md = """
            - First point
            * Second **bold** point
            + Third point
              wrapped continuation
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        val list = blocks[0] as MarkdownBlock.BulletList
        assertEquals(
            listOf(
                "First point",
                "Second **bold** point",
                "Third point wrapped continuation",
            ),
            list.items,
        )
    }

    @Test
    fun parsesNumberedListsPreservingNumbers() {
        val md = """
            1. Step one
            2. Step two
            10. Step ten
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(
            listOf(
                MarkdownBlock.NumberedList(
                    listOf(
                        NumberedListItem(1, "Step one"),
                        NumberedListItem(2, "Step two"),
                        NumberedListItem(10, "Step ten"),
                    )
                )
            ),
            blocks,
        )
    }

    @Test
    fun separatesParagraphsAcrossMultipleBlankLines() {
        val md = "First paragraph line 1\nline 2\n\n\n\nSecond paragraph."
        val blocks = parseMarkdownBlocks(md)
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("First paragraph line 1 line 2"),
                MarkdownBlock.Paragraph("Second paragraph."),
            ),
            blocks,
        )
    }

    @Test
    fun parsesBlockquotesCodeBlocksAndHorizontalRules() {
        val md = """
            > First quote line
            > Second quote line

            ```kotlin
            val x = 1
            println(x)
            ```

            ---

            ***
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(
            listOf(
                MarkdownBlock.Blockquote("First quote line\nSecond quote line"),
                MarkdownBlock.CodeBlock(code = "val x = 1\nprintln(x)", language = "kotlin"),
                MarkdownBlock.HorizontalRule,
                MarkdownBlock.HorizontalRule,
            ),
            blocks,
        )
    }

    @Test
    fun unclosedCodeFenceRecoversWithoutDroppingCode() {
        val md = "```\nval unclosed = true\nval kept = 42"
        val blocks = parseMarkdownBlocks(md)
        assertEquals(
            listOf(MarkdownBlock.CodeBlock(code = "val unclosed = true\nval kept = 42", language = null)),
            blocks,
        )
    }

    @Test
    fun inlineBoldAndDoubleUnderscoreApplyBoldFontWeight() {
        val annotated = buildMarkdownAnnotatedString("Hello **world** and __earth__", codeBackground = testCodeBg)
        assertEquals("Hello world and earth", annotated.text)
        val boldSpans = annotated.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(2, boldSpans.size)
        assertEquals(6 to 11, boldSpans[0].start to boldSpans[0].end)
        assertEquals(16 to 21, boldSpans[1].start to boldSpans[1].end)
    }

    @Test
    fun inlineItalicAppliesItalicWhilePreservingSnakeCaseIdentifiers() {
        val annotated = buildMarkdownAnnotatedString("Use *emphasis* or _style_ on snake_case_var", codeBackground = testCodeBg)
        assertEquals("Use emphasis or style on snake_case_var", annotated.text)
        val italicSpans = annotated.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(2, italicSpans.size)
        assertEquals(4 to 12, italicSpans[0].start to italicSpans[0].end)
        assertEquals(16 to 21, italicSpans[1].start to italicSpans[1].end)
    }

    @Test
    fun inlineBoldItalicAndStrikethroughApplyCombinedStyles() {
        val annotated = buildMarkdownAnnotatedString("***urgent*** and ~~deprecated~~", codeBackground = testCodeBg)
        assertEquals("urgent and deprecated", annotated.text)
        val boldItalic = annotated.spanStyles.filter {
            it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic
        }
        assertEquals(1, boldItalic.size)
        assertEquals(0 to 6, boldItalic[0].start to boldItalic[0].end)

        val strike = annotated.spanStyles.filter { it.item.textDecoration == TextDecoration.LineThrough }
        assertEquals(1, strike.size)
        assertEquals(11 to 21, strike[0].start to strike[0].end)
    }

    @Test
    fun inlineCodeAppliesMonospaceAndBackgroundAndIgnoresInnerDelimiters() {
        val annotated = buildMarkdownAnnotatedString(
            text = "Call `*not_italic*` in code",
            codeBackground = testCodeBg,
        )
        assertEquals("Call *not_italic* in code", annotated.text)
        val codeSpans = annotated.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
        assertEquals(1, codeSpans.size)
        assertEquals(5 to 17, codeSpans[0].start to codeSpans[0].end)
        assertEquals(testCodeBg, codeSpans[0].item.background)
        assertTrue(annotated.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun inlineLinksAttachUrlAnnotationAndStyles() {
        val annotated = buildMarkdownAnnotatedString(
            text = "See [Kotlin docs](https://kotlinlang.org/docs_(v2)) now",
            codeBackground = testCodeBg,
            linkStyles = testLinkStyles,
        )
        assertEquals("See Kotlin docs now", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        assertEquals(4 to 15, links[0].start to links[0].end)
        val urlItem = links[0].item as LinkAnnotation.Url
        assertEquals("https://kotlinlang.org/docs_(v2)", urlItem.url)
        assertEquals(testLinkColor, urlItem.styles?.style?.color)
        assertEquals(TextDecoration.Underline, urlItem.styles?.style?.textDecoration)
    }

    @Test
    fun mixedInlineStylingInBulletItemParsesAllSpans() {
        val raw = "* **TL;DR:** The article explains `code` and [link](https://example.com)"
        val blocks = parseMarkdownBlocks(raw)
        assertEquals(1, blocks.size)
        val bullet = blocks[0] as MarkdownBlock.BulletList
        assertEquals(1, bullet.items.size)

        val annotated = buildMarkdownAnnotatedString(
            text = bullet.items[0],
            codeBackground = testCodeBg,
            linkStyles = testLinkStyles,
        )
        assertEquals("TL;DR: The article explains code and link", annotated.text)
        assertEquals(1, annotated.spanStyles.count { it.item.fontWeight == FontWeight.Bold })
        assertEquals(1, annotated.spanStyles.count { it.item.fontFamily == FontFamily.Monospace })
        assertEquals(1, annotated.getLinkAnnotations(0, annotated.length).size)
    }

    @Test
    fun nestedBoldAndItalicSpansPreserveOuterAndInnerStyles() {
        val annotated = buildMarkdownAnnotatedString("*italic **bold** tail* and **bold *inner***", codeBackground = testCodeBg)
        assertEquals("italic bold tail and bold inner", annotated.text)
        val boldSpans = annotated.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        val italicSpans = annotated.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertEquals(2, boldSpans.size)
        assertEquals(2, italicSpans.size)
    }

    @Test
    fun unclosedMarkersAndMalformedSyntaxRecoverGracefully() {
        val samples = listOf(
            "**unclosed bold",
            "*unclosed italic",
            "`unclosed code",
            "~~unclosed strike",
            "[unclosed](https://example.com",
            "[empty]()",
            "3 * 4 * 5 = 60",
        )
        for (sample in samples) {
            val blocks = parseMarkdownBlocks(sample)
            assertTrue("Expected non-empty blocks for: $sample", blocks.isNotEmpty())
            val annotated = buildMarkdownAnnotatedString(sample, codeBackground = testCodeBg)
            assertEquals(sample, annotated.text)
        }
    }

    @Test
    fun escapedPunctuationRendersLiterallyWithoutSpans() {
        val annotated = buildMarkdownAnnotatedString("\\*literal\\* and \\`ticks\\`", codeBackground = testCodeBg)
        assertEquals("*literal* and `ticks`", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }
}
