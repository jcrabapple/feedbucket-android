package me.ash.reader.infrastructure.rss

import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.modules.mediarss.MediaModule
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FD1 — JSON Feed parser suite: 1.0/1.1 documents, HTML and plain-text
 * bodies, attachments, thumbnail mapping, the blank-title host fallback,
 * detection heuristics and malformed-document rejection.
 */
class JsonFeedParserTest {

    private val fullFeedJson =
        """
        {
          "version": "https://jsonfeed.org/version/1.1",
          "title": "Fixture Feed",
          "home_page_url": "https://example.org/",
          "feed_url": "https://example.org/feed.json",
          "description": "A fixture description",
          "icon": "https://example.org/icon.png",
          "favicon": "https://example.org/favicon.png",
          "authors": [{"name": "Feed Author", "url": "https://example.org/author"}],
          "items": [
            {
              "id": "1001",
              "url": "https://example.org/post-1",
              "title": "Post One",
              "content_html": "<p>Hello <b>World</b></p>",
              "content_text": "Hello World",
              "summary": "First post",
              "image": "https://example.org/img1.png",
              "banner_image": "https://example.org/banner1.png",
              "date_published": "2026-11-01T10:00:00-05:00",
              "date_modified": "2026-11-02T12:30:00Z",
              "authors": [{"name": "Entry Author"}],
              "attachments": [
                {
                  "url": "https://example.org/ep.mp3",
                  "mime_type": "audio/mpeg",
                  "title": "Episode",
                  "size_in_bytes": 12345678,
                  "duration_in_seconds": 1234
                }
              ]
            },
            {
              "id": 1002,
              "external_url": "https://elsewhere.example/post-2",
              "content_text": "Text only body",
              "date_published": "2026-10-30T08:00:00Z"
            }
          ]
        }
        """.trimIndent()

    // --- feed-level mapping ---------------------------------------------------

    @Test
    fun parsesVersion11FeedLevelFields() {
        val feed = JsonFeedParser.parse(fullFeedJson)
        assertEquals("Fixture Feed", feed.title)
        assertEquals("https://example.org/", feed.link)
        assertEquals("https://example.org/feed.json", feed.uri)
        assertEquals("A fixture description", feed.description)
        assertEquals("Feed Author", feed.author)
        assertNotNull(feed.image)
        assertEquals("https://example.org/icon.png", feed.image.url)
        assertEquals("https://example.org/icon.png", feed.image.link)
        assertEquals(2, feed.entries.size)
    }

    @Test
    fun faviconUsedWhenIconAbsent() {
        val feed =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1",
                 "title":"T",
                 "favicon":"https://example.org/favicon.png",
                 "items":[]}
                """.trimIndent()
            )
        assertEquals("https://example.org/favicon.png", feed.image?.url)
    }

    @Test
    fun parsesVersion10LegacyAuthorObject() {
        val feed =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1",
                 "title":"Legacy",
                 "author":{"name":"Legacy Author","url":"https://example.org/me"},
                 "items":[]}
                """.trimIndent()
            )
        assertEquals("Legacy", feed.title)
        assertEquals("Legacy Author", feed.author)
    }

    @Test
    fun parsesBareStringAuthorFromNonConformingPublishers() {
        val feed =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1",
                 "title":"T","author":"Plain Name","items":[]}
                """.trimIndent()
            )
        assertEquals("Plain Name", feed.author)
    }

    // --- entry mapping ---------------------------------------------------------

    @Test
    fun mapsHtmlEntryContentSummaryDatesAttachments() {
        val feed = JsonFeedParser.parse(fullFeedJson)
        val entry = feed.entries[0]
        assertEquals("1001", entry.uri)
        assertEquals("https://example.org/post-1", entry.link)
        assertEquals("Post One", entry.title)
        assertEquals("Entry Author", entry.author)

        assertEquals(1, entry.contents.size)
        assertEquals("text/html", entry.contents[0].type)
        assertEquals("<p>Hello <b>World</b></p>", entry.contents[0].value)
        // summary wins the description slot when an HTML body exists
        assertEquals("First post", entry.description.value)

        val published = Date.from(OffsetDateTime.parse("2026-11-01T10:00:00-05:00").toInstant())
        val modified = Date.from(OffsetDateTime.parse("2026-11-02T12:30:00Z").toInstant())
        assertEquals(published, entry.publishedDate)
        assertEquals(modified, entry.updatedDate)

        assertEquals(1, entry.enclosures.size)
        val enclosure = entry.enclosures[0]
        assertEquals("https://example.org/ep.mp3", enclosure.url)
        assertEquals("audio/mpeg", enclosure.type)
        assertEquals(12345678L, enclosure.length)
    }

    @Test
    fun mapsImageToMediaRssThumbnail() {
        val feed = JsonFeedParser.parse(fullFeedJson)
        val module = feed.entries[0].getModule(MediaModule.URI) as? MediaEntryModule
        assertNotNull("expected a media module for the item image", module)
        val thumbnails = module!!.metadata.thumbnail
        assertEquals(1, thumbnails.size)
        assertEquals("https://example.org/img1.png", thumbnails[0].url.toString())
    }

    @Test
    fun bannerImageUsedWhenImageAbsent() {
        val feed =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1.1","title":"T","items":[
                  {"id":"1","url":"https://example.org/1","banner_image":"https://example.org/b.png"}
                ]}
                """.trimIndent()
            )
        val module = feed.entries[0].getModule(MediaModule.URI) as? MediaEntryModule
        assertNotNull(module)
        assertEquals("https://example.org/b.png", module!!.metadata.thumbnail[0].url.toString())
    }

    @Test
    fun mapsTextOnlyEntryWithNumericIdAndExternalUrl() {
        val feed = JsonFeedParser.parse(fullFeedJson)
        val entry = feed.entries[1]
        assertEquals("1002", entry.uri)
        assertEquals("https://elsewhere.example/post-2", entry.link)
        assertEquals(1, entry.contents.size)
        assertEquals("text/plain", entry.contents[0].type)
        assertEquals("Text only body", entry.contents[0].value)
        assertEquals("Text only body", entry.description.value)
        assertEquals(
            Date.from(OffsetDateTime.parse("2026-10-30T08:00:00Z").toInstant()),
            entry.publishedDate,
        )
        assertTrue(entry.enclosures.isNullOrEmpty())
    }

    @Test
    fun summaryOnlyEntryBecomesDescription() {
        val feed =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1.1","title":"T","items":[
                  {"id":"s1","url":"https://example.org/s1","summary":"Just a summary"}
                ]}
                """.trimIndent()
            )
        val entry = feed.entries[0]
        assertEquals("Just a summary", entry.description.value)
        assertTrue(entry.contents.isNullOrEmpty())
    }

    @Test
    fun emptyItemsYieldsEmptyEntryList() {
        val feed =
            JsonFeedParser.parse(
                """{"version":"https://jsonfeed.org/version/1.1","title":"T","items":[]}"""
            )
        assertEquals(0, feed.entries.size)
    }

    @Test
    fun attachmentWithoutUrlIsDropped() {
        val feed =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1.1","title":"T","items":[
                  {"id":"1","url":"https://example.org/1",
                   "attachments":[{"mime_type":"audio/mpeg"},{"url":"https://example.org/a.mp3"}]}
                ]}
                """.trimIndent()
            )
        assertEquals(1, feed.entries[0].enclosures.size)
        assertEquals("https://example.org/a.mp3", feed.entries[0].enclosures[0].url)
        assertEquals("application/octet-stream", feed.entries[0].enclosures[0].type)
    }

    // --- title fallback (subscribe must never see a null title) ----------------

    @Test
    fun blankTitleFallsBackToHomePageHost() {
        val feed =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1.1",
                 "title":"   ",
                 "home_page_url":"https://example.org/blog/",
                 "items":[]}
                """.trimIndent()
            )
        assertEquals("example.org", feed.title)
    }

    @Test
    fun blankTitleFallsBackToFeedUrlHostThenSourceUrlHost() {
        val fromFeedUrl =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1.1",
                 "feed_url":"https://feeds.example/f.json","items":[]}
                """.trimIndent()
            )
        assertEquals("feeds.example", fromFeedUrl.title)

        val fromSource =
            JsonFeedParser.parse(
                """{"version":"https://jsonfeed.org/version/1.1","items":[]}""",
                sourceUrl = "https://src.example/path/feed.json",
            )
        assertEquals("src.example", fromSource.title)
    }

    @Test
    fun blankTitleWithoutAnyUrlFallsBackToLiteral() {
        val feed =
            JsonFeedParser.parse(
                """{"version":"https://jsonfeed.org/version/1.1","items":[]}"""
            )
        assertEquals("JSON Feed", feed.title)
    }

    // --- rejection ---------------------------------------------------------------

    @Test
    fun malformedJsonIsRejected() {
        for (bad in listOf("{not json", "", "   ", "[1,2,3]", "{\"version\":")) {
            try {
                JsonFeedParser.parse(bad)
                fail("expected rejection for input: $bad")
            } catch (expected: IllegalArgumentException) {
                // pass
            }
        }
    }

    @Test
    fun missingOrForeignVersionIsRejected() {
        for (
            bad in listOf(
                """{"title":"No version","items":[]}""",
                """{"version":"https://example.com/version/1","title":"Wrong","items":[]}""",
                """{"version":"1.1","title":"Bare","items":[]}""",
            )
        ) {
            try {
                JsonFeedParser.parse(bad)
                fail("expected rejection for input: $bad")
            } catch (expected: IllegalArgumentException) {
                // pass
            }
        }
    }

    // --- detection ------------------------------------------------------------------

    @Test
    fun detectsJsonFeedByContentType() {
        assertTrue(JsonFeedParser.isJsonFeedContentType("application/feed+json"))
        assertTrue(JsonFeedParser.isJsonFeedContentType("application/feed+json; charset=utf-8"))
        assertTrue(JsonFeedParser.isJsonFeedContentType("APPLICATION/JSON"))
        assertTrue(JsonFeedParser.isJsonFeedContentType("application/json; charset=UTF-8"))
        assertEquals(false, JsonFeedParser.isJsonFeedContentType("application/rss+xml"))
        assertEquals(false, JsonFeedParser.isJsonFeedContentType("text/html"))
        assertEquals(false, JsonFeedParser.isJsonFeedContentType(null))
    }

    @Test
    fun detectsJsonFeedByBodySniff() {
        assertTrue(
            JsonFeedParser.isJsonFeed(
                "text/plain",
                """  {"version":"https://jsonfeed.org/version/1.1","title":"x"}""",
            )
        )
        assertEquals(
            false,
            JsonFeedParser.isJsonFeed("text/plain", """{"title":"no marker"}"""),
        )
        assertEquals(
            false,
            JsonFeedParser.isJsonFeed(
                "text/plain",
                """<?xml version="1.0"?><rss version="2.0"><channel/></rss>""",
            ),
        )
        assertEquals(false, JsonFeedParser.isJsonFeed("application/json", ""))
    }

    @Test
    fun detectsJsonFeedBytesByContentTypeOrSniff() {
        val jsonBytes = """{"version":"https://jsonfeed.org/version/1"}""".toByteArray()
        assertTrue(JsonFeedParser.isJsonFeed("application/feed+json", jsonBytes))
        assertTrue(JsonFeedParser.isJsonFeed("text/plain", jsonBytes))
        assertEquals(false, JsonFeedParser.isJsonFeed("text/plain", "<rss/>".toByteArray()))
        assertEquals(false, JsonFeedParser.isJsonFeed("application/json", ByteArray(0)))
    }

    // --- date tolerance ----------------------------------------------------------------

    @Test
    fun parsesDateVariantsAndDegradesToNull() {
        assertEquals(
            Date.from(OffsetDateTime.parse("2026-11-01T10:00:00Z").toInstant()),
            JsonFeedParser.parseDate("2026-11-01T10:00:00Z"),
        )
        assertEquals(
            Date.from(OffsetDateTime.parse("2026-11-01T10:00:00+02:00").toInstant()),
            JsonFeedParser.parseDate("2026-11-01T10:00:00+02:00"),
        )
        // Offset-less local date-time is assumed UTC.
        assertEquals(
            Date.from(OffsetDateTime.parse("2026-11-01T10:00:00Z").toInstant()),
            JsonFeedParser.parseDate("2026-11-01T10:00:00"),
        )
        // Plain date becomes start of day UTC.
        assertEquals(
            Date.from(OffsetDateTime.parse("2026-11-01T00:00:00Z").toInstant()),
            JsonFeedParser.parseDate("2026-11-01"),
        )
        assertNull(JsonFeedParser.parseDate("not a date"))
        assertNull(JsonFeedParser.parseDate(null))
        assertNull(JsonFeedParser.parseDate("  "))
    }

    @Test
    fun unparseableItemDateDegradesToNullWithoutFailingTheFeed() {
        val feed =
            JsonFeedParser.parse(
                """
                {"version":"https://jsonfeed.org/version/1.1","title":"T","items":[
                  {"id":"1","url":"https://example.org/1","date_published":"yesterday-ish"}
                ]}
                """.trimIndent()
            )
        assertEquals(1, feed.entries.size)
        assertNull(feed.entries[0].publishedDate)
        assertEquals(
            Date.from(java.time.LocalDateTime.of(2026, 11, 1, 0, 0).toInstant(ZoneOffset.UTC)),
            JsonFeedParser.parseDate("2026-11-01"),
        )
    }
}
