package me.ash.reader.ui.page.home.flow

import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.preference.FlowArticleListDescPreference
import me.ash.reader.infrastructure.preference.FlowArticleListFeedIconPreference
import me.ash.reader.infrastructure.preference.FlowArticleListFeedNamePreference
import me.ash.reader.infrastructure.preference.FlowArticleListImagePreference
import me.ash.reader.infrastructure.preference.FlowArticleListTimePreference
import me.ash.reader.infrastructure.preference.FlowArticleReadIndicatorPreference
import me.ash.reader.infrastructure.preference.LocalFlowArticleListDesc
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedIcon
import me.ash.reader.infrastructure.preference.LocalFlowArticleListFeedName
import me.ash.reader.infrastructure.preference.LocalFlowArticleListImage
import me.ash.reader.infrastructure.preference.LocalFlowArticleListReadIndicator
import me.ash.reader.infrastructure.preference.LocalFlowArticleListTime
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * queue-scroll lane — return-from-reader scroll alignment, driven on the REAL
 * Choreographer clock (no compose-test rule, no test frame clock).
 *
 * Why not createComposeRule: this app process reliably crashed with
 * "performMeasureAndLayout called during measure layout" under the compose-ui
 * test clock — TestMonotonicFrameClock.performFrame runs measureAndLayoutForTest
 * on the test-thread scheduler while the main thread may be inside a
 * Choreographer traversal of the same AndroidComposeView (a known framework
 * race whose window scales with frame duration; the production ArticleItem row
 * has a ~1.5 s cold JIT frame here). Running the harness on the platform frame
 * clock keeps ALL measure/layout work on the main thread, so the re-entrancy
 * guard can never see two threads; the test thread only reads immutable layout
 * snapshots and injects real MotionEvents.
 *
 * Fidelity: the harness renders the PRODUCTION [ArticleList] (real
 * ArticleItem rows, real stickyHeader entries, production content types) with a
 * fake static paging snapshot, inside a [rememberSaveableStateHolder] pane swap
 * that mirrors NavigableListDetailPaneScaffold's List <-> Detail retention, and
 * the list pane runs the PRODUCTION return-alignment path
 * ([awaitReturnScrollDecision] + [computeReturnScrollTarget] +
 * requestScrollToItem). Article taps are real injected touch events.
 *
 * Contracts pinned here:
 * 1. A tapped, fully visible article stays at its exact tapped position on
 *    return (the former unconditional negative scrollOffset shoved it toward
 *    and past the bottom edge).
 * 2. An article that went off-screen inside the reader (+8 navigation) is
 *    scrolled cleanly to the TOP of the viewport (offset 0), never below.
 * 3. With sticky headers on, a date-adjacent target scrolls to its date item
 *    so the pinned header never covers the article's top lines.
 */
class FlowReturnScrollInstrumentedTest {

    private class HarnessState {
        var inDetail by mutableStateOf(false)
        var activeArticleId by mutableStateOf<String?>(null)

        // Published from the list pane composition (main thread); snapshot
        // state so test-thread reads are coherent.
        var listState by mutableStateOf<LazyListState?>(null)
        var listScope by mutableStateOf<CoroutineScope?>(null)
        var listBoxesInWindow by mutableStateOf<Rect?>(null)
        var windowOrigin by mutableStateOf(intArrayOf(0, 0))
    }

    private val state = HarnessState()

    /** 60 items: a date header at every multiple of 10, articles elsewhere. */
    private val items: List<ArticleFlowItem> =
        (0 until 60).map { index ->
            if (index % 10 == 0) {
                ArticleFlowItem.Date(date = "Day $index", showSpacer = false)
            } else {
                article("a$index")
            }
        }

    /**
     * Stable flow instance (like production's cached pager): recreating it per
     * recomposition would reset the paging presenter on every pane switch and
     * transiently empty the list, endangering the warm-position restore.
     */
    private val pagingFlow = flowOf(PagingData.from(items))

    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before
    fun launchActivity() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After
    fun closeActivity() {
        scenario.close()
    }

    private fun article(id: String): ArticleFlowItem.Article =
        ArticleFlowItem.Article(
            ArticleWithFeed(
                article =
                    Article(
                            id = id,
                            date = Date(1654053729L),
                            title = "Article $id",
                            rawDescription = "desc $id",
                            shortDescription = "desc $id",
                            link = "https://example.com/$id",
                            feedId = "feed-1",
                            accountId = 1,
                            isUnread = true,
                        )
                        .apply { dateString = "10:00" },
                feed =
                    Feed(
                        id = "feed-1",
                        name = "Example Feed",
                        icon = null,
                        url = "https://example.com/feed.xml",
                        groupId = "group-1",
                        accountId = 1,
                        isNotification = false,
                        isFullContent = false,
                    ),
            )
        )

    @Composable
    private fun FlowPrefs(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalFlowArticleListFeedIcon provides FlowArticleListFeedIconPreference.ON,
                    LocalFlowArticleListFeedName provides FlowArticleListFeedNamePreference.ON,
                    LocalFlowArticleListImage provides FlowArticleListImagePreference.ON,
                    LocalFlowArticleListDesc provides FlowArticleListDescPreference.SHORT,
                    LocalFlowArticleListTime provides FlowArticleListTimePreference.ON,
                    LocalFlowArticleListReadIndicator provides
                        FlowArticleReadIndicatorPreference.ExcludingStarred,
                ) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun ListPane(sticky: Boolean, paging: LazyPagingItems<ArticleFlowItem>) {
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        val scope = rememberCoroutineScope()
        state.listState = listState
        state.listScope = scope

        // Mirrors FlowPage: capture the active reader article at composition
        // time and align on re-entry only when it is not cleanly visible.
        val returnArticleId = state.activeArticleId
        LaunchedEffect(Unit) {
            val articleId = returnArticleId ?: return@LaunchedEffect
            val decision =
                listState.awaitReturnScrollDecision(
                    articleId = articleId,
                    stickyHeaderEnabled = sticky,
                    itemCount = { paging.itemCount },
                    items = { paging.itemSnapshotList },
                )
            if (decision is ReturnScrollDecision.ScrollTo) {
                listState.requestScrollToItem(decision.targetIndex, decision.scrollOffset)
            }
        }

        LazyColumn(
            modifier =
                Modifier.fillMaxSize()
                    .onGloballyPositioned { state.listBoxesInWindow = it.boundsInWindow() },
            state = listState,
        ) {
            ArticleList(
                pagingItems = paging,
                diffMap = emptyMap(),
                isShowFeedIcon = true,
                isShowStickyHeader = sticky,
                articleListTonalElevation = 0,
                onClick = { articleWithFeed, _ ->
                    state.activeArticleId = articleWithFeed.article.id
                    state.inDetail = true
                },
            )
        }
    }

    @Composable
    private fun DetailPane() {
        Box(modifier = Modifier.fillMaxSize()) {
            Text("Reading ${state.activeArticleId}")
        }
    }

    private fun setHarness(sticky: Boolean) {
        scenario.onActivity { activity ->
            val origin = IntArray(2)
            activity.window.decorView.getLocationOnScreen(origin)
            state.windowOrigin = origin
            activity.setContent {
                FlowPrefs {
                    val paging = pagingFlow.collectAsLazyPagingItems()
                    val holder = rememberSaveableStateHolder()
                    if (!state.inDetail) {
                        holder.SaveableStateProvider("list") { ListPane(sticky = sticky, paging = paging) }
                    } else {
                        holder.SaveableStateProvider("detail") { DetailPane() }
                    }
                }
            }
        }
        awaitListSettled()
    }

    // ---------------------------------------------------------------------
    // Test-thread helpers: pure snapshot reads + real input injection only.
    // No compose-test framework calls, so no test-thread frame execution.
    // ---------------------------------------------------------------------

    private fun pollUntil(description: String, timeoutMillis: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(150)
        }
        fail("timed out waiting for: $description")
    }

    /** Waits until the full snapshot is laid out and the position is stable. */
    private fun awaitListSettled() {
        pollUntil("list pane composed") { state.listState != null }
        var lastSignature = ""
        var stablePolls = 0
        pollUntil(
            "list settled (last signature $lastSignature)",
        ) {
            val listState = state.listState ?: return@pollUntil false
            val layoutInfo = listState.layoutInfo
            val signature =
                "${layoutInfo.totalItemsCount}|" +
                    "${layoutInfo.visibleItemsInfo.size}|" +
                    "${listState.firstVisibleItemIndex}|" +
                    "${listState.firstVisibleItemScrollOffset}"
            if (signature == lastSignature && layoutInfo.totalItemsCount >= items.size) {
                stablePolls++
            } else {
                stablePolls = 0
                lastSignature = signature
            }
            stablePolls >= 3
        }
    }

    private fun layoutInfo(): LazyListLayoutInfo {
        val listState = state.listState ?: error("list state missing")
        return listState.layoutInfo
    }

    private fun visibleItem(index: Int): LazyListItemInfo? =
        layoutInfo().visibleItemsInfo.firstOrNull { it.index == index }

    private fun scrollListTo(index: Int, offsetPx: Int = 0) {
        val listState = state.listState ?: error("list state missing")
        val scope = state.listScope ?: error("list scope missing")
        // The scroll mutation runs on the composition's main-thread scope.
        val job = scope.launch { listState.scrollToItem(index, offsetPx) }
        runBlocking { job.join() }
        awaitListSettled()
    }

    /** Injects a real down/up tap at the center of the given list item. */
    private fun tapItem(index: Int) {
        pollUntil("item $index laid out") { visibleItem(index) != null }
        val info = visibleItem(index) ?: error("item $index not laid out")
        val boxes = state.listBoxesInWindow ?: error("list bounds unknown")
        val (originX, originY) = state.windowOrigin
        val x = originX + boxes.left + (boxes.right - boxes.left) / 2f
        val y = originY + boxes.top + info.offset + info.size / 2f
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = android.os.SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        )
        instrumentation.sendPointerSync(
            MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        )
    }

    /**
     * Opens the article at [index] with a real tap and waits for the detail
     * pane (the pane swap mirrors the List -> Detail navigation).
     */
    private fun tapIntoReader(index: Int) {
        tapItem(index)
        pollUntil("detail pane shown") { state.inDetail }
        // Let the list pane leave composition and the detail frame run.
        Thread.sleep(400)
    }

    /**
     * Simulates in-reader article navigation, then pops back to the list the
     * same way the Back navigation does. The stale (detached) LazyListState is
     * unpublished first so [awaitListSettled] cannot mistake its frozen layout
     * info for the restored pane's settle.
     */
    private fun backToList(readerArticleId: String?) {
        scenario.onActivity {
            state.listState = null
            state.activeArticleId = readerArticleId
            state.inDetail = false
        }
        awaitListSettled()
        // One more settle pass: the alignment effect may request a scroll that
        // lands on the layout AFTER the restore frame.
        Thread.sleep(300)
        awaitListSettled()
    }

    private fun assertCleanlyInsideViewport(index: Int, message: String) {
        val info = visibleItem(index) ?: error("$message: item $index not laid out")
        val layout = layoutInfo()
        assertTrue(
            "$message: offset ${info.offset} above viewport start ${layout.viewportStartOffset}",
            info.offset >= layout.viewportStartOffset,
        )
        assertTrue(
            "$message: bottom ${info.offset + info.size} past viewport end ${layout.viewportEndOffset}",
            info.offset + info.size <= layout.viewportEndOffset,
        )
    }

    @Test
    fun tappedVisibleArticleStaysAtItsTappedPositionOnReturn() {
        setHarness(sticky = false)

        // Scroll down; the target must be fully visible and not flush at top.
        scrollListTo(22)
        assertCleanlyInsideViewport(25, "pre-tap a25")
        val infoBefore = visibleItem(25) ?: error("pre-tap a25 not laid out")
        val offsetBefore = infoBefore.offset
        val sizeBefore = infoBefore.size
        assertTrue(
            "pre-tap a25 should not be flush at the viewport top",
            offsetBefore > layoutInfo().viewportStartOffset,
        )
        val firstIndexBefore = state.listState!!.firstVisibleItemIndex
        val firstOffsetBefore = state.listState!!.firstVisibleItemScrollOffset

        // Real tap on the article row -> reader pane.
        tapIntoReader(25)
        assertEquals("a25", state.activeArticleId)

        // Pop back without changing the reader article.
        backToList(readerArticleId = "a25")

        // The list must be byte-identical to the tapped state: no scroll, no
        // shove toward the bottom edge.
        val infoAfter = visibleItem(25) ?: error("post-return a25 not laid out")
        assertTrue(
            "a25 moved on return: offset $offsetBefore -> ${infoAfter.offset}",
            abs(infoAfter.offset - offsetBefore) <= 1,
        )
        assertEquals("a25 size changed on return", sizeBefore, infoAfter.size)
        assertEquals(firstIndexBefore, state.listState!!.firstVisibleItemIndex)
        assertEquals(firstOffsetBefore, state.listState!!.firstVisibleItemScrollOffset)
        assertCleanlyInsideViewport(25, "post-return a25")
    }

    @Test
    fun offScreenArticleReturnsCleanlyToViewportTop() {
        setHarness(sticky = false)

        scrollListTo(2)
        assertCleanlyInsideViewport(5, "pre-tap a5")

        tapIntoReader(5)
        assertEquals("a5", state.activeArticleId)

        // Navigate inside the reader to an article far off-screen (5 + 8 = 13;
        // item 12 is an article, so no date-adjacent rewrite applies).
        backToList(readerArticleId = "a13")

        // Cleanly at the top: first visible item is the target with offset 0.
        val listState = state.listState!!
        assertEquals(13, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        val info = visibleItem(13) ?: error("a13 must be laid out after return")
        assertTrue("a13 offset must never be negative, was ${info.offset}", info.offset >= 0)
        assertEquals(0, info.offset)
        assertCleanlyInsideViewport(13, "post-return a13")
    }

    @Test
    fun dateAdjacentTargetScrollsToDateItemSoStickyHeaderNeverCoversIt() {
        setHarness(sticky = true)

        scrollListTo(2)
        assertCleanlyInsideViewport(3, "pre-tap a3")

        tapIntoReader(3)
        assertEquals("a3", state.activeArticleId)

        // Navigate inside the reader past the bottom of the viewport to a21,
        // whose preceding item (20) is the date header. (a11 would sit inside
        // this tall viewport and correctly stay put.)
        backToList(readerArticleId = "a21")

        // The decision targets the date item (index 20) with offset 0.
        val listState = state.listState!!
        assertEquals(20, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        val header = visibleItem(20) ?: error("date item 20 must be laid out")
        val articleInfo = visibleItem(21) ?: error("a21 must be laid out")
        assertEquals(0, header.offset)
        assertTrue(
            "a21 (offset ${articleInfo.offset}) must start at or below the date " +
                "header bottom (${header.offset + header.size}), never under it",
            articleInfo.offset >= header.offset + header.size - 1,
        )
        assertCleanlyInsideViewport(21, "post-return a21")
    }
}
