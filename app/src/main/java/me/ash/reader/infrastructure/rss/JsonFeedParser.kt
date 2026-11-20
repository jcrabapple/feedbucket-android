package me.ash.reader.infrastructure.rss

import com.rometools.modules.mediarss.MediaEntryModuleImpl
import com.rometools.modules.mediarss.types.Metadata
import com.rometools.modules.mediarss.types.Thumbnail
import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEnclosureImpl
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.feed.synd.SyndImageImpl
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * FD1 — JSON Feed 1.x parser (the 1.0 and 1.1 specifications published by
 * jsonfeed.org) producing a Rome [SyndFeed], so JSON Feeds share the exact
 * same ingest, subscribe and sync pipeline as XML feeds.
 *
 * Mapping decisions (documented for the sync pipeline):
 *  - A blank `title` falls back to the host of `home_page_url`, then the host
 *    of `feed_url`, then the host of the caller-provided source URL, then a
 *    literal placeholder — `AbstractRssRepository.subscribe` requires a
 *    non-null feed title and must never crash on a minimal JSON Feed.
 *  - `content_html` becomes a single `text/html` synd content; `content_text`
 *    becomes a `text/plain` synd content when there is no HTML body; the
 *    entry description is `summary`, or the text body when an HTML body is
 *    present. This matches what `RssHelper.buildArticleFromSyndEntry` reads
 *    (`contents` first, `description` as fallback).
 *  - `attachments` become synd enclosures (url, MIME type, size in bytes);
 *    `image` (else `banner_image`) becomes a MediaRSS thumbnail — both feed
 *    the existing `RssHelper.findThumbnail` lookup order (enclosure first,
 *    then media module).
 *  - `date_published` / `date_modified` become the entry published/updated
 *    dates (RFC 3339: offset, `Z`, local date-time or plain local date); an
 *    unparseable date degrades to null instead of failing the whole feed.
 *  - `authors` (1.1) wins over the legacy `author` object (1.0); a bare
 *    string `author` from non-conforming publishers is also accepted.
 *
 * Pure JVM code with no Android dependencies — exercised by
 * `JsonFeedParserTest`.
 */
object JsonFeedParser {

    /** Every conforming JSON Feed document carries a version under this prefix. */
    const val VERSION_PREFIX: String = "https://jsonfeed.org/version/"

    /** Body-sniff window for the `{` + jsonfeed.org prefix detection. */
    private const val SNIFF_WINDOW_CHARS = 4096

    /** Last-resort title when the document and every URL are blank. */
    private const val FALLBACK_TITLE = "JSON Feed"

    private val json = Json { ignoreUnknownKeys = true }

    /** True when the HTTP `Content-Type` advertises JSON Feed or JSON. */
    fun isJsonFeedContentType(contentType: String?): Boolean {
        val ct = contentType?.lowercase() ?: return false
        return ct.contains("application/feed+json") || ct.contains("application/json")
    }

    /** True when the trimmed body starts with `{` and mentions jsonfeed.org near the top. */
    fun looksLikeJsonFeed(text: String?): Boolean {
        val trimmed = text?.trimStart() ?: return false
        if (!trimmed.startsWith("{")) return false
        return trimmed.take(SNIFF_WINDOW_CHARS).contains("jsonfeed.org")
    }

    /** Combined detection: `Content-Type` OR body sniff (both per FD1 spec). */
    fun isJsonFeed(contentType: String?, body: ByteArray): Boolean {
        if (body.isEmpty()) return false
        val prefix = String(body, 0, minOf(body.size, SNIFF_WINDOW_CHARS), Charsets.UTF_8)
        return looksLikeJsonFeed(prefix) || isJsonFeedContentType(contentType)
    }

    /** Combined detection over an already-decoded body. */
    fun isJsonFeed(contentType: String?, bodyText: String): Boolean {
        if (looksLikeJsonFeed(bodyText)) return true
        return bodyText.isNotBlank() && isJsonFeedContentType(contentType)
    }

    /**
     * Parses a JSON Feed document into a Rome [SyndFeed].
     *
     * @param body the full document text.
     * @param sourceUrl the URL the document was fetched from; used as the
     *   title-host and link fallback when the document omits them.
     * @throws IllegalArgumentException when the body is not valid JSON or is
     *   not a JSON Feed (missing or foreign `version`).
     */
    @Throws(IllegalArgumentException::class)
    fun parse(body: String, sourceUrl: String? = null): SyndFeed {
        val root: JsonObject =
            try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed JSON Feed document", e)
            }
        val version = root.string("version")
        if (version == null || !version.startsWith(VERSION_PREFIX)) {
            throw IllegalArgumentException(
                "Not a JSON Feed document: missing jsonfeed.org version"
            )
        }

        val homePageUrl = root.string("home_page_url")?.takeIf { it.isNotBlank() }
        val feedUrl = root.string("feed_url")?.takeIf { it.isNotBlank() }
        val title =
            root.string("title")?.takeIf { it.isNotBlank() }
                ?: hostOf(homePageUrl)
                ?: hostOf(feedUrl)
                ?: hostOf(sourceUrl)
                ?: FALLBACK_TITLE

        val feed = SyndFeedImpl()
        feed.title = title
        feed.link = homePageUrl ?: feedUrl ?: sourceUrl
        feed.uri = feedUrl ?: sourceUrl
        feed.description = root.string("description")?.takeIf { it.isNotBlank() }
        feed.author = authorName(root)
        val iconUrl =
            root.string("icon")?.takeIf { it.isNotBlank() }
                ?: root.string("favicon")?.takeIf { it.isNotBlank() }
        if (iconUrl != null) {
            feed.image =
                SyndImageImpl().apply {
                    url = iconUrl
                    link = iconUrl
                    this.title = title
                }
        }
        feed.entries = root.array("items").orEmpty().map { item -> buildEntry(item) }
        return feed
    }

    private fun buildEntry(item: JsonObject): SyndEntry {
        val entry = SyndEntryImpl()
        entry.uri = item.string("id")
        entry.link =
            item.string("url")?.takeIf { it.isNotBlank() }
                ?: item.string("external_url")?.takeIf { it.isNotBlank() }
        entry.title = item.string("title")

        val contentHtml = item.string("content_html")?.takeIf { it.isNotBlank() }
        val contentText = item.string("content_text")?.takeIf { it.isNotBlank() }
        val summary = item.string("summary")?.takeIf { it.isNotBlank() }
        when {
            contentHtml != null -> {
                entry.contents = listOf(syndContent("text/html", contentHtml))
                (summary ?: contentText)?.let { entry.description = syndContent("text/plain", it) }
            }
            contentText != null -> {
                entry.contents = listOf(syndContent("text/plain", contentText))
                entry.description = syndContent("text/plain", summary ?: contentText)
            }
            summary != null -> entry.description = syndContent("text/plain", summary)
        }

        parseDate(item.string("date_published"))?.let { entry.publishedDate = it }
        parseDate(item.string("date_modified"))?.let { entry.updatedDate = it }
        authorName(item)?.let { entry.author = it }

        entry.enclosures =
            item.array("attachments").orEmpty().mapNotNull { attachment ->
                val attachmentUrl =
                    attachment.string("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SyndEnclosureImpl().apply {
                    url = attachmentUrl
                    type = attachment.string("mime_type") ?: "application/octet-stream"
                    length = (attachment["size_in_bytes"] as? JsonPrimitive)?.longOrNull ?: 0L
                }
            }

        val imageUrl =
            item.string("image")?.takeIf { it.isNotBlank() }
                ?: item.string("banner_image")?.takeIf { it.isNotBlank() }
        val imageUri = imageUrl?.let { runCatching { URI(it) }.getOrNull() }
        if (imageUri != null) {
            val metadata = Metadata()
            metadata.thumbnail = arrayOf(Thumbnail(imageUri))
            val module = MediaEntryModuleImpl()
            module.metadata = metadata
            // Copy into a MUTABLE list (preserving the DC module Rome's
            // getModules() lazily injects) — assigning an immutable listOf()
            // would make every later getAuthor()/getModule() throw, since
            // getModules() add()s a DCModuleImpl to the backing list.
            val updated = ArrayList(entry.modules)
            updated.add(module)
            entry.modules = updated
        }
        return entry
    }

    /**
     * RFC 3339 tolerant date parse: offset date-time (including a `Z`
     * suffix), offset-less local date-time (assumed UTC) or plain local date
     * (start of day UTC). Anything unparseable returns null — a bad date
     * never fails the whole feed.
     */
    internal fun parseDate(value: String?): Date? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Date.from(OffsetDateTime.parse(text).toInstant()) }
            .recoverCatching { Date.from(LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)) }
            .recoverCatching { Date.from(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()) }
            .getOrNull()
    }

    private fun authorName(container: JsonObject): String? {
        val modern =
            container.array("authors").orEmpty().firstNotNullOfOrNull { it.string("name") }
        if (!modern.isNullOrBlank()) return modern
        return when (val legacy = container["author"]) {
            is JsonObject -> legacy.string("name")?.takeIf { it.isNotBlank() }
            is JsonPrimitive -> legacy.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun hostOf(url: String?): String? =
        url?.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }

    private fun syndContent(type: String, value: String): SyndContentImpl =
        SyndContentImpl().apply {
            this.type = type
            this.value = value
        }

    /** String content of a primitive member (numbers stringify, JSON null yields null). */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.array(key: String): List<JsonObject>? =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
}
