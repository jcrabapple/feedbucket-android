package me.ash.reader.ui.page.nav3

import me.ash.reader.ui.page.nav3.key.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-v1 Wave H gap (UI-CONTRACT §2.2): FeedDetail/GroupDetail destinations
 * are nested routes under the Feeds root. Their route keys must survive the
 * per-root back-stack persistence seam (saveRootStacks/restoreRootStacks)
 * so process recreation restores [Feeds, FeedDetail(...)] intact, and Back
 * from a detail route pops to Feeds (the detail key is not a suite root).
 */
class DetailRouteBackStackTest {

    @Test
    fun feedDetailSurvivesRootStackRoundTrip() {
        val stacks =
            mutableMapOf<Route, List<Route>>(
                Route.Feeds to listOf(Route.Feeds, Route.FeedDetail("feed-1")),
                Route.Settings to listOf(Route.Settings),
                Route.Reading(null) to listOf(Route.Reading(null)),
            )

        val restored = restoreRootStacks(saveRootStacks(stacks)!!)!!

        assertEquals(stacks, restored)
        assertEquals(Route.FeedDetail("feed-1"), restored.getValue(Route.Feeds).last())
    }

    @Test
    fun groupDetailSurvivesRootStackRoundTrip() {
        val stacks =
            mutableMapOf<Route, List<Route>>(
                Route.Feeds to listOf(Route.Feeds, Route.GroupDetail("group-1")),
            )

        val restored = restoreRootStacks(saveRootStacks(stacks)!!)!!

        assertEquals(stacks, restored)
        assertEquals(Route.GroupDetail("group-1"), restored.getValue(Route.Feeds).last())
    }

    @Test
    fun detailRouteKeysCarryTheirIds() {
        // Route keys are value types over their ids; distinct ids must stay
        // distinct after JSON persistence (no collapse to a shared instance).
        val stacks =
            mutableMapOf<Route, List<Route>>(
                Route.Feeds to
                    listOf(
                        Route.Feeds,
                        Route.FeedDetail("feed-a"),
                        Route.FeedDetail("feed-b"),
                    )
            )

        val restored = restoreRootStacks(saveRootStacks(stacks)!!)!!

        assertEquals(
            listOf(Route.Feeds, Route.FeedDetail("feed-a"), Route.FeedDetail("feed-b")),
            restored.getValue(Route.Feeds),
        )
        assertTrue(restored.getValue(Route.Feeds).contains(Route.FeedDetail("feed-a")))
        assertTrue(restored.getValue(Route.Feeds).contains(Route.FeedDetail("feed-b")))
    }

    @Test
    fun detailRoutesAreNotSuiteRoots() {
        // Only Reading(null)/Feeds/Settings terminate root detection; a
        // detail route on top must leave the current root as Feeds so Back
        // pops the detail entry instead of switching roots (§2.3.6).
        val detailKeys: List<Route> = listOf(Route.FeedDetail("f"), Route.GroupDetail("g"))
        val suiteRoots = setOf(Route.Reading(null), Route.Feeds, Route.Settings)
        detailKeys.forEach { key -> assertTrue(!suiteRoots.contains(key)) }
    }
}
