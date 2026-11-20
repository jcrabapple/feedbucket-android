package me.ash.reader.domain.model.discovery

/** FD1 — which remote sources a topic search may query. */
enum class DiscoverySearchScope {
    ALL,
    ARTICLES,
    PODCASTS,
}

/**
 * FD1 — outcome of one discovery run. [feeds] is never null (possibly empty);
 * [notice] carries a NON-FATAL human-readable message (partial remote failure,
 * security rejection, nothing found) that the UI may surface without treating
 * the run as failed.
 */
data class DiscoveryResult(
    val feeds: List<DiscoveredFeed>,
    val notice: String? = null,
)

/**
 * FD1 — the classification of a raw discovery query. Platform variants carry
 * the parsed identifier so resolvers never re-parse free text.
 */
sealed interface DiscoveryQuery {

    /** Free-text keyword/topic query (routed to the topic search). */
    data object Keyword : DiscoveryQuery

    /** A direct URL or bare domain (routed to fetch + autodiscovery). */
    data class DirectUrl(val url: String) : DiscoveryQuery

    /** `r/<sub>`, `/r/<sub>` or a reddit.com subreddit URL. */
    data class RedditSubreddit(val name: String) : DiscoveryQuery

    /** A reddit.com `u/<user>` or `user/<user>` profile URL. */
    data class RedditUser(val name: String) : DiscoveryQuery

    /** `@user@domain` handle or a fediverse `https://domain/@user` URL. */
    data class MastodonProfile(val domain: String, val user: String) : DiscoveryQuery

    /** `github.com/<owner>/<repo>` (releases + commits Atom candidates). */
    data class GitHubRepo(val owner: String, val repo: String) : DiscoveryQuery

    /** `github.com/<user>` (user/org Atom candidate). */
    data class GitHubUser(val name: String) : DiscoveryQuery

    /** `youtube.com/channel/<id>` — feed URL is a pure mapping. */
    data class YouTubeChannel(val channelId: String) : DiscoveryQuery

    /** `youtube.com/@handle`, `/c/<name>` or `/user/<name>` — needs a page
     * fetch to extract the channel id. [path] keeps the raw path (for
     * example `@handle` or `c/name`). */
    data class YouTubePage(val path: String) : DiscoveryQuery

    /** `<publication>.substack.com` — feed URL is a pure mapping. */
    data class SubstackPublication(val publication: String) : DiscoveryQuery
}
