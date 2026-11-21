package me.ash.reader.ui.page.home.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import coil.size.Precision
import coil.size.Scale
import me.ash.reader.R
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference
import me.ash.reader.infrastructure.preference.LocalArticleListSwipeEndAction
import me.ash.reader.infrastructure.preference.LocalArticleListSwipeStartAction
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.infrastructure.preference.SwipeEndActionPreference
import me.ash.reader.infrastructure.preference.SwipeStartActionPreference
import me.ash.reader.ui.component.FeedIcon
import me.ash.reader.ui.component.base.RYAsyncImage
import me.ash.reader.ui.component.base.SIZE_1000
import me.ash.reader.ui.component.menu.AnimatedDropdownMenu
import me.ash.reader.ui.component.swipe.SwipeAction
import me.ash.reader.ui.component.swipe.SwipeableActionsBox
import me.ash.reader.ui.ext.requiresBidi
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.page.settings.color.flow.generateArticleWithFeedPreview
import me.ash.reader.ui.theme.applyTextDirection
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import me.ash.reader.ui.theme.palette.onDark

private const val TAG = "ArticleItem"

/** Test seams for the structural gutter geometry (instrumented alignment assertions). */
const val ARTICLE_ROW_GUTTER_TAG = "article_row_gutter"
const val ARTICLE_ROW_ICON_TAG = "article_row_feed_icon"
const val ARTICLE_ROW_DOT_TAG = "article_row_read_dot"

/**
 * Pure read-state decisions for the flow row (REFRESH-v0.2 sections 1.1-1.3,
 * structural-gutter rework). Extracted so the emphasis and dot truth table and
 * the gutter geometry are unit-testable on the JVM.
 */
internal object ArticleItemReadState {

    /** Card content edge: 12dp row margin + 12dp inner row padding. */
    val contentEdge: Dp = 24.dp

    /** Horizontal gap between the left gutter column and the text column. */
    val gutterEndSpacing: Dp = 12.dp

    /** Unread-level typography (weight 600 + onSurface + primary feedName). */
    fun emphasized(
        readIndicator: FlowArticleReadIndicatorPreference,
        isUnread: Boolean,
        isStarred: Boolean,
    ): Boolean =
        when (readIndicator) {
            FlowArticleReadIndicatorPreference.None -> true
            FlowArticleReadIndicatorPreference.AllRead -> isUnread
            FlowArticleReadIndicatorPreference.ExcludingStarred -> isUnread || isStarred
        }

    /** The dot strictly means unread; starred-read rows carry no dot. */
    fun showDot(
        readIndicator: FlowArticleReadIndicatorPreference,
        isUnread: Boolean,
    ): Boolean = readIndicator != FlowArticleReadIndicatorPreference.None && isUnread

    /** The dot slot is reserved on every row unless the user opted out. */
    fun reservesDotSlot(readIndicator: FlowArticleReadIndicatorPreference): Boolean =
        readIndicator != FlowArticleReadIndicatorPreference.None

    /**
     * The left gutter renders whenever the favicon is on or a dot slot is
     * reserved; it is omitted only when neither is, so the text column then
     * spans the full padded width.
     */
    fun showsGutter(
        isShowFeedIcon: Boolean,
        readIndicator: FlowArticleReadIndicatorPreference,
    ): Boolean = isShowFeedIcon || reservesDotSlot(readIndicator)

    /**
     * Structural gutter width: the 24dp favicon frame when the icon is shown,
     * else the 12dp reserved dot lane. feedName, title and snippet alignment
     * comes from this column instead of per-line start padding, so all three
     * share the exact same left edge in every configuration.
     */
    fun gutterWidth(
        isShowFeedIcon: Boolean,
        readIndicator: FlowArticleReadIndicatorPreference,
    ): Dp =
        when {
            isShowFeedIcon -> 24.dp
            reservesDotSlot(readIndicator) -> 12.dp
            else -> 0.dp
        }

    /**
     * Sticky date header start padding: aligns the header with the row text
     * column when a gutter is shown, else with the card content edge.
     */
    fun stickyHeaderStartPadding(
        isShowFeedIcon: Boolean,
        readIndicator: FlowArticleReadIndicatorPreference,
    ): Dp =
        if (showsGutter(isShowFeedIcon, readIndicator)) {
            contentEdge + gutterWidth(isShowFeedIcon, readIndicator) + gutterEndSpacing
        } else {
            contentEdge
        }
}

@Composable
fun ArticleItem(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    onClick: (ArticleWithFeed) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val feed = articleWithFeed.feed
    val article = articleWithFeed.article

    ArticleItem(
        modifier = modifier,
        feedName = feed.name,
        feedIconUrl = feed.icon,
        title = article.title,
        shortDescription = article.shortDescription,
        timeString = article.dateString,
        imgData = article.img,
        isStarred = article.isStarred,
        isUnread = isUnread,
        onClick = { onClick(articleWithFeed) },
        onLongClick = onLongClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleItem(
    modifier: Modifier = Modifier,
    feedName: String = "",
    feedIconUrl: String? = null,
    title: String = "",
    shortDescription: String = "",
    timeString: String? = null,
    imgData: Any? = null,
    isStarred: Boolean = false,
    isUnread: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val articleListFeedIcon = LocalFlowArticleListFeedIcon.current
    val articleListFeedName = LocalFlowArticleListFeedName.current
    val articleListImage = LocalFlowArticleListImage.current
    val articleListDesc = LocalFlowArticleListDesc.current
    val articleListDate = LocalFlowArticleListTime.current
    val articleListReadIndicator = LocalFlowArticleListReadIndicator.current

    val emphasized = ArticleItemReadState.emphasized(articleListReadIndicator, isUnread, isStarred)
    val showDot = ArticleItemReadState.showDot(articleListReadIndicator, isUnread)
    val reservesDotSlot = ArticleItemReadState.reservesDotSlot(articleListReadIndicator)
    val showGutter = ArticleItemReadState.showsGutter(articleListFeedIcon.value, articleListReadIndicator)
    val gutterWidth = ArticleItemReadState.gutterWidth(articleListFeedIcon.value, articleListReadIndicator)
    val showMetadataRow = articleListFeedName.value || articleListDate.value || isStarred
    val readStateDescription = stringResource(if (isUnread) R.string.unread else R.string.read)

    // Two-column row: a fixed left gutter (favicon on top, reserved unread-dot
    // slot below it) plus one content column whose text lines all start at
    // 0dp, so feedName, title and snippet share the exact same left edge in
    // every icon/dot/thumbnail/read-state combination.
    Row(
        modifier =
            modifier
                .padding(horizontal = 12.dp)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics { stateDescription = readStateDescription }
                .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        // Left gutter
        if (showGutter) {
            Column(
                modifier =
                    Modifier
                        .testTag(ARTICLE_ROW_GUTTER_TAG)
                        .padding(end = ArticleItemReadState.gutterEndSpacing)
                        .width(gutterWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (articleListFeedIcon.value) {
                    FeedIcon(
                        modifier = Modifier.testTag(ARTICLE_ROW_ICON_TAG),
                        feedName = feedName,
                        iconUrl = feedIconUrl,
                    )
                }
                if (reservesDotSlot) {
                    // Reserved 8dp slot below the icon: read/unread transitions
                    // never shift text or icon horizontally or vertically.
                    // Decorative; the row exposes stateDescription instead.
                    Spacer(
                        modifier =
                            Modifier.height(
                                if (articleListFeedIcon.value) 10.dp else 4.dp
                            )
                    )
                    Box(
                        modifier =
                            Modifier.size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (showDot) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clearAndSetSemantics { testTag = ARTICLE_ROW_DOT_TAG }
                    )
                }
            }
        }

        // Content column
        Column(modifier = Modifier.weight(1f)) {
            // Row 1: metadata line (feedName left, star + time right)
            if (showMetadataRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (articleListFeedName.value) {
                        Text(
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                            text = feedName,
                            color =
                                if (emphasized) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (articleListDate.value) {
                        // feedName hidden: the time takes the weight(1f) left edge.
                        Text(
                            modifier = Modifier.weight(1f),
                            text = timeString ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (isStarred) {
                        StarredIcon()
                    }
                    if (articleListFeedName.value && articleListDate.value) {
                        Text(
                            modifier = Modifier,
                            text = timeString ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // Row 2: title + snippet, optional right thumbnail
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .then(if (showMetadataRow) Modifier.padding(top = 4.dp) else Modifier)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color =
                            if (emphasized) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style =
                            MaterialTheme.typography.titleMedium
                                .merge(
                                    fontWeight =
                                        if (emphasized) FontWeight.SemiBold else FontWeight.Normal
                                )
                                .applyTextDirection(title.requiresBidi())
                                .merge(lineHeight = 22.sp),
                        maxLines =
                            if (articleListDesc != FlowArticleListDescPreference.NONE) 2 else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (
                        articleListDesc != FlowArticleListDescPreference.NONE &&
                            shortDescription.isNotBlank()
                    ) {
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = shortDescription,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style =
                                MaterialTheme.typography.bodySmall.applyTextDirection(
                                    shortDescription.requiresBidi()
                                ),
                            maxLines =
                                when (articleListDesc) {
                                    FlowArticleListDescPreference.LONG -> 4
                                    FlowArticleListDescPreference.SHORT -> 2
                                    else -> throw IllegalStateException()
                                },
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Image
                if (imgData != null && articleListImage.value) {
                    RYAsyncImage(
                        modifier =
                            Modifier.padding(start = 12.dp)
                                .size(80.dp)
                                .clip(MaterialTheme.shapes.small),
                        data = imgData,
                        scale = Scale.FILL,
                        precision = Precision.INEXACT,
                        size = SIZE_1000,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

@Composable
fun StarredIcon(modifier: Modifier = Modifier) {
    val fontSize = LocalTextStyle.current.fontSize
    val iconSize = with(LocalDensity.current) { fontSize.toDp() }

    Icon(
        modifier = modifier
            .size(iconSize)
            .padding(end = 2.dp),
        imageVector = Icons.Rounded.Star,
        contentDescription = stringResource(R.string.starred),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
}

private const val PositionalThresholdFraction = 0.4f
private const val SwipeActionDelay = 300L

@Composable
fun SwipeableArticleItem(
    articleWithFeed: ArticleWithFeed,
    isUnread: Boolean = articleWithFeed.article.isUnread,
    articleListTonalElevation: Int = 0,
    onClick: (ArticleWithFeed) -> Unit = {},
    isSwipeEnabled: () -> Boolean = { false },
    isMenuEnabled: Boolean = true,
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
) {

    var isMenuExpanded by remember { mutableStateOf(false) }

    val onLongClick =
        if (isMenuEnabled) {
            { isMenuExpanded = true }
        } else {
            null
        }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }

    SwipeActionBox(
        articleWithFeed = articleWithFeed,
        isRead = !isUnread,
        isStarred = articleWithFeed.article.isStarred,
        onToggleStarred = onToggleStarred,
        onToggleRead = onToggleRead,
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .pointerInput(isMenuExpanded) {
                        awaitEachGesture {
                            while (true) {
                                awaitFirstDown(requireUnconsumed = false).let {
                                    menuOffset = it.position.round()
                                }
                            }
                        }
                    }
                    .background(
                        MaterialTheme.colorScheme.surfaceColorAtElevation(
                            articleListTonalElevation.dp
                        ) onDark MaterialTheme.colorScheme.surface
                    )
                    .wrapContentSize()
        ) {
            ArticleItem(
                articleWithFeed = articleWithFeed,
                isUnread = isUnread,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            with(articleWithFeed.article) {
                if (isMenuEnabled) {
                    AnimatedDropdownMenu(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        expanded = isMenuExpanded,
                        onDismissRequest = { isMenuExpanded = false },
                        offset = menuOffset,
                    ) {
                        ArticleItemMenuContent(
                            articleWithFeed = articleWithFeed,
                            isStarred = isStarred,
                            isRead = !isUnread,
                            onToggleStarred = onToggleStarred,
                            onToggleRead = onToggleRead,
                            onMarkAboveAsRead = onMarkAboveAsRead,
                            onMarkBelowAsRead = onMarkBelowAsRead,
                            onShare = onShare,
                        ) {
                            isMenuExpanded = false
                        }
                    }
                }
            }
        }
    }
}

private enum class SwipeDirection {
    StartToEnd,
    EndToStart,
}

@Composable
private fun SwipeActionBox(
    modifier: Modifier = Modifier,
    articleWithFeed: ArticleWithFeed,
    isStarred: Boolean,
    isRead: Boolean,
    onToggleStarred: (ArticleWithFeed) -> Unit,
    onToggleRead: (ArticleWithFeed) -> Unit,
    content: @Composable () -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.tertiaryContainer

    val swipeToStartAction = LocalArticleListSwipeStartAction.current
    val swipeToEndAction = LocalArticleListSwipeEndAction.current

    val onSwipeEndToStart =
        when (swipeToStartAction) {
            SwipeStartActionPreference.None -> null
            SwipeStartActionPreference.ToggleRead -> onToggleRead
            SwipeStartActionPreference.ToggleStarred -> onToggleStarred
        }

    val onSwipeStartToEnd =
        when (swipeToEndAction) {
            SwipeEndActionPreference.None -> null
            SwipeEndActionPreference.ToggleRead -> onToggleRead
            SwipeEndActionPreference.ToggleStarred -> onToggleStarred
        }

    if (onSwipeStartToEnd == null && onSwipeEndToStart == null) {
        content()
        return
    }

    val startAction =
        onSwipeStartToEnd?.let {
            SwipeAction(
                icon = {
                    swipeActionIcon(
                            direction = SwipeDirection.StartToEnd,
                            isStarred = isStarred,
                            isRead = isRead,
                        )
                        ?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                },
                background = containerColor,
                isUndo = false,
                onSwipe = { onSwipeStartToEnd.invoke(articleWithFeed) },
            )
        }

    val endAction =
        onSwipeEndToStart?.let {
            SwipeAction(
                icon = {
                    swipeActionIcon(
                            direction = SwipeDirection.EndToStart,
                            isStarred = isStarred,
                            isRead = isRead,
                        )
                        ?.let {
                            Icon(
                                imageVector = it,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                },
                background = containerColor,
                isUndo = false,
                onSwipe = { onSwipeEndToStart.invoke(articleWithFeed) },
            )
        }

    SwipeableActionsBox(
        modifier = modifier,
        startActions = listOfNotNull(startAction),
        endActions = listOfNotNull(endAction),
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surface,
    ) {
        content.invoke()
    }
}

@Composable
private fun swipeActionIcon(
    direction: SwipeDirection,
    isStarred: Boolean,
    isRead: Boolean,
): ImageVector? {
    val swipeToStartAction = LocalArticleListSwipeStartAction.current
    val swipeToEndAction = LocalArticleListSwipeEndAction.current

    val starImageVector =
        remember(isStarred) { if (isStarred) Icons.Outlined.StarOutline else Icons.Rounded.Star }

    val readImageVector =
        remember(isRead) { if (isRead) Icons.Outlined.Circle else Icons.Rounded.CheckCircleOutline }

    return remember(direction) {
        when (direction) {
            SwipeDirection.StartToEnd -> {

                when (swipeToEndAction) {
                    SwipeEndActionPreference.None -> null
                    SwipeEndActionPreference.ToggleRead -> readImageVector
                    SwipeEndActionPreference.ToggleStarred -> starImageVector
                }
            }

            SwipeDirection.EndToStart -> {
                when (swipeToStartAction) {
                    SwipeStartActionPreference.None -> null
                    SwipeStartActionPreference.ToggleRead -> readImageVector
                    SwipeStartActionPreference.ToggleStarred -> starImageVector
                }
            }
        }
    }
}

@Composable
private fun swipeActionText(
    direction: SwipeDirection,
    isStarred: Boolean,
    isRead: Boolean,
): String {
    val swipeToStartAction = LocalArticleListSwipeStartAction.current
    val swipeToEndAction = LocalArticleListSwipeEndAction.current

    val starText =
        stringResource(if (isStarred) R.string.mark_as_unstar else R.string.mark_as_starred)

    val readText = stringResource(if (isRead) R.string.mark_as_unread else R.string.mark_as_read)

    return remember(direction) {
        when (direction) {
            SwipeDirection.StartToEnd -> {
                when (swipeToEndAction) {
                    SwipeEndActionPreference.None -> "null"
                    SwipeEndActionPreference.ToggleRead -> readText
                    SwipeEndActionPreference.ToggleStarred -> starText
                }
            }

            SwipeDirection.EndToStart -> {
                when (swipeToStartAction) {
                    SwipeStartActionPreference.None -> "null"
                    SwipeStartActionPreference.ToggleRead -> readText
                    SwipeStartActionPreference.ToggleStarred -> starText
                }
            }
        }
    }
}

@Composable
fun ArticleItemMenuContent(
    articleWithFeed: ArticleWithFeed,
    iconSize: DpSize = DpSize(width = 20.dp, height = 20.dp),
    isStarred: Boolean = false,
    isRead: Boolean = false,
    onToggleStarred: (ArticleWithFeed) -> Unit = {},
    onToggleRead: (ArticleWithFeed) -> Unit = {},
    onMarkAboveAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onMarkBelowAsRead: ((ArticleWithFeed) -> Unit)? = null,
    onShare: ((ArticleWithFeed) -> Unit)? = null,
    onItemClick: (() -> Unit)? = null,
) {
    val starImageVector =
        remember(isStarred) { if (isStarred) Icons.Outlined.StarOutline else Icons.Rounded.Star }

    val readImageVector =
        remember(isRead) {
            if (isRead) Icons.Outlined.FiberManualRecord else Icons.Rounded.FiberManualRecord
        }

    val starText =
        stringResource(if (isStarred) R.string.mark_as_unstar else R.string.mark_as_starred)

    val readText = stringResource(if (isRead) R.string.mark_as_unread else R.string.mark_as_read)

    DropdownMenuItem(
        text = { Text(text = readText) },
        onClick = {
            onToggleRead(articleWithFeed)
            onItemClick?.invoke()
        },
        leadingIcon = {
            Icon(
                imageVector = readImageVector,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        },
    )
    DropdownMenuItem(
        text = { Text(text = starText) },
        onClick = {
            onToggleStarred(articleWithFeed)
            onItemClick?.invoke()
        },
        leadingIcon = {
            Icon(
                imageVector = starImageVector,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        },
    )

    if (onMarkAboveAsRead != null || onMarkBelowAsRead != null) {
        HorizontalDivider()
    }
    onMarkAboveAsRead?.let {
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.mark_above_as_read)) },
            onClick = {
                onMarkAboveAsRead(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
    onMarkBelowAsRead?.let {
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.mark_below_as_read)) },
            onClick = {
                onMarkBelowAsRead(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
    onShare?.let {
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = stringResource(id = R.string.share)) },
            onClick = {
                onShare(articleWithFeed)
                onItemClick?.invoke()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
            },
        )
    }
}

@Preview
@Composable
fun MenuContentPreview() {
    MaterialTheme {
        Surface() {
            Column(modifier = Modifier.padding()) {
                ArticleItemMenuContent(
                    articleWithFeed = generateArticleWithFeedPreview(),
                    onMarkBelowAsRead = {},
                    onMarkAboveAsRead = {},
                    onShare = {},
                )
            }
        }
    }
}

@Preview(name = "ArticleItem light")
@Composable
fun ArticleItemLightPreview() {
    MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
        Surface(color = MaterialTheme.colorScheme.surface) { ArticleItemPreviewStates() }
    }
}

@Preview(name = "ArticleItem dark")
@Composable
fun ArticleItemDarkPreview() {
    MaterialTheme(colorScheme = FeedBucketColorSchemes.Dark) {
        Surface(color = MaterialTheme.colorScheme.surface) { ArticleItemPreviewStates() }
    }
}

/** Unread / read / starred-read under the default ExcludingStarred indicator. */
@Composable
private fun ArticleItemPreviewStates() {
    val preview = generateArticleWithFeedPreview()
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        ArticleItemPreviewRow(preview, isUnread = true, isStarred = false)
        ArticleItemPreviewRow(preview, isUnread = false, isStarred = false)
        ArticleItemPreviewRow(preview, isUnread = false, isStarred = true)
    }
}

@Composable
private fun ArticleItemPreviewRow(
    preview: ArticleWithFeed,
    isUnread: Boolean,
    isStarred: Boolean,
) {
    val article = preview.article
    ArticleItem(
        feedName = preview.feed.name,
        feedIconUrl = preview.feed.icon,
        title = article.title,
        shortDescription = article.shortDescription,
        timeString = article.dateString,
        imgData = R.drawable.animation,
        isStarred = isStarred,
        isUnread = isUnread,
    )
}
