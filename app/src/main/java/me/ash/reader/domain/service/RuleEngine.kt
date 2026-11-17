package me.ash.reader.domain.service

import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.query.QueryMatcher
import me.ash.reader.domain.model.query.SearchQuery
import me.ash.reader.domain.model.rule.ActionRule

/**
 * S17a — ingest-time rule application (port of web `apply_actions_for_entry`,
 * actions.py:105–139), unified on the canonical S12a contract (D7).
 *
 * (amended, R1) ONLY `type='standard'` rules participate in ingest
 * application; `type='mute'` rules are pure query-time filters (S15) and
 * NEVER have ingest effects — a deliberate divergence from web
 * actions.py:105–123 (which iterates ALL action rows with no
 * `action_type` guard, so a web mute carrying the default `mark_read`
 * effect suppresses unread insertion). Documented in SPEC S17a: deleting
 * an Android mute reveals the untouched unread state (D7 reversibility).
 *
 * Matching per rule uses [QueryMatcher] over the four canonical fields;
 * multiple matching rules OR their effects (web parity:
 * `mark_read = mark_read or …`, `star = star or …`). The star budget is
 * 100 stars per per-feed refresh run, shared across rules within that
 * run, decremented ONLY on actual flips (web `star_budget` semantics) —
 * a bad rule cannot star an entire backlog in one sync.
 *
 * Update-path semantics (S17a): on a publisher UPDATE (S20a), `star` is
 * skipped entirely (`star = not is_update and …`, web actions.py:117) and
 * `mark_read` clears isUnread AND isUpdated (web deletes the unread and
 * updated rows). Wave E wires this into the update path; the decision
 * function lives here, test-pinned.
 */
object RuleEngine {

    /** S17a — web star_budget: max 100 stars per per-feed refresh run. */
    const val STAR_BUDGET_PER_RUN = 100

    data class BatchResult(
        /** The input articles with rule effects applied (copies). */
        val articles: List<Article>,
        /** How many star flips the budget actually funded this run. */
        val starsApplied: Int,
    )

    /**
     * S17a new-article path: matching `mark_read` inserts the article with
     * `isUnread = 0`; matching `star` sets `isStarred = 1` while the budget
     * lasts (the 101st matched article of the run stays unstarred).
     * [rules] may contain any types; non-standard rules are ignored here.
     * [feedTags] is the feed's tags CSV split; [groupName] its group name
     * (S13 scope is resolved by the CALLER against the account's feeds).
     */
    fun applyToNewArticles(
        articles: List<Article>,
        feedTags: List<String>,
        groupName: String?,
        rules: List<ActionRule>,
    ): BatchResult {
        val standard = rules.filter { it.type == ActionRule.TYPE_STANDARD }
        if (standard.isEmpty() || articles.isEmpty()) {
            return BatchResult(articles, 0)
        }
        val parsed = standard.map { SearchQuery.parse(it.query) to it }
        var budget = STAR_BUDGET_PER_RUN
        var starsApplied = 0
        val out = articles.map { article ->
            var markRead = false
            var star = false
            val fields = QueryMatcher.Fields(
                title = article.title,
                shortDescription = article.shortDescription,
                rawDescription = article.rawDescription,
                fullContent = article.fullContent,
            )
            for ((query, rule) in parsed) {
                if (!QueryMatcher.matches(query, fields, feedTags, groupName)) continue
                val effects = rule.effects.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                markRead = markRead || ActionRule.EFFECT_MARK_READ in effects
                star = star || ActionRule.EFFECT_STAR in effects
            }
            var next = article
            if (markRead) next = next.copy(isUnread = false)
            // New articles always start unstarred, so a funded star is an
            // actual flip by construction (budget decrements like the web
            // `cursor.rowcount` check).
            if (star && budget > 0) {
                next = next.copy(isStarred = true)
                budget -= 1
                starsApplied += 1
            }
            next
        }
        return BatchResult(out, starsApplied)
    }

    /**
     * S17a update path: whether a matching rule's `mark_read` effect fires
     * for an updated article (`star` is NEVER applied on updates — web
     * `star = not is_update and …`). Wave E calls this from the S20a
     * update path; it is test-pinned here.
     */
    fun shouldMarkReadOnUpdate(
        fields: QueryMatcher.Fields,
        feedTags: List<String>,
        groupName: String?,
        rules: List<ActionRule>,
    ): Boolean {
        val standard = rules.filter { it.type == ActionRule.TYPE_STANDARD }
        return standard.any { rule ->
            ActionRule.EFFECT_MARK_READ in
                rule.effects.split(',').map { it.trim() }.filter { it.isNotEmpty() } &&
                QueryMatcher.matches(SearchQuery.parse(rule.query), fields, feedTags, groupName)
        }
    }
}
