package me.ash.reader.domain.model.query

import me.ash.reader.domain.model.group.Group

/**
 * S12a/S13 — the ONE canonical in-memory match contract (D7).
 *
 * A term matches an article iff `TextNormalizer.normalize(term)` is a
 * substring of `TextNormalizer.normalize(field)` for ANY of title,
 * shortDescription, rawDescription, fullContent (per-field OR; all terms
 * AND-ed). Deliberate improvement over web `entry_matches`
 * (actions.py:77): web concatenates fields with "\n", allowing terms to
 * match across field boundaries — the per-field OR removes that
 * false-match class.
 *
 * `tag:` matches when the normalized tag is contained in the feed's tags
 * CSV or equals the feed's group name (S4: groupId stays the display
 * folder, tags carry the full tag set).
 *
 * Also hosts S13 rule scope resolution (port of web `resolve_scope`,
 * actions.py:33).
 */
object QueryMatcher {

    data class Fields(
        val title: String?,
        val shortDescription: String?,
        val rawDescription: String?,
        val fullContent: String?,
    )

    /** Minimal feed view for S13 scope resolution (Room-entity-free). */
    data class ScopedFeed(
        val id: String,
        val groupId: String,
        val tags: String,
    )

    fun matches(
        query: SearchQuery,
        fields: Fields,
        feedTags: List<String> = emptyList(),
        groupName: String? = null,
    ): Boolean {
        query.tag?.let { tag ->
            val normalizedTag = TextNormalizer.normalize(tag)
            val tagHits = feedTags.map { TextNormalizer.normalize(it) } +
                listOfNotNull(groupName?.let { TextNormalizer.normalize(it) })
            if (normalizedTag !in tagHits) return false
        }
        val normalizedFields = listOf(
            TextNormalizer.normalize(fields.title),
            TextNormalizer.normalize(fields.shortDescription),
            TextNormalizer.normalize(fields.rawDescription),
            TextNormalizer.normalize(fields.fullContent),
        )
        return query.terms.all { term ->
            val normalizedTerm = TextNormalizer.normalize(term)
            normalizedFields.any { it.contains(normalizedTerm) }
        }
    }

    /**
     * S13 — port of `resolve_scope` (actions.py:33): `allFeeds` -> every
     * feed of the account; else the union of [feedIds] and feeds whose
     * `groupId` is in [groupIds] OR whose `tags` CSV contains any group
     * NAME resolved from [groupIds] (Android group ids map to names, then
     * match both `groupId` and `tags` per S4), intersected with the
     * existing feeds. An empty scope matches nothing (web
     * `_query_mute_filters` skips empty-scope mutes).
     */
    fun resolveScope(
        allFeeds: Boolean,
        feedIds: List<String>,
        groupIds: List<String>,
        feeds: List<ScopedFeed>,
        groups: List<Group>,
    ): Set<String> {
        val existingFeedIds = feeds.map { it.id }.toSet()
        if (allFeeds) return existingFeedIds

        val scope = mutableSetOf<String>()
        scope.addAll(feedIds)
        if (groupIds.isNotEmpty()) {
            val groupNames = groups.filter { it.id in groupIds }.map { TextNormalizer.normalize(it.name) }.toSet()
            scope.addAll(
                feeds
                    .filter { feed ->
                        feed.groupId in groupIds ||
                            groupNames.any { name ->
                                feed.tags.split(',')
                                    .map { TextNormalizer.normalize(it) }
                                    .contains(name)
                            }
                    }
                    .map { it.id }
            )
        }
        return scope intersect existingFeedIds
    }
}
