package me.ash.reader.domain.model.discovery

/**
 * FD1 — one curated entry of the bundled starter catalog. [url] is always an
 * HTTPS feed URL verified at catalog-authoring time; [description] is a short
 * human-written blurb for the browse cards.
 */
data class StarterFeed(
    val url: String,
    val title: String,
    val description: String? = null,
)

/**
 * FD1 — a browse category of the starter catalog ([id] is a stable kebab-case
 * key, [label] is the display name).
 */
data class FeedCategory(
    val id: String,
    val label: String,
    val feeds: List<StarterFeed>,
)
