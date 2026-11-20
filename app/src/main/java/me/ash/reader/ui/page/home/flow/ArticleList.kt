package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import me.ash.reader.domain.data.Diff
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed

/** Test seam for the REFRESH-v0.2 section 1.4 hairline dividers. */
const val FLOW_DIVIDER_TAG = "flow_divider"

/**
 * Hairline separator between two adjacent article rows (REFRESH-v0.2
 * section 1.4). Emitted inside the article's own lazy item, outside the
 * swipe box, so it stays put while a row is swiped and the item count (and
 * therefore index-based scrolling) is unchanged; never before a date header
 * or after the last article.
 */
@Composable
private fun ArticleDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp).testTag(FLOW_DIVIDER_TAG),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Suppress("FunctionName")
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.ArticleList(
    pagingItems: LazyPagingItems<ArticleFlowItem>,
    diffMap: Map<String, Diff>,
    isShowFeedIcon: Boolean,
    isShowStickyHeader: Boolean,
    articleListTonalElevation: Int,
    isSwipeEnabled: () -> Boolean = { false },
    isMenuEnabled: Boolean = true,
    onClick: (ArticleWithFeed, Int) -> Unit = { _, _ -> },
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
) {
    // https://issuetracker.google.com/issues/193785330
    // FIXME: Using sticky header with paging-compose need to iterate through the entire list
    //  to figure out where to add sticky headers, which significantly impacts the performance
    if (!isShowStickyHeader) {
        items(
            count = pagingItems.itemCount,
            key = pagingItems.itemKey(::key),
            contentType = pagingItems.itemContentType(::contentType),
        ) { index ->
            when (val item = pagingItems[index]) {
                is ArticleFlowItem.Article -> {
                    val article = item.articleWithFeed.article
                    Column {
                        SwipeableArticleItem(
                            articleWithFeed = item.articleWithFeed,
                            isUnread = diffMap[article.id]?.isUnread ?: article.isUnread,
                            articleListTonalElevation = articleListTonalElevation,
                            onClick = { onClick(it, index) },
                            isSwipeEnabled = isSwipeEnabled,
                            isMenuEnabled = isMenuEnabled,
                            onToggleStarred = onToggleStarred,
                            onToggleRead = onToggleRead,
                            onMarkAboveAsRead =
                                if (index == 1) null
                                else onMarkAboveAsRead, // index == 0 -> ArticleFlowItem.Date
                            onMarkBelowAsRead =
                                if (index == pagingItems.itemCount - 1) null else onMarkBelowAsRead,
                            onShare = onShare,
                        )
                        // peek() throws past the end (paging 3.3.6): guard the
                        // index; no trailing divider after the last article.
                        if (
                            index + 1 < pagingItems.itemCount &&
                                pagingItems.peek(index + 1) is ArticleFlowItem.Article
                        ) {
                            ArticleDivider()
                        }
                    }
                }

                is ArticleFlowItem.Date -> {
                    if (item.showSpacer) {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                    StickyHeader(item.date, isShowFeedIcon, articleListTonalElevation)
                }

                else -> {}
            }
        }
    } else {
        for (index in 0 until pagingItems.itemCount) {
            when (val item = pagingItems.peek(index)) {
                is ArticleFlowItem.Article -> {
                    item(key = key(item), contentType = contentType(item)) {
                        val article = item.articleWithFeed.article
                        Column {
                            SwipeableArticleItem(
                                articleWithFeed = item.articleWithFeed,
                                isUnread = diffMap[article.id]?.isUnread ?: article.isUnread,
                                articleListTonalElevation = articleListTonalElevation,
                                onClick = { onClick(it, index) },
                                isSwipeEnabled = isSwipeEnabled,
                                isMenuEnabled = isMenuEnabled,
                                onToggleStarred = onToggleStarred,
                                onToggleRead = onToggleRead,
                                onMarkAboveAsRead =
                                    if (index == 1) null
                                    else onMarkAboveAsRead, // index == 0 -> ArticleFlowItem.Date
                                onMarkBelowAsRead =
                                    if (index == pagingItems.itemCount - 1) null
                                    else onMarkBelowAsRead,
                                onShare = onShare,
                            )
                            // peek() throws past the end (paging 3.3.6):
                            // guard the index; no trailing divider.
                            if (
                                index + 1 < pagingItems.itemCount &&
                                    pagingItems.peek(index + 1) is ArticleFlowItem.Article
                            ) {
                                ArticleDivider()
                            }
                        }
                    }
                }

                is ArticleFlowItem.Date -> {
                    if (item.showSpacer) {
                        item { Spacer(modifier = Modifier.height(32.dp)) }
                    }
                    stickyHeader(key = key(item), contentType = contentType(item)) {
                        StickyHeader(item.date, isShowFeedIcon, articleListTonalElevation)
                    }
                }

                else -> {}
            }
        }
    }
}

private fun key(item: ArticleFlowItem): String {
    return when (item) {
        is ArticleFlowItem.Article -> item.articleWithFeed.article.id
        is ArticleFlowItem.Date -> item.date
    }
}

private fun contentType(item: ArticleFlowItem): Int {
    return when (item) {
        is ArticleFlowItem.Article -> CONTENT_TYPE_ARTICLE
        is ArticleFlowItem.Date -> CONTENT_TYPE_DATE_HEADER
    }
}

const val CONTENT_TYPE_ARTICLE = 1
const val CONTENT_TYPE_DATE_HEADER = 2
