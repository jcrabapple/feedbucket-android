package me.ash.reader.domain.model.article

import java.security.MessageDigest

/**
 * FeedBucket article identity (S6/S7/S9).
 *
 * Deterministic ids for NEW inserts: re-sync, re-import and OPML-then-migration
 * double imports converge on one row, mirroring the web
 * `UNIQUE(feed_id, external_id)` behavior (web `store_entries`, db.py:496).
 *
 * `id = "<accountId>$" + sha256hex(feedUrl + "\n" + externalId)`
 *
 * The `$` spacer convention is preserved so upstream code paths that split on
 * it keep working (S6). Existing v7 UUID rows are NOT rewritten (S2).
 */
object ArticleIdentity {

    private const val HASH_SEPARATOR = "|"

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * S6 fallback chain (web db.py:496):
     * `external_id = guid || link || sha256(title|url|author|published|contentFields)`.
     */
    fun externalId(
        guid: String?,
        link: String?,
        title: String?,
        url: String?,
        author: String?,
        published: Long?,
        summary: String?,
        content: String?,
    ): String {
        guid?.takeIf { it.isNotBlank() }?.let { return it }
        link?.takeIf { it.isNotBlank() }?.let { return it }
        return sha256Hex(
            listOf(
                title.orEmpty(),
                url.orEmpty(),
                author.orEmpty(),
                published?.toString().orEmpty(),
                summary.orEmpty(),
                content.orEmpty(),
            ).joinToString(HASH_SEPARATOR)
        )
    }

    /**
     * S6: deterministic article id. Stable across calls, devices and re-syncs
     * for the same (accountId, feedUrl, externalId).
     */
    fun articleId(accountId: Int, feedUrl: String, externalId: String): String =
        accountId.spacerDollar(sha256Hex("$feedUrl\n$externalId"))

    private fun Int.spacerDollar(str: Any): String = "$this$$str"
}
