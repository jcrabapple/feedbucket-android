package me.ash.reader.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.query.RuleMatchSql
import me.ash.reader.domain.model.query.SearchQuery
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.service.StateApplyLedger
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S18a — the REAL Room SQL behind the apply-to-existing ledger, on a real
 * v8 database (reviewer Finding 1 regression): the optimistic-lock effect
 * write must land exactly once when `expectedVersion` equals the row's
 * CURRENT stateVersion, write 0 rows otherwise, and the full
 * apply→undo round trip through [StateApplyLedger] must move destination
 * columns and versions exactly as the ledger records them.
 *
 * Runs on an explicitly selected emulator only (D15); the hosted
 * feedbucket-instrumented workflow is the gate for this path.
 */
@RunWith(AndroidJUnit4::class)
class StateApplyLedgerPersistenceTest {

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
        db.articleDao().insert(
            article("a1", 3000, title = "foo one", updated = true),
            article("a2", 2000, title = "foo two", starred = true, version = 3),
            article("x1", 1000, title = "other"),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun article(
        id: String,
        dateMillis: Long,
        title: String = "t$id",
        starred: Boolean = false,
        updated: Boolean = false,
        version: Long = 0,
    ) = Article(
        id = "1\$$id",
        date = Date(dateMillis),
        title = title,
        rawDescription = "",
        shortDescription = "",
        link = "https://example.com/$id",
        feedId = feed.id,
        accountId = 1,
        isUnread = true,
        isStarred = starred,
        isUpdated = updated,
        readAt = null,
        stateVersion = version,
    )

    private fun ledger() = StateApplyLedger(db.stateApplyDao(), db.articleDao(), Dispatchers.IO)

    @Test
    fun applyRuleEffectsById_is_conditional_on_the_current_stateVersion() = runBlocking {
        // wrong version: 0 rows, article untouched
        val wrong = db.articleDao().applyRuleEffectsById(
            id = "1\$a1", markRead = true, star = true,
            readAt = Date(NOW), expectedVersion = 9L,
        )
        assertEquals(0, wrong)
        db.articleDao().queryById("1\$a1")!!.article.let {
            assertTrue(it.isUnread)
            assertFalse(it.isStarred)
            assertEquals(0L, it.stateVersion)
        }

        // current version: 1 row, effects + single bump land
        val right = db.articleDao().applyRuleEffectsById(
            id = "1\$a1", markRead = true, star = true,
            readAt = Date(NOW), expectedVersion = 0L,
        )
        assertEquals(1, right)
        db.articleDao().queryById("1\$a1")!!.article.let {
            assertFalse(it.isUnread)
            assertFalse(it.isUpdated)
            assertTrue(it.isStarred)
            assertNotNull(it.readAt)
            assertEquals(1L, it.stateVersion)
        }

        // the row now sits on the bumped version: re-running with the
        // pre-apply version is a no-op (the optimistic lock held)
        val stale = db.articleDao().applyRuleEffectsById(
            id = "1\$a1", markRead = true, star = true,
            readAt = Date(NOW), expectedVersion = 0L,
        )
        assertEquals(0, stale)
    }

    @Test
    fun applyToExisting_flips_destination_columns_and_undo_restores_them() = runBlocking {
        val rule = ActionRule(
            type = ActionRule.TYPE_STANDARD,
            title = "r",
            query = "foo",
            effects = "mark_read,star",
            createdAt = 1L,
        )
        val count = ledger().applyToExisting(1, rule, listOf(feed.id))
        // x1 ("other") does not match the canonical term search
        assertEquals(2, count)

        db.articleDao().queryById("1\$a1")!!.article.let {
            assertFalse(it.isUnread)
            assertFalse(it.isUpdated)
            assertTrue(it.isStarred)
            assertNotNull(it.readAt)
            assertEquals(1L, it.stateVersion)
        }
        db.articleDao().queryById("1\$a2")!!.article.let {
            assertFalse(it.isUnread)
            assertTrue(it.isStarred) // pre-existing star kept
            assertEquals(4L, it.stateVersion)
        }
        db.articleDao().queryById("1\$x1")!!.article.let {
            assertTrue(it.isUnread) // unmatched: untouched
            assertFalse(it.isStarred)
            assertEquals(0L, it.stateVersion)
        }

        // ledger head + before rows on the POST-apply bumped versions
        val run = db.stateApplyDao().queryLatestUndoableRun()!!
        assertEquals(2, run.appliedCount)
        val before = db.stateApplyDao().queryBeforeRows(run.id)
        assertEquals(2, before.size)
        assertEquals(1L, before.single { it.articleId == "1\$a1" }.stateVersionAtApply)
        assertEquals(4L, before.single { it.articleId == "1\$a2" }.stateVersionAtApply)

        // Undo through the service restores the exact before-state
        val counts = ledger().undoLatest()!!
        assertEquals(2, counts.restored)
        assertEquals(0, counts.skipped)
        db.articleDao().queryById("1\$a1")!!.article.let {
            assertTrue(it.isUnread)
            assertFalse(it.isStarred)
            assertTrue(it.isUpdated)
            assertNull(it.readAt)
            assertEquals(2L, it.stateVersion)
        }
        db.articleDao().queryById("1\$a2")!!.article.let {
            assertTrue(it.isUnread)
            assertTrue(it.isStarred) // undo never clears the pre-existing star
            assertEquals(5L, it.stateVersion)
        }
    }

    @Test
    fun explicitScopeInlinedLiteralsAndTagEscapeRunOnRealSqlite() = runBlocking {
        // Review lane 3 defect 3: a >999-id explicit scope renders as OR-ed
        // quoted-literal IN chunks (<=500) — ZERO scope bind variables — and
        // must execute on real SQLite (SQLITE_MAX_VARIABLE_NUMBER is 999 on
        // API 26-29, bounded per STATEMENT, so chunked binds would not help).
        val manyIds = (0 until 1200).map { "1\$missing$it" } + feed.id
        val countBuilt = RuleMatchSql.count(1, manyIds, SearchQuery.parse("foo"))
        assertTrue(countBuilt.args.size < 999)
        assertEquals(
            2, // a1 "foo one" + a2 "foo two"; x1 "other" does not match
            db.articleDao().queryRuleMatchCountRaw(
                SimpleSQLiteQuery(countBuilt.sql, countBuilt.args.toTypedArray()),
            ),
        )
        val statesBuilt = RuleMatchSql.states(1, manyIds, SearchQuery.parse("foo"))
        assertEquals(
            2,
            db.articleDao()
                .queryArticleApplyStatesRaw(
                    SimpleSQLiteQuery(statesBuilt.sql, statesBuilt.args.toTypedArray()),
                )
                .size,
        )
        // Review lane 3 defect 4 at the SQLite level: the tag clause ESCAPEs
        // `_`, so `te_h` must not wildcard-match the feed's `tech` tag,
        // while the literal tag still matches all 3 articles of the feed.
        val wildBuilt = RuleMatchSql.count(1, listOf(feed.id), SearchQuery.parse("tag:te_h"))
        assertEquals(
            0,
            db.articleDao().queryRuleMatchCountRaw(
                SimpleSQLiteQuery(wildBuilt.sql, wildBuilt.args.toTypedArray()),
            ),
        )
        val tagBuilt = RuleMatchSql.count(1, listOf(feed.id), SearchQuery.parse("tag:tech"))
        assertEquals(
            3,
            db.articleDao().queryRuleMatchCountRaw(
                SimpleSQLiteQuery(tagBuilt.sql, tagBuilt.args.toTypedArray()),
            ),
        )
    }

    companion object {
        private const val NOW = 1_700_000_000_000L
    }
}
