package me.ash.reader.domain.service

import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.query.RuleMatchSql
import me.ash.reader.domain.model.query.SearchQuery
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.model.rule.ArticleApplyState
import me.ash.reader.domain.model.rule.StateApplyBefore
import me.ash.reader.domain.model.rule.StateApplyPlanner
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.StateApplyDao
import me.ash.reader.infrastructure.di.IODispatcher

/**
 * S18a — preview / apply-to-existing / undo over the durable ledger.
 *
 * The S13 scope resolution and SQL rendering happen in the caller
 * (AbstractRssRepository); this service receives the resolved scope feed
 * ids and owns the ledger transaction composition. Transaction bodies live
 * as defaults on [StateApplyDao] so the behavior is unit-testable with
 * fakes (see UndoLedgerTest).
 *
 * Preview: total match count + first 20 samples (S18a cap, web
 * `preview_action` parity). Apply: ONE transaction — ledger head +
 * before rows + effects + stateVersion bumps + retention prune — exact
 * count returned. Undo: most recent completed, not-yet-undone run only;
 * changed-since-apply rows skipped+counted; never clears pre-existing
 * stars; survives process death (Room-durable); retention = last 10 runs.
 */
@Singleton
class StateApplyLedger
@Inject
constructor(
    private val stateApplyDao: StateApplyDao,
    private val articleDao: ArticleDao,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** One preview sample row: `(articleId, title, feed name, date)`. */
    data class PreviewSample(
        val articleId: String,
        val title: String,
        val feedName: String,
        val date: Date?,
    )

    data class Preview(
        val count: Int,
        val samples: List<PreviewSample>,
    )

    /** S18a — total match count over the current scoped library. */
    suspend fun previewCount(accountId: Int, feedIds: List<String>, query: String): Int =
        withContext(ioDispatcher) {
            if (feedIds.isEmpty()) return@withContext 0
            val built = RuleMatchSql.count(accountId, feedIds, parse(query))
            articleDao.queryRuleMatchCountRaw(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
        }

    /** S18a — first 20 samples, ordered by the query sort (S14). */
    suspend fun previewSamples(
        accountId: Int,
        feedIds: List<String>,
        query: String,
        limit: Int = PREVIEW_SAMPLE_LIMIT,
    ): List<PreviewSample> =
        withContext(ioDispatcher) {
            if (feedIds.isEmpty()) return@withContext emptyList()
            val built = RuleMatchSql.preview(accountId, feedIds, parse(query), limit)
            articleDao
                .queryRuleMatchPreviewRaw(SimpleSQLiteQuery(built.sql, built.args.toTypedArray()))
                .map { PreviewSample(it.articleId, it.title, it.feedName.orEmpty(), it.date) }
        }

    /**
     * S18a apply-to-existing (NO star budget — web parity): one transaction
     * writing the durable ledger + effects + stateVersion bumps. [feedIds]
     * is the S13-resolved scope; effects come from the rule's CSV.
     */
    suspend fun applyToExisting(
        accountId: Int,
        rule: ActionRule,
        feedIds: List<String>,
    ): Int =
        withContext(ioDispatcher) {
            if (feedIds.isEmpty()) return@withContext 0
            val effects = rule.effects.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val markRead = ActionRule.EFFECT_MARK_READ in effects
            val star = ActionRule.EFFECT_STAR in effects
            val built = RuleMatchSql.states(accountId, feedIds, parse(rule.query))
            val matched =
                articleDao.queryArticleApplyStatesRaw(
                    SimpleSQLiteQuery(built.sql, built.args.toTypedArray())
                )
            stateApplyDao.applyRun(
                matched = matched,
                ruleId = rule.id,
                markRead = markRead,
                star = star,
                now = System.currentTimeMillis(),
                insertRun = { stateApplyDao.insertRun(it) },
                insertBeforeRows = { stateApplyDao.insertBeforeRows(it) },
                writeEffect = { state -> writeEffect(state, markRead, star) },
                prune = { stateApplyDao.pruneToLastTenRuns() },
            )
        }

    /** S18a — the most recent completed, not-yet-undone run (Undo target). */
    suspend fun latestUndoableRun() = stateApplyDao.queryLatestUndoableRun()

    /**
     * S18a undo of the most recent completed, not-yet-undone run. Each
     * restore is the conditional UPDATE `… WHERE id = ? AND stateVersion =
     * ?` so changed-since-apply rows skip atomically.
     */
    suspend fun undoLatest(): StateApplyDao.UndoCounts? =
        withContext(ioDispatcher) {
            stateApplyDao.undoLatestRun(now = System.currentTimeMillis()) { row ->
                val values = StateApplyPlanner.restoreValues(row)
                articleDao.restoreStateByIdIfVersion(
                    id = row.articleId,
                    isUnread = values.isUnread,
                    isStarred = values.isStarred,
                    isUpdated = values.isUpdated,
                    readAt = values.readAtMillis?.let { Date(it) },
                    expectedVersion = row.stateVersionAtApply,
                )
            }
        }

    /**
     * S18a effect write: ONE conditional UPDATE per touched article. The
     * optimistic check is against the article's CURRENT version (the value
     * read into [ArticleApplyState]) — the SQL matches
     * `WHERE stateVersion = :expectedVersion` and then bumps, so passing
     * the pre-apply version is what makes the write land exactly once and
     * makes `stateVersionAtApply = state.stateVersion + 1` true for the
     * undo restore's version check. Passing anything else (e.g. the
     * post-apply version) matches zero rows and silently drops the effect.
     */
    private suspend fun writeEffect(state: ArticleApplyState, markRead: Boolean, star: Boolean): Int {
        var changed = 0
        if (markRead || star) {
            changed =
                articleDao.applyRuleEffectsById(
                    id = state.id,
                    markRead = markRead,
                    star = star,
                    readAt = Date(System.currentTimeMillis()),
                    expectedVersion = state.stateVersion,
                )
        }
        return changed
    }

    private fun parse(query: String) = SearchQuery.parse(query)

    companion object {
        /** S18a — web `preview_action` cap. */
        const val PREVIEW_SAMPLE_LIMIT = 20
    }
}
