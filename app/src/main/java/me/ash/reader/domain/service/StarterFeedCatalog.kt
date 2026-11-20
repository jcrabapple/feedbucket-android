package me.ash.reader.domain.service

import me.ash.reader.domain.model.discovery.DiscoveredFeed
import me.ash.reader.domain.model.discovery.DiscoverySource
import me.ash.reader.domain.model.discovery.FeedCategory
import me.ash.reader.domain.model.discovery.StarterFeed

/**
 * FD1 — the bundled curated starter catalog: 50 high-signal feeds across 8
 * browse categories. Every URL was verified live (HTTP 200 with a feed root
 * element) at authoring time, is HTTPS, and is unique across the whole
 * catalog — `StarterFeedCatalogTest`-style assertions in
 * `FeedDiscoveryServiceTest` pin these invariants.
 *
 * [search] is the keyless local half of topic search: it runs instantly,
 * works offline, and its matches are merged ahead of remote results.
 */
object StarterFeedCatalog {

    /** Browse categories in display order. Getter, not an eager alias. */
    val categories: List<FeedCategory>
        get() = CATEGORIES

    private val CATEGORIES: List<FeedCategory> =
        listOf(
            FeedCategory(
                id = "tech",
                label = "Tech",
                feeds = listOf(
                    StarterFeed("https://arstechnica.com/feed/", "Ars Technica", "Technology news and in-depth analysis"),
                    StarterFeed("https://www.theverge.com/rss/index.xml", "The Verge", "Tech, science and culture coverage"),
                    StarterFeed("https://techcrunch.com/feed/", "TechCrunch", "Startup and technology industry news"),
                    StarterFeed("https://www.engadget.com/rss.xml", "Engadget", "Consumer technology news and reviews"),
                    StarterFeed("https://www.wired.com/feed/rss", "WIRED", "Technology, science and digital culture"),
                    StarterFeed("https://hnrss.org/frontpage", "Hacker News", "Front page of the Y Combinator community"),
                    StarterFeed("https://www.technologyreview.com/feed/", "MIT Technology Review", "Emerging technology analysis from MIT"),
                ),
            ),
            FeedCategory(
                id = "security",
                label = "Security",
                feeds = listOf(
                    StarterFeed("https://krebsonsecurity.com/feed/", "Krebs on Security", "In-depth data breach and cybercrime reporting"),
                    StarterFeed("https://www.schneier.com/feed/", "Schneier on Security", "Security and privacy essays from Bruce Schneier"),
                    StarterFeed("https://feeds.feedburner.com/TheHackersNews", "The Hacker News", "Information security news and vulnerabilities"),
                    StarterFeed("https://www.bleepingcomputer.com/feed/", "BleepingComputer", "Security alerts, ransomware and how-to guides"),
                    StarterFeed("https://www.troyhunt.com/rss/", "Troy Hunt", "Web security writing from Have I Been Pwned"),
                    StarterFeed("https://isc.sans.edu/rssfeed_full.xml", "SANS Internet Storm Center", "Daily diary and threat alerts"),
                ),
            ),
            FeedCategory(
                id = "programming",
                label = "Programming",
                feeds = listOf(
                    StarterFeed("https://github.blog/feed/", "The GitHub Blog", "Engineering and product news from GitHub"),
                    StarterFeed("https://martinfowler.com/feed.atom", "Martin Fowler", "Software architecture and design articles"),
                    StarterFeed("https://lobste.rs/rss", "Lobsters", "Computing-focused community link feed"),
                    StarterFeed("https://dev.to/feed", "DEV Community", "Developer articles and discussions"),
                    StarterFeed("https://stackoverflow.blog/feed/", "Stack Overflow Blog", "Engineering practices from Stack Overflow"),
                    StarterFeed("https://blog.jetbrains.com/kotlin/feed/", "Kotlin Blog", "Kotlin language and ecosystem news"),
                    StarterFeed("https://simonwillison.net/atom/everything/", "Simon Willison", "Python, Django and LLM engineering notes"),
                ),
            ),
            FeedCategory(
                id = "science",
                label = "Science",
                feeds = listOf(
                    StarterFeed("https://www.nature.com/nature.rss", "Nature News", "Science news from Nature"),
                    StarterFeed("https://api.quantamagazine.org/feed/", "Quanta Magazine", "Mathematics, physics and biology coverage"),
                    StarterFeed("https://www.nasa.gov/rss/dyn/breaking_news.rss", "NASA Breaking News", "Official NASA announcements"),
                    StarterFeed("https://phys.org/rss-feed/", "Phys.org", "Physics, nanotech and space research news"),
                    StarterFeed("https://www.sciencedaily.com/rss/top/science.xml", "ScienceDaily", "Top science research headlines"),
                    StarterFeed("https://www.space.com/feeds/all", "Space.com", "Space exploration and astronomy news"),
                ),
            ),
            FeedCategory(
                id = "world-news",
                label = "World News",
                feeds = listOf(
                    StarterFeed("https://feeds.bbci.co.uk/news/world/rss.xml", "BBC World", "BBC international news coverage"),
                    StarterFeed("https://www.theguardian.com/world/rss", "The Guardian World", "World news from The Guardian"),
                    StarterFeed("https://www.aljazeera.com/xml/rss/all.xml", "Al Jazeera", "International news and analysis"),
                    StarterFeed("https://feeds.npr.org/1001/rss.xml", "NPR News", "US and world news from NPR"),
                    StarterFeed("https://www.france24.com/en/rss", "France 24", "International breaking news"),
                    StarterFeed("https://rss.dw.com/xml/rss-en-world", "DW World", "Deutsche Welle world coverage"),
                ),
            ),
            FeedCategory(
                id = "linux-open-source",
                label = "Linux & Open Source",
                feeds = listOf(
                    StarterFeed("https://www.phoronix.com/rss.php", "Phoronix", "Linux hardware, kernel and performance news"),
                    StarterFeed("https://lwn.net/headlines/rss", "LWN.net", "Kernel, distributions and open-source policy"),
                    StarterFeed("https://www.omgubuntu.co.uk/feed", "OMG! Ubuntu", "Ubuntu apps, themes and desktop news"),
                    StarterFeed("https://archlinux.org/feeds/news/", "Arch Linux News", "Official Arch Linux announcements"),
                    StarterFeed("https://fedoramagazine.org/feed/", "Fedora Magazine", "Guides and news for Fedora Linux"),
                    StarterFeed("https://itsfoss.com/feed/", "It's FOSS", "Linux and open-source tutorials and news"),
                    StarterFeed("https://9to5linux.com/feed", "9to5Linux", "Daily Linux distribution and software news"),
                ),
            ),
            FeedCategory(
                id = "design",
                label = "Design",
                feeds = listOf(
                    StarterFeed("https://www.smashingmagazine.com/feed/", "Smashing Magazine", "Web design and development articles"),
                    StarterFeed("https://alistapart.com/main/feed/", "A List Apart", "Web standards, design and craft essays"),
                    StarterFeed("https://uxdesign.cc/feed", "UX Collective", "User experience and product design writing"),
                    StarterFeed("https://design-milk.com/feed/", "Design Milk", "Modern design across art and architecture"),
                    StarterFeed("https://typographica.org/feed/", "Typographica", "Typeface reviews and typography commentary"),
                ),
            ),
            FeedCategory(
                id = "indie-culture",
                label = "Indie & Culture",
                feeds = listOf(
                    StarterFeed("https://daringfireball.net/feeds/main", "Daring Fireball", "Independent Apple and tech commentary"),
                    StarterFeed("https://feeds.kottke.org/main", "kottke.org", "Curated interestingness across the web"),
                    StarterFeed("https://boingboing.net/feed", "Boing Boing", "Independent culture, gadgets and weirdness"),
                    StarterFeed("https://www.theatlantic.com/feed/all/", "The Atlantic", "Politics, culture and long-form essays"),
                    StarterFeed("https://www.themarginalian.org/feed/", "The Marginalian", "Literature, philosophy and art reflections"),
                    StarterFeed("https://www.metafilter.com/rss.xml", "MetaFilter", "Community-curated web links and discussion"),
                ),
            ),
        )

    /**
     * Keyless local topic match: a feed matches when at least one
     * whitespace-separated query token occurs in its title, description,
     * category label or URL (case-insensitive). Results are ordered by the
     * number of matched tokens (strongest first), then catalog order —
     * deterministic and offline-safe.
     */
    fun search(query: String): List<DiscoveredFeed> {
        val tokens =
            query
                .lowercase()
                .split(' ', '\t', '\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return CATEGORIES
            .flatMap { category ->
                category.feeds.map { feed ->
                    val haystack =
                        buildString {
                            append(feed.title.lowercase())
                            append('\n')
                            append(feed.description?.lowercase().orEmpty())
                            append('\n')
                            append(category.label.lowercase())
                            append('\n')
                            append(feed.url.lowercase())
                        }
                    feed to tokens.count { haystack.contains(it) }
                }
            }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (feed, _) -> feed.toDiscoveredFeed() }
    }

    /** Catalog entry as a discovery candidate. */
    fun StarterFeed.toDiscoveredFeed(): DiscoveredFeed =
        DiscoveredFeed(
            url = url,
            title = title,
            description = description,
            siteUrl = null,
            iconUrl = null,
            format = DiscoveredFeed.guessFormat(url),
            subscribers = null,
            source = DiscoverySource.STARTER_CATALOG,
        )
}
