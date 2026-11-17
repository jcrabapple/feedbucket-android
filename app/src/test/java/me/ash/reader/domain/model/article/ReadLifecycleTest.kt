package me.ash.reader.domain.model.article

import me.ash.reader.infrastructure.preference.HideRecentlyReadPreference
import me.ash.reader.infrastructure.preference.MarkAllReadConfirmationPreference
import me.ash.reader.infrastructure.preference.MarkAsReadOnScrollPreference
import me.ash.reader.infrastructure.preference.MarkReadOnOpenPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read lifecycle (S15/S21a/S23/S25a, D5):
 *
 *  - mark-read-on-open default ON (S23); disabled pref or already-read article
 *    fires no write
 *  - mark-read sets readAt + drains isUpdated + bumps stateVersion; mark-unread
 *    clears readAt (S21a/S23)
 *  - mark-all-read confirmation default ON (S25a, web parity)
 *  - read-on-scroll stays OFF by default (D5); hide-recently-read OFF (S15)
 */
class ReadLifecycleTest {

    @Test
    fun `S23 mark-read-on-open defaults ON`() {
        val default = MarkReadOnOpenPreference.default
        assertEquals(MarkReadOnOpenPreference.ON, default)
        assertTrue(default.value)
    }

    @Test
    fun `S25a mark-all-read confirmation defaults ON`() {
        val default = MarkAllReadConfirmationPreference.default
        assertEquals(MarkAllReadConfirmationPreference.ON, default)
        assertTrue(default.value)
    }

    @Test
    fun `D5 read-on-scroll stays OFF by default`() {
        // Touch `.default` first: the upstream class initializes eagerly, and
        // touching the OFF singleton first would trip the JVM recursive-init
        // trap (outer <clinit> reads the in-progress INSTANCE as null).
        val default = MarkAsReadOnScrollPreference.default
        assertEquals(MarkAsReadOnScrollPreference.OFF, default)
        assertFalse(default.value)
    }

    @Test
    fun `S15 hide-recently-read defaults OFF`() {
        val default = HideRecentlyReadPreference.default
        assertEquals(HideRecentlyReadPreference.OFF, default)
        assertFalse(default.value)
    }

    @Test
    fun `opening an unread article with the pref ON fires MARK_READ`() {
        val delta = ReadLifecycle.onOpen(markReadOnOpen = true, wasUnread = true)
        assertEquals(ReadLifecycle.MARK_READ, delta)
    }

    @Test
    fun `opening with the pref OFF fires no write`() {
        assertNull(ReadLifecycle.onOpen(markReadOnOpen = false, wasUnread = true))
    }

    @Test
    fun `reopening an already-read article fires no write`() {
        assertNull(ReadLifecycle.onOpen(markReadOnOpen = true, wasUnread = false))
    }

    @Test
    fun `S23 mark-read delta sets readAt drains isUpdated bumps version`() {
        val d = ReadLifecycle.MARK_READ
        assertEquals(false, d.isUnread)
        assertEquals(ReadFieldChange.SET, d.readAt)
        assertEquals(ReadFieldChange.CLEAR, d.isUpdated)
        assertTrue(d.bumpsStateVersion)
    }

    @Test
    fun `S21a mark-unread delta clears readAt leaves isUpdated bumps version`() {
        val d = ReadLifecycle.MARK_UNREAD
        assertEquals(true, d.isUnread)
        assertEquals(ReadFieldChange.CLEAR, d.readAt)
        assertEquals(ReadFieldChange.UNCHANGED, d.isUpdated)
        assertTrue(d.bumpsStateVersion)
    }
}
