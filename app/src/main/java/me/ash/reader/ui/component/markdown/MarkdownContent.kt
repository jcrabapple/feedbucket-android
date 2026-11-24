package me.ash.reader.ui.component.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.ash.reader.ui.component.reader.textLinkStyles

/**
 * Structured block elements produced by [parseMarkdownBlocks].
 */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class NumberedList(val items: List<NumberedListItem>) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data class CodeBlock(val code: String, val language: String? = null) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

data class NumberedListItem(
    val number: Int,
    val text: String,
)

private val HEADING_REGEX = Regex("^ {0,3}(#{1,6})\\s+(.*)$")
private val BULLET_REGEX = Regex("^\\s*[-*+]\\s+(.*)$")
private val NUMBERED_REGEX = Regex("^\\s*(\\d+)\\.\\s+(.*)$")
private val FENCE_START_REGEX = Regex("^ {0,3}```(.*)$")

/**
 * Parses raw Markdown text into a list of [MarkdownBlock] elements.
 * Pure Kotlin and JVM-testable; handles malformed or unclosed syntax gracefully.
 */
fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()

    val normalized = markdown.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalized.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }

        val fenceMatch = FENCE_START_REGEX.matchEntire(line)
        if (fenceMatch != null) {
            val language = fenceMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size) {
                val candidate = lines[i]
                if (candidate.trim().startsWith("```") && candidate.trim().drop(3).isBlank()) {
                    i++
                    break
                }
                codeLines.add(candidate)
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(code = codeLines.joinToString("\n"), language = language))
            continue
        }

        if (isHorizontalRule(line)) {
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        val headingMatch = HEADING_REGEX.matchEntire(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val rawText = stripTrailingHeadingHashes(headingMatch.groupValues[2].trim())
            if (rawText.isNotEmpty()) {
                blocks.add(MarkdownBlock.Heading(level = level, text = rawText))
            }
            i++
            continue
        }

        if (line.trimStart().startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                val afterMarker = lines[i].trimStart().removePrefix(">")
                quoteLines.add(if (afterMarker.startsWith(" ")) afterMarker.substring(1) else afterMarker)
                i++
            }
            val quoteText = quoteLines.joinToString("\n").trim()
            if (quoteText.isNotEmpty()) {
                blocks.add(MarkdownBlock.Blockquote(text = quoteText))
            }
            continue
        }

        val bulletMatch = BULLET_REGEX.matchEntire(line)
        if (bulletMatch != null) {
            val items = mutableListOf<String>()
            items.add(bulletMatch.groupValues[1].trim())
            i++
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) break
                val nextBullet = BULLET_REGEX.matchEntire(next)
                if (nextBullet != null) {
                    items.add(nextBullet.groupValues[1].trim())
                    i++
                    continue
                }
                if (isIndentedContinuation(next) && !isBlockBoundary(next)) {
                    items[items.lastIndex] = items.last() + " " + next.trim()
                    i++
                    continue
                }
                break
            }
            blocks.add(MarkdownBlock.BulletList(items = items))
            continue
        }

        val numberedMatch = NUMBERED_REGEX.matchEntire(line)
        if (numberedMatch != null) {
            val items = mutableListOf<NumberedListItem>()
            val firstNum = numberedMatch.groupValues[1].toIntOrNull() ?: 1
            items.add(NumberedListItem(number = firstNum, text = numberedMatch.groupValues[2].trim()))
            i++
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank()) break
                val nextNumMatch = NUMBERED_REGEX.matchEntire(next)
                if (nextNumMatch != null) {
                    val num = nextNumMatch.groupValues[1].toIntOrNull() ?: (items.last().number + 1)
                    items.add(NumberedListItem(number = num, text = nextNumMatch.groupValues[2].trim()))
                    i++
                    continue
                }
                if (isIndentedContinuation(next) && !isBlockBoundary(next)) {
                    val last = items.last()
                    items[items.lastIndex] = last.copy(text = last.text + " " + next.trim())
                    i++
                    continue
                }
                break
            }
            blocks.add(MarkdownBlock.NumberedList(items = items))
            continue
        }

        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val current = lines[i]
            if (current.isBlank() || (paraLines.isNotEmpty() && isBlockBoundary(current))) {
                break
            }
            paraLines.add(current)
            i++
        }
        val paragraphText = joinParagraphLines(paraLines)
        if (paragraphText.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(text = paragraphText))
        }
    }

    return blocks
}

private fun isHorizontalRule(line: String): Boolean {
    val compact = line.trim().filterNot { it == ' ' || it == '	' }
    if (compact.length < 3) return false
    val first = compact[0]
    return (first == '-' || first == '*' || first == '_') && compact.all { it == first }
}

private fun stripTrailingHeadingHashes(text: String): String {
    val trimmed = text.trimEnd()
    var end = trimmed.length
    while (end > 0 && trimmed[end - 1] == '#') {
        end--
    }
    return if (end < trimmed.length && (end == 0 || trimmed[end - 1].isWhitespace())) {
        trimmed.substring(0, end).trimEnd()
    } else {
        trimmed
    }
}

private fun isIndentedContinuation(line: String): Boolean =
    line.startsWith("  ") || line.startsWith("\t")

private fun isBlockBoundary(line: String): Boolean {
    return FENCE_START_REGEX.matches(line) ||
        isHorizontalRule(line) ||
        HEADING_REGEX.matches(line) ||
        line.trimStart().startsWith(">") ||
        BULLET_REGEX.matches(line) ||
        NUMBERED_REGEX.matches(line)
}

private fun joinParagraphLines(lines: List<String>): String {
    if (lines.isEmpty()) return ""
    val sb = StringBuilder()
    for (idx in lines.indices) {
        val raw = lines[idx]
        val cleaned = if (raw.endsWith("\\")) raw.dropLast(1).trimEnd() else raw.trim()
        if (idx > 0) {
            val prevHardBreak = lines[idx - 1].endsWith("  ") || lines[idx - 1].endsWith("\\")
            sb.append(if (prevHardBreak) '\n' else ' ')
        }
        sb.append(cleaned)
    }
    return sb.toString().trim()
}

/**
 * Builds an [AnnotatedString] with inline Markdown styles (bold, italic, bold+italic,
 * inline code, strikethrough, and clickable links).
 * Gracefully falls back to literal text for unclosed or malformed delimiters.
 */
fun buildMarkdownAnnotatedString(
    text: String,
    codeBackground: Color = Color.Unspecified,
    linkStyles: TextLinkStyles? = TextLinkStyles(
        style = SpanStyle(
            color = Color.Unspecified,
            textDecoration = TextDecoration.Underline,
        )
    ),
    linkInteractionListener: LinkInteractionListener? = null,
): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(
        builder = this,
        text = text,
        codeBackground = codeBackground,
        linkStyles = linkStyles,
        linkInteractionListener = linkInteractionListener,
    )
}

private fun appendInlineMarkdown(
    builder: AnnotatedString.Builder,
    text: String,
    codeBackground: Color,
    linkStyles: TextLinkStyles?,
    linkInteractionListener: LinkInteractionListener?,
) {
    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]

        // Escaped punctuation
        if (ch == '\\' && i + 1 < n && isEscapableMarkdownChar(text[i + 1])) {
            builder.append(text[i + 1])
            i += 2
            continue
        }

        // Inline code: `...` or ``...``
        if (ch == '`') {
            var tickCount = 0
            while (i + tickCount < n && text[i + tickCount] == '`') {
                tickCount++
            }
            val closeIdx = findClosingBackticks(text, i + tickCount, tickCount)
            if (closeIdx != -1) {
                val codeText = text.substring(i + tickCount, closeIdx)
                val start = builder.length
                builder.append(codeText)
                val end = builder.length
                builder.addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    ),
                    start = start,
                    end = end,
                )
                i = closeIdx + tickCount
                continue
            } else {
                repeat(tickCount) { builder.append('`') }
                i += tickCount
                continue
            }
        }

        // Link: [label](url)
        if (ch == '[') {
            val linkMatch = parseInlineLink(text, i)
            if (linkMatch != null) {
                val start = builder.length
                appendInlineMarkdown(
                    builder = builder,
                    text = linkMatch.label,
                    codeBackground = codeBackground,
                    linkStyles = linkStyles,
                    linkInteractionListener = linkInteractionListener,
                )
                val end = builder.length
                if (end > start && linkMatch.url.isNotEmpty()) {
                    builder.addLink(
                        LinkAnnotation.Url(
                            url = linkMatch.url,
                            styles = linkStyles,
                            linkInteractionListener = linkInteractionListener,
                        ),
                        start = start,
                        end = end,
                    )
                }
                i = linkMatch.nextIndex
                continue
            }
        }

        // Strikethrough: ~~text~~
        if (text.startsWith("~~", i)) {
            val closeIdx = findClosingDelimiter(text, i + 2, "~~", requireWordBoundary = false)
            if (closeIdx != -1 && closeIdx > i + 2) {
                val start = builder.length
                appendInlineMarkdown(
                    builder = builder,
                    text = text.substring(i + 2, closeIdx),
                    codeBackground = codeBackground,
                    linkStyles = linkStyles,
                    linkInteractionListener = linkInteractionListener,
                )
                val end = builder.length
                if (end > start) {
                    builder.addStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                        start = start,
                        end = end,
                    )
                }
                i = closeIdx + 2
                continue
            }
        }

        // Bold + Italic: ***text*** or ___text___
        if (text.startsWith("***", i) || text.startsWith("___", i)) {
            val delim = text.substring(i, i + 3)
            val wordBoundary = delim[0] == '_'
            if (canOpenDelimiter(text, i, delim.length, wordBoundary)) {
                val closeIdx = findClosingDelimiter(text, i + 3, delim, requireWordBoundary = wordBoundary)
                if (closeIdx != -1 && closeIdx > i + 3) {
                    val start = builder.length
                    appendInlineMarkdown(
                        builder = builder,
                        text = text.substring(i + 3, closeIdx),
                        codeBackground = codeBackground,
                        linkStyles = linkStyles,
                        linkInteractionListener = linkInteractionListener,
                    )
                    val end = builder.length
                    if (end > start) {
                        builder.addStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic,
                            ),
                            start = start,
                            end = end,
                        )
                    }
                    i = closeIdx + 3
                    continue
                }
            }
        }

        // Bold: **text** or __text__
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val delim = text.substring(i, i + 2)
            val wordBoundary = delim[0] == '_'
            if (canOpenDelimiter(text, i, delim.length, wordBoundary)) {
                val closeIdx = findClosingDelimiter(text, i + 2, delim, requireWordBoundary = wordBoundary)
                if (closeIdx != -1 && closeIdx > i + 2) {
                    val start = builder.length
                    appendInlineMarkdown(
                        builder = builder,
                        text = text.substring(i + 2, closeIdx),
                        codeBackground = codeBackground,
                        linkStyles = linkStyles,
                        linkInteractionListener = linkInteractionListener,
                    )
                    val end = builder.length
                    if (end > start) {
                        builder.addStyle(
                            SpanStyle(fontWeight = FontWeight.Bold),
                            start = start,
                            end = end,
                        )
                    }
                    i = closeIdx + 2
                    continue
                }
            }
        }

        // Italic: *text* or _text_
        if (ch == '*' || ch == '_') {
            val delim = ch.toString()
            val wordBoundary = ch == '_'
            if (canOpenDelimiter(text, i, 1, wordBoundary)) {
                val closeIdx = findClosingDelimiter(text, i + 1, delim, requireWordBoundary = wordBoundary)
                if (closeIdx != -1 && closeIdx > i + 1) {
                    val start = builder.length
                    appendInlineMarkdown(
                        builder = builder,
                        text = text.substring(i + 1, closeIdx),
                        codeBackground = codeBackground,
                        linkStyles = linkStyles,
                        linkInteractionListener = linkInteractionListener,
                    )
                    val end = builder.length
                    if (end > start) {
                        builder.addStyle(
                            SpanStyle(fontStyle = FontStyle.Italic),
                            start = start,
                            end = end,
                        )
                    }
                    i = closeIdx + 1
                    continue
                }
            }
        }

        builder.append(ch)
        i++
    }
}

private fun isEscapableMarkdownChar(ch: Char): Boolean =
    ch in "\\`*_{}[]()#+-.!~>"

private fun findClosingBackticks(text: String, startIndex: Int, tickCount: Int): Int {
    var pos = startIndex
    val n = text.length
    while (pos < n) {
        if (text[pos] == '`') {
            var run = 0
            while (pos + run < n && text[pos + run] == '`') {
                run++
            }
            if (run == tickCount) {
                return pos
            }
            pos += run
        } else {
            pos++
        }
    }
    return -1
}

private data class ParsedInlineLink(
    val label: String,
    val url: String,
    val nextIndex: Int,
)

private fun parseInlineLink(text: String, openBracketIndex: Int): ParsedInlineLink? {
    val n = text.length
    var pos = openBracketIndex + 1
    var bracketDepth = 1
    while (pos < n && bracketDepth > 0) {
        when (text[pos]) {
            '\\' -> pos += 2
            '`' -> {
                var run = 0
                while (pos + run < n && text[pos + run] == '`') run++
                val closeTicks = findClosingBackticks(text, pos + run, run)
                pos = if (closeTicks != -1) closeTicks + run else pos + run
            }
            '[' -> {
                bracketDepth++
                pos++
            }
            ']' -> {
                bracketDepth--
                if (bracketDepth == 0) break
                pos++
            }
            else -> pos++
        }
    }
    if (bracketDepth != 0 || pos >= n) return null
    val closeBracketIndex = pos
    val openParenIndex = closeBracketIndex + 1
    if (openParenIndex >= n || text[openParenIndex] != '(') return null

    var urlPos = openParenIndex + 1
    var parenDepth = 1
    while (urlPos < n && parenDepth > 0) {
        when (text[urlPos]) {
            '\\' -> urlPos += 2
            '(' -> {
                parenDepth++
                urlPos++
            }
            ')' -> {
                parenDepth--
                if (parenDepth == 0) break
                urlPos++
            }
            else -> urlPos++
        }
    }
    if (parenDepth != 0 || urlPos >= n) return null
    val label = text.substring(openBracketIndex + 1, closeBracketIndex)
    val url = text.substring(openParenIndex + 1, urlPos).trim()
    if (label.isEmpty() || url.isEmpty()) return null
    return ParsedInlineLink(label = label, url = url, nextIndex = urlPos + 1)
}

private fun canOpenDelimiter(
    text: String,
    index: Int,
    delimLength: Int,
    requireWordBoundary: Boolean,
): Boolean {
    val afterIndex = index + delimLength
    if (afterIndex >= text.length) return false
    if (text[afterIndex].isWhitespace()) return false
    if (requireWordBoundary && index > 0 && text[index - 1].isLetterOrDigit()) return false
    return true
}

private fun findClosingDelimiter(
    text: String,
    searchStart: Int,
    delim: String,
    requireWordBoundary: Boolean,
): Int {
    val n = text.length
    val delimLen = delim.length
    var pos = searchStart
    while (pos <= n - delimLen) {
        val ch = text[pos]
        if (ch == '\\' && pos + 1 < n) {
            pos += 2
            continue
        }
        if (ch == '`') {
            var run = 0
            while (pos + run < n && text[pos + run] == '`') run++
            val closeTicks = findClosingBackticks(text, pos + run, run)
            pos = if (closeTicks != -1) closeTicks + run else pos + run
            continue
        }
        if (text.startsWith(delim, pos)) {
            val markerChar = delim[0]
            var runLen = delimLen
            while (pos + runLen < n && text[pos + runLen] == markerChar) {
                runLen++
            }
            // Skip exact double-marker runs (e.g. **bold** inside *italic*) when searching for single * or _.
            if (delimLen == 1 && runLen == 2) {
                pos += runLen
                continue
            }
            // Match at the end of a longer run so nested spans like **bold *italic*** work.
            val candidatePos = pos + (runLen - delimLen)
            val beforeChar = text[candidatePos - 1]
            val afterIndex = candidatePos + delimLen
            val boundaryOk = !requireWordBoundary ||
                afterIndex >= n ||
                !text[afterIndex].isLetterOrDigit()
            if (!beforeChar.isWhitespace() && boundaryOk) {
                return candidatePos
            }
            pos += runLen
            continue
        }
        pos++
    }
    return -1
}

/**
 * Renders parsed Markdown blocks using Material 3 typography and color tokens.
 */
@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val colorScheme = MaterialTheme.colorScheme
    val codeBackground = colorScheme.surfaceContainerHighest
    val linkStyles = textLinkStyles()
    val uriHandler = LocalUriHandler.current
    val linkListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            if (url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true)
            ) {
                runCatching { uriHandler.openUri(url) }
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val headingStyle: TextStyle =
                        when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }.copy(
                            fontWeight = if (block.level == 1) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    val annotated = remember(block.text, codeBackground, linkStyles, linkListener) {
                        buildMarkdownAnnotatedString(
                            text = block.text,
                            codeBackground = codeBackground,
                            linkStyles = linkStyles,
                            linkInteractionListener = linkListener,
                        )
                    }
                    Text(
                        text = annotated,
                        style = headingStyle,
                        color = colorScheme.onSurface,
                        modifier =
                            Modifier
                                .padding(top = if (index > 0) 8.dp else 0.dp, bottom = 2.dp)
                                .semantics { heading() },
                    )
                }

                is MarkdownBlock.Paragraph -> {
                    val annotated = remember(block.text, codeBackground, linkStyles, linkListener) {
                        buildMarkdownAnnotatedString(
                            text = block.text,
                            codeBackground = codeBackground,
                            linkStyles = linkStyles,
                            linkInteractionListener = linkListener,
                        )
                    }
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorScheme.onSurface,
                    )
                }

                is MarkdownBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        block.items.forEach { item ->
                            val annotated = remember(item, codeBackground, linkStyles, linkListener) {
                                buildMarkdownAnnotatedString(
                                    text = item,
                                    codeBackground = codeBackground,
                                    linkStyles = linkStyles,
                                    linkInteractionListener = linkListener,
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 4.dp, end = 10.dp),
                                )
                                Text(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                is MarkdownBlock.NumberedList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        block.items.forEach { item ->
                            val annotated = remember(item.text, codeBackground, linkStyles, linkListener) {
                                buildMarkdownAnnotatedString(
                                    text = item.text,
                                    codeBackground = codeBackground,
                                    linkStyles = linkStyles,
                                    linkInteractionListener = linkListener,
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "${item.number}.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                                )
                                Text(
                                    text = annotated,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                is MarkdownBlock.Blockquote -> {
                    val annotated = remember(block.text, codeBackground, linkStyles, linkListener) {
                        buildMarkdownAnnotatedString(
                            text = block.text,
                            codeBackground = codeBackground,
                            linkStyles = linkStyles,
                            linkInteractionListener = linkListener,
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                        color = colorScheme.surfaceContainerLow,
                    ) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(colorScheme.primary),
                            )
                            Text(
                                text = annotated,
                                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = codeBackground,
                    ) {
                        Text(
                            text = block.code,
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            color = colorScheme.onSurface,
                            modifier =
                                Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                        )
                    }
                }

                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
