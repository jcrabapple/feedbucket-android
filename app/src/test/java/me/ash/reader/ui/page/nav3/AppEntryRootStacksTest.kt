package me.ash.reader.ui.page.nav3

import me.ash.reader.ui.page.nav3.key.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave H (UI-CONTRACT §2.1): per-root back stack retention survives
 * configuration change / process recreation (RootStacksSaver round trip).
 */
class AppEntryRootStacksTest {

    @Test
    fun rootStacksRoundTripThroughSaver() {
        val stacks =
            mutableMapOf<Route, List<Route>>(
                Route.Reading(null) to listOf(Route.Reading(null), Route.Reading("article-1")),
                Route.Feeds to listOf(Route.Feeds),
                Route.Settings to listOf(Route.Settings, Route.Rules),
            )

        val saved = saveRootStacks(stacks)
        assertTrue("saver should produce JSON", saved != null && saved.isNotBlank())

        val restored = restoreRootStacks(saved!!)
        assertEquals(stacks, restored)
    }

    @Test
    fun emptyRootsAreNotRetained() {
        val stacks = mutableMapOf<Route, List<Route>>(Route.Feeds to listOf(Route.Feeds))
        val restored = restoreRootStacks(saveRootStacks(stacks)!!)!!
        assertTrue(restored.keys.none { it == Route.Reading(null) })
        assertTrue(restored.keys.none { it == Route.Settings })
    }

    @Test
    fun corruptInputRestoresToNull() {
        assertNull(restoreRootStacks("not json"))
    }

    @Test
    fun rootOrderMatchesContract() {
        // Read, Feeds, Settings (§2.1)
        assertEquals(Route.Reading(null), suiteRootOrder[0])
        assertEquals(Route.Feeds, suiteRootOrder[1])
        assertEquals(Route.Settings, suiteRootOrder[2])
    }
}
