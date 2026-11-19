package me.ash.reader.infrastructure.rss

import android.content.Context
import android.util.Log
import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import com.rometools.modules.mediarss.types.UrlReference
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndImageImpl
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import java.io.ByteArrayInputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleIdentity
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.ui.ext.currentAccountId
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.isFuture
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.executeAsync
import okhttp3.internal.commonIsSuccessful
import okio.IOException
import org.jsoup.Jsoup

val enclosureRegex = """<enclosure\s+url="([^"]+)"\s+type=".*"\s*/>""".toRegex()
val imgRegex = """img.*?src=(["'])((?!data).*?)\1""".toRegex(RegexOption.DOT_MATCHES_ALL)

/** Some operations on RSS. */
class RssHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
) {

    data class SearchFeedResult(
        val feed: SyndFeed,
        val feedLink: String,
    )

    companion object {

        /**
         * S6/S7/S20a ingest identity — pure function (unit-testable, no
         * android dependencies). [guid] is the publisher guid
         * (`SyndEntry.uri ?: SyndEntry.link`); [publishedAtMillis] is the
         * future-guarded publisher date (NULL when the feed supplies none).
         */
        fun ingestArticleId(
            accountId: Int,
            feedUrl: String,
            guid: String?,
            link: String?,
            title: String?,
            author: String?,
            publishedAtMillis: Long?,
            summary: String?,
            content: String?,
        ): String = ArticleIdentity.articleId(
            accountId = accountId,
            feedUrl = feedUrl,
            externalId = ArticleIdentity.externalId(
                guid = guid,
                link = link,
                title = title,
                url = link,
                author = author,
                published = publishedAtMillis,
                summary = summary,
                content = content,
            ),
        )
    }

    @Throws(Exception::class)
    suspend fun searchFeed(feedLink: String): SearchFeedResult {
        return withContext(ioDispatcher) {
            val directResponse = response(okHttpClient, feedLink)
            if (!directResponse.commonIsSuccessful) throw IOException(directResponse.message)
            val directBody = directResponse.body.bytes()
            val directHttpContentType = toHttpContentType(directResponse.header("Content-Type"))

            val parsedDirectFeed = runCatching { parseFeed(directBody, directHttpContentType) }.getOrNull()

            val resolvedFeedLink =
                if (parsedDirectFeed != null) feedLink
                else discoverFeedLink(feedLink, directBody)
                    ?: throw IOException("Unable to detect RSS feed URL")


            val feed = parsedDirectFeed ?: run {
                val discoveredResponse = response(okHttpClient, resolvedFeedLink)
                if (!discoveredResponse.commonIsSuccessful) {
                    throw IOException(discoveredResponse.message)
                }
                parseFeed(
                    discoveredResponse.body.bytes(),
                    toHttpContentType(discoveredResponse.header("Content-Type")),
                )
            }

            feed.also {
                it.icon = SyndImageImpl()
                it.icon.link = queryRssIconLink(resolvedFeedLink)
                it.icon.url = it.icon.link
            }

            SearchFeedResult(feed = feed, feedLink = resolvedFeedLink)
        }
    }

    private fun toHttpContentType(contentType: String?): String =
        contentType?.let {
            if (it.contains("charset=", ignoreCase = true)) it else "$it; charset=UTF-8"
        } ?: "text/xml; charset=UTF-8"

    private fun parseFeed(body: ByteArray, httpContentType: String): SyndFeed =
        ByteArrayInputStream(body).use { inputStream ->
            SyndFeedInput().build(XmlReader(inputStream, httpContentType))
        }

    private fun discoverFeedLink(pageUrl: String, body: ByteArray): String? {
        val document = Jsoup.parse(String(body, Charsets.UTF_8), pageUrl)
        val links = document.select("head link[rel~=(?i)alternate][href]")
        val preferred =
            links.firstOrNull {
                val type = it.attr("type").lowercase(Locale.ROOT)
                type == "application/rss+xml" ||
                    type == "application/atom+xml" ||
                    type == "application/rdf+xml"
            }
        val fallback = links.firstOrNull()
        return (preferred ?: fallback)?.absUrl("href")?.takeIf { it.isNotBlank() }
    }

    suspend fun queryRssXml(
        feed: Feed,
        latestLink: String?,
        preDate: Date = Date(),
    ): List<Article> =
        try {
            val accountId = context.currentAccountId
            val response = response(okHttpClient, feed.url)
            val contentType = response.header("Content-Type")

            val httpContentType =
                contentType?.let {
                    if (it.contains("charset=", ignoreCase = true)) it
                    else "$it; charset=UTF-8"
                } ?: "text/xml; charset=UTF-8"

            response.body.byteStream().use { inputStream ->
                SyndFeedInput()
                    .apply { isPreserveWireFeed = true }
                    .build(XmlReader(inputStream, httpContentType))
                    .entries
                    .asSequence()
                    .takeWhile { latestLink == null || latestLink != it.link }
                    .map { buildArticleFromSyndEntry(feed, accountId, it, preDate) }
                    .toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RLog", "queryRssXml[${feed.name}]: ${e.message}")
            listOf()
        }

    fun buildArticleFromSyndEntry(
        feed: Feed,
        accountId: Int,
        syndEntry: SyndEntry,
        preDate: Date = Date(),
    ): Article {
        val desc = syndEntry.description?.value
        val content =
            syndEntry.contents
                .takeIf { it.isNotEmpty() }
                ?.let { it.joinToString("\n") { it.value } }
        val guid = syndEntry.uri ?: syndEntry.link
        val publisherDate =
            (syndEntry.publishedDate ?: syndEntry.updatedDate)?.takeIf { !it.isFuture(preDate) }
        val title = syndEntry.title.decodeHTML() ?: feed.name
        val link = syndEntry.link ?: ""
        val raw = content ?: desc ?: ""
        val short = Readability.parseToText(desc ?: content, syndEntry.link).take(280)
        return Article(
            // S6: deterministic id — stable across re-syncs/imports for the
            // same (feedUrl, externalId). guid = uri ?: link (S7 identity).
            id = ingestArticleId(
                accountId = accountId,
                feedUrl = feed.url,
                guid = guid,
                link = link,
                title = title,
                author = syndEntry.author,
                publishedAtMillis = publisherDate?.time,
                summary = short,
                content = raw,
            ),
            accountId = accountId,
            feedId = feed.id,
            date = publisherDate ?: preDate,
            title = title,
            author = syndEntry.author,
            rawDescription = raw,
            shortDescription = short,
            img = findThumbnail(syndEntry) ?: findThumbnail(content ?: desc),
            link = link,
            updateAt = preDate,
            guid = guid ?: "",
            // S20a: publisher-date provenance; never re-minted from sync time.
            publishedAt = publisherDate,
        )
    }

    fun findThumbnail(syndEntry: SyndEntry): String? {
        if (syndEntry.enclosures?.firstOrNull()?.url != null) {
            return syndEntry.enclosures.first().url
        }

        val mediaModule = syndEntry.getModule(MediaModule.URI) as? MediaEntryModule
        if (mediaModule != null) {
            return findThumbnail(mediaModule)
        }

        return null
    }

    private fun findThumbnail(mediaModule: MediaEntryModule): String? {
        val candidates =
            buildList {
                    add(mediaModule.metadata)
                    addAll(mediaModule.mediaGroups.map { mediaGroup -> mediaGroup.metadata })
                    addAll(mediaModule.mediaContents.map { content -> content.metadata })
                }
                .flatMap { it.thumbnail.toList() }

        val thumbnail = candidates.firstOrNull()

        if (thumbnail != null) {
            return thumbnail.url.toString()
        } else {
            val imageMedia = mediaModule.mediaContents.firstOrNull { it.medium == "image" }
            if (imageMedia != null) {
                return (imageMedia.reference as? UrlReference)?.url.toString()
            }
        }
        return null
    }

    fun findThumbnail(text: String?): String? {
        text ?: return null
        val enclosure = enclosureRegex.find(text)?.groupValues?.get(1)
        if (enclosure?.isNotBlank() == true) {
            return enclosure
        }
        // From https://gitlab.com/spacecowboy/Feeder
        // Using negative lookahead to skip data: urls, being inline base64
        // And capturing original quote to use as ending quote
        // Base64 encoded images can be quite large - and crash database cursors
        return imgRegex.find(text)?.groupValues?.get(2)?.takeIf { !it.startsWith("data:") }
    }

    suspend fun queryRssIconLink(feedLink: String?): String? {
        if (feedLink.isNullOrEmpty()) return null
        val iconFinder = BestIconFinder(okHttpClient)
        val domain = feedLink.extractDomain()
        return iconFinder.findBestIcon(domain ?: feedLink).also {
            Log.i("RLog", "queryRssIconByLink: get $it from $domain")
        }
    }

    suspend fun saveRssIcon(feedDao: FeedDao, feed: Feed, iconLink: String) {
        feedDao.update(feed.copy(icon = iconLink))
    }

    private suspend fun response(client: OkHttpClient, url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).build()).executeAsync()
}
