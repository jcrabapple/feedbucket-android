package me.ash.reader.domain.model.discovery

import com.rometools.rome.feed.synd.SyndFeed

/**
 * FD1 — one row of a [FeedPreview]: the latest entries of a candidate feed,
 * flattened for the preview UI. [publishedAt] is epoch millis (publisher date
 * preferred, updated date as fallback) or null when the feed supplies none.
 */
data class PreviewEntry(
    val title: String,
    val link: String?,
    val publishedAt: Long?,
)

/**
 * FD1 — a candidate feed parsed for previewing before subscribing. [feed] is
 * the full Rome wire model (title, description, icon, all entries); [feedUrl]
 * is the RESOLVED feed URL to subscribe to (which may differ from the entered
 * URL when the entered URL was an HTML page); [recentEntries] are the top
 * entries for the compact preview list.
 */
data class FeedPreview(
    val feed: SyndFeed,
    val feedUrl: String,
    val recentEntries: List<PreviewEntry>,
)
