package me.ash.reader.domain.service

import java.lang.reflect.Proxy
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.model.rule.ArticleApplyState
import me.ash.reader.domain.model.rule.StateApplyBefore
import me.ash.reader.domain.model.rule.StateApplyRun
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.StateApplyDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S18a — end-to-end wiring of [StateApplyLedger] against SQL-mirroring
 * fakes. UndoLedgerTest pins the Room transaction defaults with a synthetic
 * harness; this test pins the REAL service wiring that reviewer Finding 1
 * caught diverging from the SQL contract:
 *
 *  - `writeEffect` must pass the article's CURRENT stateVersion as the
 *    optimistic-lock check — the SQL matches `WHERE stateVersion =
 *    :expectedVersion` and then bumps, so the effect write lands exactly
 *    once and the row lands on the ledger's `stateVersionAtApply` (pre+1).
 *  - apply→undo round trip through the service restores the exact
 *    before-state and skips+counts changed-since-apply rows.
 *
 * The match-set SQL itself (RuleMatchSql) is pinned by exact-string tests
 * and the instrumented Room test; the fake's states read returns the
 * in-scope articles with their current versions, mirroring
 * `queryArticleApplyStatesRaw`.
 */
class StateApplyLedgerTest {

    private data class ArticleRow(
        val feedId: String,
        var isUnread: Boolean,
        var isStarred: Boolean,
        var isUpdated: Boolean,
        var readAt: Long?,
        var stateVersion: Long,
    )

    /** Mirrors the Room SQL of the abstract methods [StateApplyLedger] calls. */
    private class FakeStateApplyDao : StateApplyDao {
        val runs = mutableMapOf<Long, StateApplyRun>()
        val beforeRows = mutableMapOf<Long, MutableList<StateApplyBefore>>()
        private var nextRunId = 1L

        override suspend fun insertRun(run: StateApplyRun): Long {
            val id = nextRunId++
            runs[id] = run.copy(id = id)
            return id
        }

        override suspend fun queryRun(id: Long): StateApplyRun? = runs[id]

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

        override suspend fun pruneToLastTenRuns() {
            val keep = runs.keys.sortedDescending().take(10).toSet()
            (runs.keys - keep).forEach {
                runs.remove(it)
                beforeRows.remove(it)
            }
        }
    }

    /**
     * [ArticleDao] has hundreds of Room methods; a Proxy intercepts only
     * the three the ledger calls and mirrors their SQL semantics
     * (version-conditional writes returning rows-written).
     */
    private class FakeArticleDao(
        private val articles: MutableMap<String, ArticleRow>,
        private val scopeFeedIds: Set<String>,
    ) {
        val dao: ArticleDao =
            Proxy.newProxyInstance(
                ArticleDao::class.java.classLoader,
                arrayOf(ArticleDao::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "applyRuleEffectsById" -> {
                        val id = args[0] as String
                        val markRead = args[1] as Boolean
                        val star = args[2] as Boolean
                        val readAt = args[3] as Date
                        val expectedVersion = args[4] as Long
                        val row = articles.getValue(id)
                        // mirrors: WHERE id = ? AND stateVersion = :expectedVersion,
                        // SET ... stateVersion = stateVersion + 1
                        if (row.stateVersion != expectedVersion) {
                            0
                        } else {
                            if (markRead) {
                                row.isUnread = false
                                row.isUpdated = false
                                row.readAt = readAt.time
                            }
                            if (star) {
                                row.isStarred = true
                            }
                            row.stateVersion += 1
                            1
                        }
                    }
                    "restoreStateByIdIfVersion" -> {
                        val id = args[0] as String
                        val isUnread = args[1] as Boolean
                        val isStarred = args[2] as Boolean
                        val isUpdated = args[3] as Boolean
                        val readAt = args[4] as Date?
                        val expectedVersion = args[5] as Long
                        val row = articles[id]
                        if (row == null || row.stateVersion != expectedVersion) {
                            0
                        } else {
                            row.isUnread = isUnread
                            row.isStarred = isStarred
                            row.isUpdated = isUpdated
                            row.readAt = readAt?.time
                            row.stateVersion += 1
                            1
                        }
                    }
                    "queryArticleApplyStatesRaw" ->
                        articles.values
                            .filter { it.feedId in scopeFeedIds }
                            .map {
                                ArticleApplyState(
                                    id = articles.entries.first { e -> e.value === it }.key,
                                    isUnread = it.isUnread,
                                    isStarred = it.isStarred,
                                    isUpdated = it.isUpdated,
                                    readAt = it.readAt?.let { t -> Date(t) },
                                    stateVersion = it.stateVersion,
                                )
                            }
                    "toString" -> "FakeArticleDao"
                    else -> throw UnsupportedOperationException("FakeArticleDao: ${method.name}")
                }
            } as ArticleDao
    }

    private val feedId = "1\$f1"

    private fun store(vararg rows: Pair<String, ArticleRow>) = rows.toMap().toMutableMap()

    private fun unread(id: String, version: Long = 0, starred: Boolean = false, updated: Boolean = false) =
        id to ArticleRow(feedId, isUnread = true, isStarred = starred, isUpdated = updated, readAt = null, stateVersion = version)

    private fun ledger(
        rows: MutableMap<String, ArticleRow>,
        applyDao: FakeStateApplyDao = FakeStateApplyDao(),
    ) = Triple(StateApplyLedger(applyDao, FakeArticleDao(rows, setOf(feedId)).dao, Dispatchers.Unconfined), applyDao, rows)

    @Test
    fun `applyToExisting flips fields bumps stateVersion and undo restores end to end`() = runBlocking {
        val (service, applyDao, rows) = ledger(store(unread("a1", version = 0, updated = true), unread("a2", version = 3, starred = true)))
        val rule = ActionRule(
            type = ActionRule.TYPE_STANDARD,
            title = "r",
            query = "foo",
            effects = "mark_read,star",
            createdAt = 1L,
        )

        val count = service.applyToExisting(1, rule, listOf(feedId))
        assertEquals(2, count)

        // destination states, explicitly (Gate B advisory): effects landed,
        // single bump each, pre-existing star preserved
        assertEquals(false, rows.getValue("a1").isUnread)
        assertEquals(true, rows.getValue("a1").isStarred)
        assertEquals(false, rows.getValue("a1").isUpdated)
        assertNotNull(rows.getValue("a1").readAt)
        assertEquals(1L, rows.getValue("a1").stateVersion)
        assertEquals(false, rows.getValue("a2").isUnread)
        assertEquals(true, rows.getValue("a2").isStarred)
        assertEquals(4L, rows.getValue("a2").stateVersion)

        // ledger head + before rows carry the POST-apply bumped versions
        assertEquals(2, applyDao.runs.getValue(1L).appliedCount)
        val before = applyDao.beforeRows.getValue(1L)
        assertEquals(2, before.size)
        assertEquals(1L, before.single { it.articleId == "a1" }.stateVersionAtApply)
        assertEquals(4L, before.single { it.articleId == "a2" }.stateVersionAtApply)
        assertEquals(true, before.single { it.articleId == "a2" }.wasStarred)

        // a later user edit on a2 (bumps its version) before Undo
        rows.getValue("a2").isUnread = true
        rows.getValue("a2").stateVersion = 10L

        val counts = service.undoLatest()
        assertNotNull(counts)
        assertEquals(1, counts!!.restored)
        assertEquals(1, counts.skipped)

        // a1 restored to its EXACT before-state; a2 keeps the user's edit
        assertEquals(true, rows.getValue("a1").isUnread)
        assertEquals(false, rows.getValue("a1").isStarred)
        assertEquals(true, rows.getValue("a1").isUpdated)
        assertNull(rows.getValue("a1").readAt)
        assertEquals(2L, rows.getValue("a1").stateVersion)
        assertEquals(true, rows.getValue("a2").isUnread)
        assertEquals(true, rows.getValue("a2").isStarred)
        assertEquals(10L, rows.getValue("a2").stateVersion)
    }

    @Test
    fun `undo is unavailable with no writes when nothing was applied`() = runBlocking {
        val (service, applyDao, rows) = ledger(store(unread("a1")))
        val rule = ActionRule(
            type = ActionRule.TYPE_STANDARD,
            title = "r",
            query = "foo",
            effects = "",
            createdAt = 1L,
        )
        val count = service.applyToExisting(1, rule, listOf(feedId))
        assertEquals(0, count)
        assertEquals(0, applyDao.runs.getValue(1L).appliedCount)
        assertEquals(true, rows.getValue("a1").isUnread)
        assertEquals(0L, rows.getValue("a1").stateVersion)
        // undo still evaluates the ledgered rows: version never matched
        // stateVersionAtApply, so everything is skipped
        val counts = service.undoLatest()
        assertNotNull(counts)
        assertEquals(0, counts!!.restored)
        assertEquals(1, counts.skipped)
        assertEquals(true, rows.getValue("a1").isUnread)
    }

    @Test
    fun `empty scope matches nothing and writes nothing`() = runBlocking {
        val (service, applyDao, rows) = ledger(store(unread("a1")))
        val rule = ActionRule(
            type = ActionRule.TYPE_STANDARD,
            title = "r",
            query = "foo",
            effects = "mark_read",
            // Explicit (non-allFeeds) scope: an EMPTY feed-id list matches
            // nothing. (allFeeds rules scope by accountId inside
            // RuleMatchSql and never pass through this guard — review
            // lane 3 defect 3.)
            allFeeds = false,
            createdAt = 1L,
        )
        assertEquals(0, service.applyToExisting(1, rule, emptyList()))
        assertTrue(applyDao.runs.isEmpty())
        assertEquals(true, rows.getValue("a1").isUnread)
    }
}
