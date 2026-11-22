package me.ash.reader.ui.page.home.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * queue-scroll lane — decision-table tests for [computeReturnScrollTarget],
 * the pure helper behind the return-from-reader scroll alignment in FlowPage.
 *
 * Geometry model matches LazyListLayoutInfo: offsets are relative to the lazy
 * layout viewport, so `viewportStartOffset` is the top edge, `viewportEndOffset`
 * the bottom edge, and an item is cleanly visible when
 * `offset >= topClearance && offset + size <= viewportEndOffset`.
 */
class ReturnScrollDecisionTest {

    private fun item(
        index: Int,
        offset: Int,
        size: Int,
        isDateHeader: Boolean = false,
    ) = ReturnScrollItemInfo(index = index, offset = offset, size = size, isDateHeader = isDateHeader)

    private fun decide(
        targetIndex: Int,
        visibleItems: List<ReturnScrollItemInfo>,
        viewportStartOffset: Int = 0,
        viewportEndOffset: Int = 1000,
        stickyHeaderEnabled: Boolean = false,
        previousItemIsDate: Boolean = false,
    ) = computeReturnScrollTarget(
        targetIndex = targetIndex,
        visibleItems = visibleItems,
        viewportStartOffset = viewportStartOffset,
        viewportEndOffset = viewportEndOffset,
        stickyHeaderEnabled = stickyHeaderEnabled,
        previousItemIsDate = previousItemIsDate,
    )

    @Test
    fun `fully visible target stays untouched`() {
        val decision =
            decide(
                targetIndex = 3,
                visibleItems = listOf(item(2, 0, 200), item(3, 200, 200), item(4, 400, 200)),
            )
        assertEquals(ReturnScrollDecision.NoOp, decision)
    }

    @Test
    fun `exact bottom edge contact is cleanly visible`() {
        // offset + size == viewportEndOffset: last pixel row still on screen.
        val decision = decide(targetIndex = 4, visibleItems = listOf(item(4, 800, 200)))
        assertEquals(ReturnScrollDecision.NoOp, decision)
    }

    @Test
    fun `exact top clearance contact is cleanly visible`() {
        val decision = decide(targetIndex = 2, visibleItems = listOf(item(2, 0, 200)))
        assertEquals(ReturnScrollDecision.NoOp, decision)
    }

    @Test
    fun `target clipped past the bottom scrolls to top with zero offset`() {
        // Regression: the former code returned a -400 offset that pushed the
        // tapped article past the bottom edge; clipping must now re-anchor at
        // the top with offset 0.
        val decision =
            decide(
                targetIndex = 5,
                visibleItems = listOf(item(4, 700, 200), item(5, 900, 200)),
            )
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 5, scrollOffset = 0), decision)
    }

    @Test
    fun `target clipped above the top scrolls to top with zero offset`() {
        val decision =
            decide(
                targetIndex = 3,
                visibleItems = listOf(item(3, -120, 200), item(4, 80, 200)),
            )
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 3, scrollOffset = 0), decision)
    }

    @Test
    fun `target completely off-screen scrolls to top with zero offset`() {
        val decision =
            decide(
                targetIndex = 30,
                visibleItems = listOf(item(2, 0, 200), item(3, 200, 200)),
            )
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 30, scrollOffset = 0), decision)
    }

    @Test
    fun `empty viewport scrolls to top with zero offset`() {
        val decision = decide(targetIndex = 7, visibleItems = emptyList())
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 7, scrollOffset = 0), decision)
    }

    @Test
    fun `non-zero viewport start offset clips a target above it`() {
        // LazyList viewports can start below 0 (content padding); a target
        // straddling the start edge is not cleanly visible.
        val decision =
            decide(
                targetIndex = 3,
                visibleItems = listOf(item(3, 40, 200), item(4, 240, 200)),
                viewportStartOffset = 50,
                viewportEndOffset = 1050,
            )
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 3, scrollOffset = 0), decision)
    }

    @Test
    fun `target hidden under a pinned sticky date header is not cleanly visible`() {
        // Sticky ON: the section's date header is pinned over the viewport top
        // (0..60); the article at offset 40 has its first lines covered.
        val decision =
            decide(
                targetIndex = 11,
                visibleItems =
                    listOf(
                        item(10, 0, 60, isDateHeader = true),
                        item(11, 40, 200),
                        item(12, 240, 200),
                    ),
                stickyHeaderEnabled = true,
                previousItemIsDate = true,
            )
        // Date-adjacent: scroll to the date item so the header sits above the
        // article instead of covering it.
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 10, scrollOffset = 0), decision)
    }

    @Test
    fun `target below a pinned sticky date header stays untouched`() {
        val decision =
            decide(
                targetIndex = 11,
                visibleItems =
                    listOf(
                        item(10, 0, 60, isDateHeader = true),
                        item(11, 60, 200),
                        item(12, 260, 200),
                    ),
                stickyHeaderEnabled = true,
                previousItemIsDate = true,
            )
        assertEquals(ReturnScrollDecision.NoOp, decision)
    }

    @Test
    fun `date-adjacent off-screen target scrolls to the date item when sticky headers are on`() {
        val decision =
            decide(
                targetIndex = 11,
                visibleItems = listOf(item(20, 0, 200), item(21, 200, 200)),
                stickyHeaderEnabled = true,
                previousItemIsDate = true,
            )
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 10, scrollOffset = 0), decision)
    }

    @Test
    fun `date-adjacent off-screen target scrolls to itself when sticky headers are off`() {
        val decision =
            decide(
                targetIndex = 11,
                visibleItems = listOf(item(20, 0, 200), item(21, 200, 200)),
                stickyHeaderEnabled = false,
                previousItemIsDate = true,
            )
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 11, scrollOffset = 0), decision)
    }

    @Test
    fun `mid-section off-screen target scrolls to itself even when sticky headers are on`() {
        val decision =
            decide(
                targetIndex = 15,
                visibleItems = listOf(item(20, 0, 200), item(21, 200, 200)),
                stickyHeaderEnabled = true,
                previousItemIsDate = false,
            )
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 15, scrollOffset = 0), decision)
    }

    @Test
    fun `target 0 never scrolls to a negative index`() {
        val decision =
            decide(
                targetIndex = 0,
                visibleItems = listOf(item(20, 0, 200)),
                stickyHeaderEnabled = true,
                previousItemIsDate = true,
            )
        assertEquals(ReturnScrollDecision.ScrollTo(targetIndex = 0, scrollOffset = 0), decision)
    }

    @Test
    fun `an unnaturally placed date header below the top does not raise the clearance`() {
        // Sticky ON, but the date header is mid-viewport (not pinned over the
        // top): clearance stays at viewportStartOffset and the target below
        // the header is cleanly visible.
        val decision =
            decide(
                targetIndex = 12,
                visibleItems =
                    listOf(
                        item(11, 0, 200),
                        item(13, 200, 60, isDateHeader = true),
                        item(12, 260, 200),
                    ),
                stickyHeaderEnabled = true,
                previousItemIsDate = false,
            )
        assertEquals(ReturnScrollDecision.NoOp, decision)
    }

    @Test
    fun `scroll offset is never negative for any not-visible geometry`() {
        // Regression sweep: the removed behaviour used -400 (single pane) and
        // -200 (two pane); no decision may ever carry a negative offset.
        val geometries =
            listOf(
                decide(5, listOf(item(4, 700, 200), item(5, 900, 200))),
                decide(3, listOf(item(3, -120, 200))),
                decide(30, listOf(item(2, 0, 200))),
                decide(7, emptyList()),
                decide(
                    11,
                    listOf(item(10, 0, 60, isDateHeader = true), item(11, 40, 200)),
                    stickyHeaderEnabled = true,
                    previousItemIsDate = true,
                ),
            )
        for (decision in geometries) {
            assertTrue("expected ScrollTo, got $decision", decision is ReturnScrollDecision.ScrollTo)
            assertEquals(0, (decision as ReturnScrollDecision.ScrollTo).scrollOffset)
            assertTrue(decision.targetIndex >= 0)
        }
    }
}
