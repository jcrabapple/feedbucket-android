package me.ash.reader.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.ListGeneration
import me.ash.reader.domain.model.article.NeighborRow
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.position.PositionScope
import me.ash.reader.domain.model.position.PositionStore
import me.ash.reader.domain.model.position.ScopeKey
import me.ash.reader.domain.model.query.ScopeSql
import me.ash.reader.domain.repository.ScopeRow
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Acceptance #13 — S24a scope keys + durable positions, S21a/S23 read-state
 * writer semantics, S15 recentlyRead ordering, S25a generation snapshot +
 * SQL row-value neighbor fallback, all against a real v8 Room database.
 *
 * Process-death surrogate: PositionStore state is held by the ViewModel in
 * production; here we recreate the store over the same DAO (the durable
 * layer survives, in-memory UI state does not). The ActivityScenario.recreate
 * variant additionally exercises the Compose layer and requires the full app
 * harness; it is listed for the final integration gate (Wave H checklist).
 *
 * Runs on an explicitly selected emulator only (D15).
 */
@RunWith(AndroidJUnit4::class)
class PositionPersistenceTest {

    private lateinit var db: AndroidDatabase
    private lateinit var feed: Feed

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
        db.groupDao().insert(Group(id = "1\$g1", name = "Tech", accountId = 1))
        feed = Feed(
            id = "1\$f1", name = "Tech Feed", url = "https://example.com/t.xml",
            groupId = "1\$g1", accountId = 1, tags = "tech",
        )
        db.feedDao().insert(feed)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Wave D — the production scope path is the raw ScopeSql query; these
     * helpers keep the Wave C assertions running against it unchanged.
     */
    private suspend fun scopeRows(
        view: String = "unread",
        feedId: String? = null,
        groupId: String? = null,
        sortAscending: Boolean = false,
    ): List<ScopeRow> {
        val built = ScopeSql.rows(1, view, feedId, groupId, sortAscending, null, emptyList())
        return db.articleDao()
            .queryScopeRowsRaw(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
    }

    private suspend fun scopeNeighborPrev(pos: ScopeRow): String? {
        val built = ScopeSql.prev(1, "unread", null, null, false, pos.sortKey, pos.rowId)
        return db.articleDao()
            .queryScopeNeighborPrevRaw(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
    }

    private suspend fun scopeNeighborNext(pos: ScopeRow): String? {
        val built = ScopeSql.next(1, "unread", null, null, false, pos.sortKey, pos.rowId)
        return db.articleDao()
            .queryScopeNeighborNextRaw(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
    }

    private fun article(
        id: String,
        dateMillis: Long,
        unread: Boolean = true,
        updated: Boolean = false,
        readAt: Date? = null,
        stateVersion: Long = 0,
        feedId: String = feed.id,
    ) = Article(
        id = "1\$$id",
        date = Date(dateMillis),
        title = "t$id",
        rawDescription = "",
        shortDescription = "",
        link = "https://example.com/$id",
        feedId = feedId,
        accountId = 1,
        isUnread = unread,
        isUpdated = updated,
        readAt = readAt,
        stateVersion = stateVersion,
    )

    // ---- S24a scope-key canonicalization ------------------------------------

    @Test
    fun scope_key_injectivity_and_round_trip() {
        val empty = PositionScope(view = "unread", feedId = "")
        val dash = PositionScope(view = "unread", feedId = "-")
        assertNotEquals(empty.key, dash.key)

        val tricky = PositionScope(view = "unread", query = "a/b%c|d-e f")
        val decoded = ScopeKey.decode(tricky.key)!!
        assertEquals("a/b%c|d-e f", decoded[4])
        assertEquals("unread", decoded[0])
        assertEquals("", decoded[1])
    }

    @Test
    fun unicode_query_restores_exactly() {
        val scope = PositionScope(view = "unread", query = "straße İ 中文")
        assertEquals(scope.query, ScopeKey.decode(scope.key)!![4])
    }

    // ---- S21a/S23 writer semantics ------------------------------------------

    @Test
    fun mark_read_sets_readAt_drains_isUpdated_bumps_stateVersion_in_one_write() =
        runBlocking {
            db.articleDao().insert(article("a", 3000, updated = true, stateVersion = 5))

            val count =
                db.articleDao().markAsReadByArticleId(1, "1\$a", isUnread = false)
            assertEquals(1, count)

            val row = db.articleDao().queryById("1\$a")!!.article
            assertEquals(false, row.isUnread)
            assertNotNull(row.readAt)
            assertEquals(false, row.isUpdated)
            assertEquals(6, row.stateVersion)
        }

    @Test
    fun mark_unread_clears_readAt_and_bumps_stateVersion() = runBlocking {
        db.articleDao()
            .insert(article("a", 3000, unread = false, readAt = Date(1000), stateVersion = 2))

        db.articleDao().markAsReadByArticleId(1, "1\$a", isUnread = true)

        val row = db.articleDao().queryById("1\$a")!!.article
        assertEquals(true, row.isUnread)
        assertNull(row.readAt)
        assertEquals(3, row.stateVersion)
    }

    @Test
    fun scoped_mark_all_read_reports_exact_count() = runBlocking {
        db.feedDao()
            .insert(
                Feed(
                    id = "1\$f2", name = "Other", url = "https://example.com/o.xml",
                    groupId = "1\$g1", accountId = 1,
                )
            )
        db.articleDao().insert(
            article("a", 3000), article("b", 2000), article("c", 1000),
        )
        db.articleDao().insert(
            article("x", 3000, feedId = "1\$f2"), article("y", 2000, feedId = "1\$f2"),
        )

        assertEquals(3, db.articleDao().countUnreadInScope(1, "1\$f1", null, Date(Long.MAX_VALUE)))
        assertEquals(2, db.articleDao().countUnreadInScope(1, "1\$f2", null, Date(Long.MAX_VALUE)))
        assertEquals(5, db.articleDao().countUnreadInScope(1, null, null, Date(Long.MAX_VALUE)))

        val affected =
            db.articleDao()
                .markAllAsReadByFeedId(1, "1\$f1", false, Date(Long.MAX_VALUE))
        assertEquals(3, affected)
        assertEquals(0, db.articleDao().countUnreadInScope(1, "1\$f1", null, Date(Long.MAX_VALUE)))
        // Other feed untouched.
        assertEquals(2, db.articleDao().countUnreadInScope(1, "1\$f2", null, Date(Long.MAX_VALUE)))
    }

    // ---- S15 recentlyRead view ----------------------------------------------

    @Test
    fun recentlyRead_view_ordered_readAt_desc_rowid_desc() = runBlocking {
        val older = Date(1000)
        val newer = Date(2000)
        db.articleDao().insert(
            article("a", 3000, unread = false, readAt = older),
            article("b", 2000, unread = false, readAt = newer),
            article("c", 1000, unread = false, readAt = newer),
            article("unread", 4000), // never read: excluded
        )

        val pagingSource = db.articleDao().queryArticleWithFeedWhenIsRecentlyRead(1)
        val result =
            pagingSource.load(
                PagingSource.LoadParams.Refresh(null, 50, false)
            ) as PagingSource.LoadResult.Page
        val ids = result.data.map { it.article.id }
        // b and c share readAt; rowid DESC tiebreak (c inserted after b).
        assertEquals(listOf("1\$c", "1\$b", "1\$a"), ids)
    }

    // ---- S25a generation snapshot -------------------------------------------

    @Test
    fun scope_snapshot_orders_unread_by_date_desc_rowid_desc() = runBlocking {
        db.articleDao().insert(
            article("a", 3000), article("b", 2000), article("c", 1000),
        )
        val rows = scopeRows()
        assertEquals(listOf("1\$a", "1\$b", "1\$c"), rows.map { it.articleId })
    }

    @Test
    fun sticky_generation_read_write_never_removes_reorders_or_invalidates() =
        runBlocking {
            db.articleDao().insert(
                article("a", 3000), article("b", 2000), article("c", 1000),
            )
            // Generation anchored at scope entry.
            val generation =
                ListGeneration(
                    scopeRows()
                        .map { NeighborRow(it.articleId, it.sortKey, it.rowId) },
                    rolledAt = 1L,
                )
            assertEquals(listOf("1\$a", "1\$b", "1\$c"), generation.ids)

            // Open B: S21a read-state write lands in the DB…
            db.articleDao().markAsReadByArticleId(1, "1\$b", false)
            // …but the generation snapshot is immutable: same rows, same order.
            assertEquals(listOf("1\$a", "1\$b", "1\$c"), generation.ids)
            assertEquals(3, generation.size)
            // The re-queried SCOPE no longer contains B (that is the predicate)…
            val requeried = scopeRows()
            assertEquals(listOf("1\$a", "1\$c"), requeried.map { it.articleId })
            // …and the neighbor fallback keeps navigation working (prev=A, next=C).
            val pos = db.articleDao().querySortPosition("1\$b")!!
            val prev =
                scopeNeighborPrev(pos)
            val next =
                scopeNeighborNext(pos)
            assertEquals("1\$a", prev)
            assertEquals("1\$c", next)
        }

    @Test
    fun rolled_generation_includes_newly_synced_articles() = runBlocking {
        db.articleDao().insert(article("a", 3000), article("b", 2000))
        val gen1 =
            ListGeneration(
                scopeRows()
                    .map { NeighborRow(it.articleId, it.sortKey, it.rowId) },
                rolledAt = 1L,
            )
        assertEquals(listOf("1\$a", "1\$b"), gen1.ids)

        // Sync delivered a new article; explicit refresh rolls a new generation.
        db.articleDao().insert(article("c", 4000))
        val gen2 =
            ListGeneration(
                scopeRows()
                    .map { NeighborRow(it.articleId, it.sortKey, it.rowId) },
                rolledAt = 2L,
            )
        assertEquals(listOf("1\$c", "1\$a", "1\$b"), gen2.ids)
        assertTrue(gen2.rolledAt > gen1.rolledAt)
    }

    // ---- S24a durable restore -----------------------------------------------

    @Test
    fun position_restores_after_store_recreate_and_falls_back_safely() =
        runBlocking {
            val dao = db.positionDao()
            val scope = PositionScope(view = "unread")
            PositionStore(dao).persist(scope, anchorArticleId = "1\$b", anchorItemIndex = 1, 24)

            // Process-death surrogate: the durable row survives a store/VM
            // recreate; the in-memory store state does not.
            val recreated = PositionStore(dao)
            assertEquals(
                me.ash.reader.domain.model.position.RestoredPosition(1, 24),
                recreated.restore(scope, listOf("1\$a", "1\$b", "1\$c")),
            )
            // Anchor left the scope: saved index clamps into the valid range
            // (1 is already in range for a 3-row list, so it is unchanged).
            assertEquals(
                me.ash.reader.domain.model.position.RestoredPosition(1, 24),
                recreated.restore(scope, listOf("1\$a", "1\$x", "1\$c")),
            )
            // Missing row: null → top.
            assertNull(recreated.restore(PositionScope(view = "starred"), listOf("1\$a")))
        }

    @Test
    fun out_of_range_saved_index_clamps_to_last_valid_row() = runBlocking {
        val dao = db.positionDao()
        val scope = PositionScope(view = "unread")
        PositionStore(dao).persist(scope, anchorArticleId = null, anchorItemIndex = 10, 24)

        val recreated = PositionStore(dao)
        // Saved index beyond the current list clamps to the last valid row.
        assertEquals(
            me.ash.reader.domain.model.position.RestoredPosition(2, 24),
            recreated.restore(scope, listOf("1\$a", "1\$b", "1\$c")),
        )
    }

    @Test
    fun corrupt_position_row_falls_back_to_top_instead_of_crashing() =
        runBlocking {
            val dao = db.positionDao()
            val scope = PositionScope(view = "unread")
            dao.upsertListPosition(
                me.ash.reader.domain.model.position.ListPosition(
                    scopeKey = scope.key,
                    anchorArticleId = "1\$a",
                    anchorItemIndex = -5,
                    anchorOffsetPx = 0,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            val store = PositionStore(dao)
            assertNull(store.restore(scope, listOf("1\$a", "1\$b")))
        }

    @Test
    fun reading_position_persists_and_restores_per_article() = runBlocking {
        val dao = db.positionDao()
        val store = PositionStore(dao)
        // reading_position has an Article FK (S26): seed the article first.
        db.articleDao().insert(article("a", 3000))
        store.persistReading("1\$a", scrollRatioPx = 4000, contentHeightPx = 20000)

        val recreated = PositionStore(dao)
        val restored = recreated.restoreReading("1\$a")
        assertNotNull(restored)
        assertEquals(4000, restored!!.scrollRatioPx)
        assertEquals(20000, restored.contentHeightPx)
        // Unknown article: null → top-of-article, never a crash.
        assertNull(recreated.restoreReading("1\$zzz"))
    }
}
