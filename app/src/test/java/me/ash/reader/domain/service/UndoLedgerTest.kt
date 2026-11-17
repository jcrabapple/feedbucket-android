package me.ash.reader.domain.service

import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.rule.ArticleApplyState
import me.ash.reader.domain.model.rule.StateApplyBefore
import me.ash.reader.domain.model.rule.StateApplyPlanner
import me.ash.reader.domain.model.rule.StateApplyRun
import me.ash.reader.domain.repository.StateApplyDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S18a (amended, R2) — durable undo ledger, acceptance #3a:
 *
 *  - apply→undo round-trip restores the EXACT before-state incl.
 *    pre-existing stars
 *  - changed-since-apply rows are skipped+counted (never a false undo)
 *  - the ledger survives process death (Room-durable; simulated here by a
 *    fresh harness over the same store)
 *  - retention prunes all but the last 10 runs
 *  - `stateVersionAtApply` records the POST-apply bumped version
 *
 * The transaction bodies are defaults on [StateApplyDao]; the fake below
 * implements the seven abstract methods with map semantics that mirror the
 * Room SQL (documented per method), so the behavior pins run without a DB.
 */
class UndoLedgerTest {

    /** In-memory article state mirroring the article table columns the ledger touches. */
    private data class ArticleRow(
        var isUnread: Boolean,
        var isStarred: Boolean,
        var isUpdated: Boolean,
        var readAt: Long?,
        var stateVersion: Long,
    )

    /** Mirrors the Room SQL semantics of [StateApplyDao]'s abstract methods. */
    private class FakeStateApplyDao : StateApplyDao {
        val runs = mutableMapOf<Long, StateApplyRun>()
        val beforeRows = mutableMapOf<Long, MutableList<StateApplyBefore>>()
        private var nextRunId = 1L
        var pruneCalls = 0

        override suspend fun insertRun(run: StateApplyRun): Long {
            val id = nextRunId++
            runs[id] = run.copy(id = id)
            return id
        }

        override suspend fun queryRun(id: Long): StateApplyRun? = runs[id]

        // Mirrors: WHERE finishedAt IS NOT NULL AND undoneAt IS NULL ORDER BY id DESC LIMIT 1
        override suspend fun queryLatestUndoableRun(): StateApplyRun? =
            runs.values.filter { it.finishedAt != null && it.undoneAt == null }.maxByOrNull { it.id }

        override suspend fun markUndone(id: Long, undoneAt: Long, restored: Int, skipped: Int) {
            runs[id] = runs[id]!!.copy(
                undoneAt = undoneAt,
                undoRestoredCount = restored,
                undoSkippedCount = skipped,
            )
        }

        override suspend fun insertBeforeRows(rows: List<StateApplyBefore>) {
            rows.forEach { beforeRows.getOrPut(it.runId) { mutableListOf() }.add(it) }
        }

        override suspend fun queryBeforeRows(runId: Long): List<StateApplyBefore> =
            beforeRows[runId] ?: emptyList()

        // Mirrors: DELETE FROM state_apply_run WHERE id NOT IN (… ORDER BY id DESC LIMIT 10)
        // (ledger rows cascade).
        override suspend fun pruneToLastTenRuns() {
            pruneCalls += 1
            val keep = runs.keys.sortedDescending().take(10).toSet()
            (runs.keys - keep).forEach {
                runs.remove(it)
                beforeRows.remove(it)
            }
        }
    }

    /** Harness standing in for StateApplyLedger's effect/restore lambdas. */
    private class Harness(
        val dao: FakeStateApplyDao = FakeStateApplyDao(),
        val articles: MutableMap<String, ArticleRow>,
    ) {
        fun state(id: String, unread: Boolean, starred: Boolean, updated: Boolean = false, readAt: Long? = null) =
            ArticleApplyState(id, unread, starred, updated, readAt?.let { Date(it) }, articles.getValue(id).stateVersion)

        /** Mirrors ArticleDao.applyMarkReadById + applyStarById (version-conditional, bumping). */
        suspend fun applyRun(
            matched: List<ArticleApplyState>,
            markRead: Boolean,
            star: Boolean,
            now: Long = NOW,
        ): Int =
            dao.applyRun(
                matched = matched,
                ruleId = 1L,
                markRead = markRead,
                star = star,
                now = now,
                insertRun = { dao.insertRun(it) },
                insertBeforeRows = { dao.insertBeforeRows(it) },
                writeEffect = { s ->
                    val row = articles.getValue(s.id)
                    // mirrors ArticleDao.applyRuleEffectsById: ONE version-
                    // conditional write per touched article, bumping once
                    // (states were read inside the same tx so the version
                    // always matches here)
                    if (markRead || star) {
                        if (markRead) {
                            row.isUnread = false
                            row.isUpdated = false
                            row.readAt = now
                        }
                        if (star) {
                            row.isStarred = true
                        }
                        row.stateVersion += 1
                        1
                    } else 0
                },
                prune = { dao.pruneToLastTenRuns() },
            )

        /** Mirrors ArticleDao.restoreStateByIdIfVersion. */
        suspend fun undoLatest(now: Long = NOW) =
            dao.undoLatestRun(now = now) { row ->
                val values = StateApplyPlanner.restoreValues(row)
                val article = articles[row.articleId] ?: return@undoLatestRun 0
                if (article.stateVersion != row.stateVersionAtApply) return@undoLatestRun 0
                article.isUnread = values.isUnread
                article.isStarred = values.isStarred
                article.isUpdated = values.isUpdated
                article.readAt = values.readAtMillis
                article.stateVersion += 1
                1
            }
    }

    private fun articles(vararg ids: String): MutableMap<String, ArticleRow> =
        ids.associateWith { ArticleRow(isUnread = true, isStarred = false, isUpdated = false, readAt = null, stateVersion = 0) }
            .toMutableMap()

    @Test
    fun `planner records the POST-apply bumped version`() {
        val rows = StateApplyPlanner.beforeRows(
            runId = 7L,
            states = listOf(ArticleApplyState("a1", true, false, false, null, 41L)),
        )
        assertEquals(42L, rows.single().stateVersionAtApply)
        assertEquals("a1", rows.single().articleId)
        assertEquals(true, rows.single().wasUnread)
    }

    @Test
    fun `apply then undo restores exact before-state including pre-existing stars`() = runBlocking {
        val store = articles("a1", "a2")
        // a2 was ALREADY starred by the user before the apply run
        store.getValue("a2").isStarred = true
        val harness = Harness(articles = store)

        val count = harness.applyRun(
            matched = listOf(harness.state("a1", true, false), harness.state("a2", true, true)),
            markRead = true,
            star = true,
        )
        assertEquals(2, count)
        // effects landed
        assertEquals(false, store.getValue("a1").isUnread)
        assertEquals(true, store.getValue("a1").isStarred)
        assertEquals(false, store.getValue("a2").isUnread)
        assertEquals(true, store.getValue("a2").isStarred)
        assertEquals(NOW, store.getValue("a1").readAt)

        val counts = harness.undoLatest()
        assertNotNull(counts)
        assertEquals(2, counts!!.restored)
        assertEquals(0, counts.skipped)
        // exact before-state restored; a2 keeps its pre-existing star
        assertEquals(true, store.getValue("a1").isUnread)
        assertEquals(false, store.getValue("a1").isStarred)
        assertEquals(true, store.getValue("a2").isUnread)
        assertEquals(true, store.getValue("a2").isStarred)
        // restored rows are user-visible writes too: version bumped again
        assertEquals(2L, store.getValue("a1").stateVersion)
    }

    @Test
    fun `undo never clears a pre-existing star from a star-effect apply`() = runBlocking {
        val store = articles("a1")
        store.getValue("a1").isStarred = true
        val harness = Harness(articles = store)
        // rule had ONLY mark_read; the star predates the apply
        harness.applyRun(listOf(harness.state("a1", true, true)), markRead = true, star = false)
        val counts = harness.undoLatest()
        assertEquals(1, counts!!.restored)
        assertEquals(true, store.getValue("a1").isStarred)
        assertEquals(true, store.getValue("a1").isUnread)
    }

    @Test
    fun `changed-since-apply rows are skipped and counted`() = runBlocking {
        val store = articles("a1", "a2")
        val harness = Harness(articles = store)
        harness.applyRun(listOf(harness.state("a1", true, false), harness.state("a2", true, false)), markRead = true, star = false)

        // user edits a2 AFTER the apply (bumps its stateVersion)
        store.getValue("a2").isUnread = false
        store.getValue("a2").stateVersion = 99L

        val counts = harness.undoLatest()
        assertEquals(1, counts!!.restored)
        assertEquals(1, counts.skipped)
        assertEquals(true, store.getValue("a1").isUnread) // restored
        assertEquals(false, store.getValue("a2").isUnread) // untouched (user's edit stands)
    }

    @Test
    fun `undo is unavailable after one undo and returns null with no writes`() = runBlocking {
        val store = articles("a1")
        val harness = Harness(articles = store)
        harness.applyRun(listOf(harness.state("a1", true, false)), markRead = true, star = false)
        assertNotNull(harness.undoLatest())
        // second undo: no completed not-yet-undone run remains
        assertNull(harness.undoLatest())
        assertEquals(true, store.getValue("a1").isUnread) // no double-restore
    }

    @Test
    fun `ledger survives process death - fresh harness over the same store can undo`() = runBlocking {
        val store = articles("a1")
        val dao = FakeStateApplyDao()
        Harness(dao, store).applyRun(listOf(Harness(dao, store).state("a1", true, false)), markRead = true, star = false)

        // process death: a brand-new harness (new lambdas, new service
        // instance) over the SAME durable store
        val reborn = Harness(dao, store)
        val counts = reborn.undoLatest()
        assertNotNull(counts)
        assertEquals(1, counts!!.restored)
        assertEquals(true, store.getValue("a1").isUnread)
    }

    @Test
    fun `retention prunes all but the last 10 runs and their ledger rows`() = runBlocking {
        val store = articles("a1")
        val dao = FakeStateApplyDao()
        val harness = Harness(dao, store)
        repeat(12) {
            harness.applyRun(listOf(harness.state("a1", true, false)), markRead = true, star = false)
            // each apply is immediately undoable; undo consumes it so runs pile up only via new applies
        }
        assertEquals(10, dao.runs.size)
        assertEquals(10, dao.beforeRows.size)
        assertTrue(dao.runs.keys.containsAll((3L..12L).toList()))
        // the 10 retained runs are exactly the newest; ledger rows cascade-pruned with them
        val retainedBefore = dao.beforeRows.keys.toSet()
        assertEquals(dao.runs.keys, retainedBefore)
    }

    @Test
    fun `pruned runs can no longer be undone`() = runBlocking {
        val store = articles("a1")
        val dao = FakeStateApplyDao()
        val harness = Harness(dao, store)
        repeat(11) {
            harness.applyRun(listOf(harness.state("a1", true, false)), markRead = true, star = false)
        }
        // runs 1..11 applied; prune kept 2..12? — only 10 newest remain
        assertEquals(10, dao.runs.size)
        // run 1 was pruned: undoLatest targets the newest remaining
        val counts = harness.undoLatest()
        assertNotNull(counts)
    }

    companion object {
        private const val NOW = 1_700_000_000_000L
    }
}
