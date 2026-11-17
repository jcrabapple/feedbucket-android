package me.ash.reader.infrastructure.rss

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
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
}
