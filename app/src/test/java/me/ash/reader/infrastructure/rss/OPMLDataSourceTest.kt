package me.ash.reader.infrastructure.rss

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.LocalRssService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.ui.ext.getDefaultGroupId
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

internal const val OPML_TEMPLATE: String = """
<opml version="1.0">
    <head>
        <title>Import OPML Unit Test👿</title>
    </head>
     <body>
        {{var}}
    </body>
</opml>
"""

@RunWith(MockitoJUnitRunner::class)
class OPMLDataSourceTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockIODispatcher: CoroutineDispatcher

    private lateinit var opmlDataSource: OPMLDataSource

    private val defaultGroup = Group(id = "1", name = "Default", accountId = 1)

    private lateinit var mockObject: OPMLDataSourceTest

    @Before
    fun setUp() {
        mockContext = mock<Context> { }
        mockIODispatcher = mock<CoroutineDispatcher> {}
        opmlDataSource = OPMLDataSource(mockContext, mockIODispatcher)
    }

    private fun fill(value: String): String = OPML_TEMPLATE.replace("{{var}}", value)

    private fun parse(opml: String): List<GroupWithFeed> = runBlocking {
        opmlDataSource.parseFileInputStream(
            inputStream = opml.byteInputStream(Charsets.UTF_8),
            defaultGroup = defaultGroup,
            targetAccountId = 1
        ).groupWithFeedList
    }

    private fun parseFull(opml: String): OPMLDataSource.OpmlParseResult = runBlocking {
        opmlDataSource.parseFileInputStream(
            inputStream = opml.byteInputStream(Charsets.UTF_8),
            defaultGroup = defaultGroup,
            targetAccountId = 1
        )
    }

    @Test
    fun testEmptyTitle() {
        val opml = fill("""
            <outline text="Blogs" title="Blogs">
                <outline type="rss" xmlUrl="https://ash7.io/index.xml" htmlUrl="https://ash7.io"/>
            </outline>
        """)
        val result = parse(opml)
        Assert.assertEquals(2, result.size)
        Assert.assertEquals("Default", result[0].group.name)
        Assert.assertEquals(0, result[0].feeds.size)
        Assert.assertEquals("Blogs", result[1].group.name)
        Assert.assertEquals(1, result[1].feeds.size)
        Assert.assertEquals("ash7.io", result[1].feeds[0].name)
        Assert.assertEquals("https://ash7.io/index.xml", result[1].feeds[0].url)
    }

    @Test
    fun testTopLevelFeedUsesDefaultGroup() {
        val opml = fill("""
            <outline type="rss" text="Loose Feed" xmlUrl="https://loose.example/feed.xml"/>
        """)
        val result = parse(opml)
        Assert.assertEquals(1, result.size)
        Assert.assertEquals(defaultGroup.id, result[0].feeds[0].groupId)
        Assert.assertEquals("Loose Feed", result[0].feeds[0].name)
    }

    @Test
    fun testReadYouAttributesAndFallbackName() {
        val opml = fill("""
            <outline text="Blogs" title="Blogs">
                <outline type="rss" xmlUrl="https://ash7.io/index.xml"
                    isNotification="true" isFullContent="true" isBrowser="true"/>
            </outline>
        """)
        val feed = parse(opml)[1].feeds[0]
        Assert.assertEquals("ash7.io", feed.name)
        Assert.assertTrue(feed.isNotification)
        Assert.assertTrue(feed.isFullContent)
        Assert.assertTrue(feed.isBrowser)
    }

    @Test
    fun testNestedFolderInheritanceAndSiteUrl() {
        // S33 web opml.py walk parity: folder names accumulate into tags,
        // group comes from the FIRST folder level, htmlUrl -> siteUrl.
        val opml = fill("""
            <outline text="Tech" title="Tech">
                <outline text="Android" title="Android" category="mobile">
                    <outline type="rss" text="Droid Feed" xmlUrl="https://droid.example/feed"
                        htmlUrl="https://droid.example" category="kotlin, Mobile"/>
                </outline>
            </outline>
        """)
        val result = parse(opml)
        Assert.assertEquals(2, result.size)
        Assert.assertEquals("Default", result[0].group.name)
        Assert.assertEquals(0, result[0].feeds.size)
        Assert.assertEquals("Tech", result[1].group.name)
        Assert.assertEquals(1, result[1].feeds.size)
        val feed = result[1].feeds[0]
        // groupId = first folder level
        Assert.assertEquals(result[1].group.id, feed.groupId)
        // tags: folder Tech + folder Android's category + feed category,
        // lowercase, order-preserving dedupe
        Assert.assertEquals("tech,mobile,android,kotlin", feed.tags)
        // htmlUrl -> siteUrl
        Assert.assertEquals("https://droid.example", feed.siteUrl)
    }

    @Test
    fun testTopLevelCategoryAttrBecomesTags() {
        val opml = fill("""
            <outline type="rss" text="Loose" xmlUrl="https://loose.example/feed.xml"
                category="news, Tech ,news"/>
        """)
        val feed = parse(opml)[0].feeds[0]
        Assert.assertEquals("news,tech", feed.tags)
        Assert.assertEquals(defaultGroup.id, feed.groupId)
    }

    @Test
    fun testDuplicateXmlUrlInDocumentSkippedAndCounted() {
        // S33: duplicate xmlUrl -> skip with count (ON CONFLICT DO NOTHING
        // parity). The duplicate may appear anywhere in the document.
        val opml = fill("""
            <outline type="rss" text="First" xmlUrl="https://dup.example/feed.xml"/>
            <outline text="Folder" title="Folder">
                <outline type="rss" text="Dup" xmlUrl="https://dup.example/feed.xml"/>
                <outline type="rss" text="Other" xmlUrl="https://other.example/rss"/>
            </outline>
        """)
        val result = parseFull(opml)
        Assert.assertEquals(1, result.duplicateCount)
        val allFeeds = result.groupWithFeedList.flatMap { it.feeds }
        Assert.assertEquals(2, allFeeds.size)
        Assert.assertEquals(1, allFeeds.count { it.url == "https://dup.example/feed.xml" })
    }

    @Test
    fun testSaveToDatabaseCountsImportedAndSkippedExistingFeeds() {
        // S33 service level: in-document duplicate xmlUrl + already-
        // subscribed feeds count as skipped; only new feeds are inserted.
        val accountId = 1
        val defaultGroup = Group(
            id = accountId.getDefaultGroupId(), name = "Default", accountId = accountId,
        )
        val opml = fill("""
            <outline type="rss" text="New Feed" xmlUrl="https://new.example/feed"/>
            <outline type="rss" text="Existing" xmlUrl="https://existing.example/rss"/>
            <outline type="rss" text="Dup" xmlUrl="https://new.example/feed"/>
        """)
        val groupDao = mock<GroupDao> {
            onBlocking { queryById(any()) } doReturn defaultGroup
        }
        val inserted = mutableListOf<Feed>()
        val feedDao = mock<FeedDao> {
            onBlocking { insertList(any()) } doAnswer { invocation ->
                val list = invocation.getArgument<List<Feed>>(0)
                inserted.addAll(list)
                list.map { 0L }
            }
        }
        val accountService = mock<AccountService> {
            on { getCurrentAccountId() } doReturn accountId
        }
        val localRssService = mock<LocalRssService> {
            onBlocking { isFeedExist(any()) } doReturn false
            onBlocking { isFeedExist("https://existing.example/rss") } doReturn true
        }
        val rssService = mock<RssService> {
            on { get() } doReturn localRssService
        }
        val service = OpmlService(
            groupDao = groupDao,
            feedDao = feedDao,
            accountService = accountService,
            rssService = rssService,
            OPMLDataSource = opmlDataSource,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        val result = runBlocking { service.saveToDatabase(opml.byteInputStream(Charsets.UTF_8)) }

        Assert.assertEquals(1, result.imported)
        Assert.assertEquals(2, result.skipped)
        Assert.assertEquals(1, inserted.size)
        Assert.assertEquals("https://new.example/feed", inserted.single().url)
    }

    @Test
    fun testExportCategoryCsvAndSiteUrlRoundTrip() {
        // S33 export parity: category = feed.tags CSV; htmlUrl carries
        // siteUrl; both round-trip through the importer (htmlUrl ->
        // siteUrl, category -> tags).
        val accountId = 1
        val group = Group(id = "1\$g1", name = "Tech", accountId = accountId)
        val taggedFeed = Feed(
            id = "1\$f1", name = "Droid Feed", url = "https://droid.example/feed",
            groupId = group.id, accountId = accountId,
            siteUrl = "https://droid.example", tags = "tech,mobile",
        )
        val bareFeed = Feed(
            id = "1\$f2", name = "Plain", url = "https://plain.example/rss",
            groupId = group.id, accountId = accountId,
            siteUrl = "", tags = "",
        )
        val groupDao = mock<GroupDao> {
            onBlocking { queryById(any()) } doReturn null
            onBlocking { queryAllGroupWithFeed(any()) } doReturn
                listOf(GroupWithFeed(group, mutableListOf(taggedFeed, bareFeed)))
        }
        val accountService = mock<AccountService> {
            onBlocking { getCurrentAccount() } doReturn
                Account(name = "Synthetic", type = AccountType.Local)
        }
        val localRssService = mock<LocalRssService>()
        val rssService = mock<RssService>()
        val service = OpmlService(
            groupDao = groupDao,
            feedDao = mock(),
            accountService = accountService,
            rssService = rssService,
            OPMLDataSource = opmlDataSource,
            // saveToString now runs under withContext(ioDispatcher) (review
            // lane 3 defect 5) — a bare mocked CoroutineDispatcher cannot
            // dispatch; use a real one like the import test above.
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val exported = runBlocking { service.saveToString(accountId, attachInfo = false) }

        // category CSV export on the tagged feed; no category on the bare one.
        val reparsed = be.ceau.opml.OpmlParser().parse(exported.byteInputStream(Charsets.UTF_8))
        val outlines = reparsed.body.outlines.flatMap { it.subElements } +
            reparsed.body.outlines.filter { it.attributes["xmlUrl"] != null }
        val taggedOutline = outlines.first { it.attributes["xmlUrl"] == taggedFeed.url }
        Assert.assertEquals("tech,mobile", taggedOutline.attributes["category"])
        Assert.assertEquals("https://droid.example", taggedOutline.attributes["htmlUrl"])
        val bareOutline = outlines.first { it.attributes["xmlUrl"] == bareFeed.url }
        Assert.assertFalse(bareOutline.attributes.containsKey("category"))

        // Round-trip: the exported document imports back to identical
        // tags/siteUrl.
        val roundTripped = parseFull(exported).groupWithFeedList.flatMap { it.feeds }
        val roundTrippedTagged = roundTripped.single { it.url == taggedFeed.url }
        Assert.assertEquals("tech,mobile", roundTrippedTagged.tags)
        Assert.assertEquals("https://droid.example", roundTrippedTagged.siteUrl)
    }
}
