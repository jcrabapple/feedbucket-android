package me.ash.reader.ui.page.home.reading

import androidx.compose.runtime.Immutable
import java.util.Date
import me.ash.reader.ui.page.adaptive.ReaderState

/**
 * Lane 4 defect 2 — the identity `ReadingPage`'s `AnimatedContent` keys on.
 *
 * Contains ONLY the fields that change the rendered article body. The
 * transient [ReaderState] flags (`isExtracting` / the ≤3 s `extractFailed`
 * tint), the prefetch neighbours (`nextArticle` / `previousArticle`) and
 * `listIndex` are deliberately EXCLUDED: keying the animation on the whole
 * [ReaderState] made every flag toggle re-run the slide/fade transition and
 * recreate `rememberScrollState()`, resetting the reader's scroll position.
 *
 * Kept in its own file so the key-stability contract is unit-testable on
 * the JVM without loading any composable (see ReaderBodyStateTest).
 */
@Immutable
internal data class ReaderBodyState(
    val articleId: String?,
    val feedName: String,
    val title: String?,
    val author: String?,
    val link: String?,
    val publishedDate: Date,
    val content: ReaderState.ContentState,
)

/** Body-identity snapshot of a [ReaderState]; equality drives the article transition. */
internal fun ReaderState.toBodyState(): ReaderBodyState =
    ReaderBodyState(
        articleId = articleId,
        feedName = feedName,
        title = title,
        author = author,
        link = link,
        publishedDate = publishedDate,
        content = content,
    )
