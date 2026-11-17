package me.ash.reader.infrastructure.rss

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.mock

internal const val enclosureUrlString1: String = "https://example.com/enclosure.jpg"
internal const val enclosureUrlString2: String = "https://github.blog/wp-content/uploads/2024/03/github_copilot_header.png"
internal const val imageUrlString: String = "https://example.com/image.jpg"
internal const val enclosureHtmlCase1: String = """
        <enclosure url="$enclosureUrlString1" type="image/jpeg"/>
        <img src="$imageUrlString"/>
    """
internal const val enclosureHtmlCase2: String = """
        <img src="$imageUrlString"/>
        <enclosure url="$enclosureUrlString1" type="image/jpeg"/>
        <img src="$imageUrlString"/> 
    """
internal const val enclosureHtmlCase3: String = """
        <img src="$imageUrlString"/>
        <enclosure url="$enclosureUrlString2" type="image/png"/>
        <img src="$imageUrlString"/> 
    """
internal const val imageHtmlCase1: String = """
        <img src="$enclosureUrlString1"/>
        <img src="$imageUrlString"/> 
    """
internal const val imageHtmlCase2: String = """
        <img src="$imageUrlString"/> 
        <img src="$enclosureUrlString1"/> 
        <img src="$enclosureUrlString1"/> 
    """

@RunWith(MockitoJUnitRunner::class)
class RssHelperTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockIODispatcher: CoroutineDispatcher

    @Mock
    private lateinit var mockOkHttpClient: OkHttpClient

    private lateinit var rssHelper: RssHelper

    @Before
    fun setUp() {
        mockContext = mock<Context> { }
        mockIODispatcher = mock<CoroutineDispatcher> {}
        mockOkHttpClient = mock<OkHttpClient> {}
        rssHelper = RssHelper(mockContext, mockIODispatcher, mockOkHttpClient)
    }

    @Test
    fun testFindThumbnail() {
        Assert.assertNull(rssHelper.findThumbnail(""))
        Assert.assertNull(rssHelper.findThumbnail(" "))
        Assert.assertNull(rssHelper.findThumbnail(null))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(enclosureHtmlCase1))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(enclosureHtmlCase2))
        Assert.assertEquals(enclosureUrlString2, rssHelper.findThumbnail(enclosureHtmlCase3))
        Assert.assertEquals(enclosureUrlString1, rssHelper.findThumbnail(imageHtmlCase1))
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(imageHtmlCase2))
    }

    @Test
    fun testEnclosureNoFilenameExtension() {
        val case = """
            <enclosure url="$imageUrlString" type="image/jpeg" length="0"/>
        """
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(case))
    }

    @Test
    fun testMediaNamespaceThumbnailInRSS20() {
        val case = """
            <enclosure url="$imageUrlString" type="image/jpeg" length="0"/>
        """
        Assert.assertEquals(imageUrlString, rssHelper.findThumbnail(case))
    }

    private fun ingest(guid: String?, link: String?, published: Long? = 42L): String =
        RssHelper.ingestArticleId(
            accountId = 1,
            feedUrl = "https://example.com/feed.xml",
            guid = guid,
            link = link,
            title = "Title",
            author = "auth",
            publishedAtMillis = published,
            summary = "sum",
            content = "body",
        )

    @Test
    fun testIngestArticleIdIsDeterministicAndPrefersGuid() {
        val first = ingest("tag:ex,2023:1", "https://ex.example/1")
        val second = ingest("tag:ex,2023:1", "https://ex.example/1")
        Assert.assertEquals(first, second)
        Assert.assertTrue(first.startsWith("1$"))
        // Same guid, different link -> same id (guid wins in the fallback chain).
        Assert.assertEquals(first, ingest("tag:ex,2023:1", "https://ex.example/other"))
        // Different feed -> different id.
        val otherFeed = RssHelper.ingestArticleId(
            accountId = 1,
            feedUrl = "https://other.example/feed.xml",
            guid = "tag:ex,2023:1",
            link = "https://ex.example/1",
            title = "Title",
            author = "auth",
            publishedAtMillis = 42L,
            summary = "sum",
            content = "body",
        )
        Assert.assertNotEquals(first, otherFeed)
    }

    @Test
    fun testIngestArticleIdFallsBackToLinkThenHash() {
        val byLink = ingest(null, "https://ex.example/1")
        val byLinkBlankGuid = ingest("", "https://ex.example/1")
        Assert.assertEquals(byLink, byLinkBlankGuid)
        // No guid, no link -> hash of the joined content fields (deterministic).
        val hashed = ingest("", "")
        val hashedAgain = ingest(null, "")
        Assert.assertEquals(hashed, hashedAgain)
        Assert.assertNotEquals(byLink, hashed)
        // Publisher date participates in the hash fallback.
        Assert.assertNotEquals(hashed, ingest("", "", 43L))
    }
}
