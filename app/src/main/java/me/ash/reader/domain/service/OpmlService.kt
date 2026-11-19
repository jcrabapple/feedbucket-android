package me.ash.reader.domain.service

import be.ceau.opml.OpmlWriter
import be.ceau.opml.entity.Body
import be.ceau.opml.entity.Head
import be.ceau.opml.entity.Opml
import be.ceau.opml.entity.Outline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.OPMLDataSource
import me.ash.reader.ui.ext.getDefaultGroupId
import java.io.InputStream
import java.util.*
import javax.inject.Inject

/**
 * Supports import and export from OPML files.
 */
class OpmlService @Inject constructor(
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val accountService: AccountService,
    private val rssService: RssService,
    private val OPMLDataSource: OPMLDataSource,
    @IODispatcher
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * S33 — OPML import result counts (subscriptions imported/skipped only;
     * OPML never carries reader state, D11).
     */
    data class OpmlImportResult(
        val imported: Int,
        val skipped: Int,
    )

    /**
     * Imports OPML file.
     *
     * @param [inputStream] input stream of OPML file
     * @return imported/skipped counts (in-document duplicate `xmlUrl` and
     *   already-subscribed feeds count as skipped, ON CONFLICT DO NOTHING
     *   parity)
     */
    @Throws(Exception::class)
    suspend fun saveToDatabase(inputStream: InputStream): OpmlImportResult {
        return withContext(ioDispatcher) {
            val accountId = accountService.getCurrentAccountId()
            val defaultGroup = groupDao.queryById(getDefaultGroupId(accountId))!!
            val parseResult =
                OPMLDataSource.parseFileInputStream(inputStream, defaultGroup, accountId)
            var imported = 0
            var skipped = parseResult.duplicateCount
            parseResult.groupWithFeedList.forEach { groupWithFeed ->
                if (groupWithFeed.group != defaultGroup) {
                    groupDao.insert(groupWithFeed.group)
                }
                val repeatList = mutableListOf<Feed>()
                groupWithFeed.feeds.forEach {
                    it.groupId = groupWithFeed.group.id
                    if (rssService.get().isFeedExist(it.url)) {
                        repeatList.add(it)
                    }
                }
                skipped += repeatList.size
                val inserted = (groupWithFeed.feeds subtract repeatList.toSet()).toList()
                feedDao.insertList(inserted)
                imported += inserted.size
            }
            OpmlImportResult(imported, skipped)
        }
    }

    /**
     * Exports OPML file (S33 web export parity): folder nesting from
     * `groupId` (ReadYou parity) AND `category` = `feed.tags` CSV; `htmlUrl`
     * carries `siteUrl` so consumers round-trip it back (import maps
     * `htmlUrl` -> `siteUrl`). Both consumers round-trip either form.
     */
    @Throws(Exception::class)
    suspend fun saveToString(accountId: Int, attachInfo: Boolean): String =
        withContext(ioDispatcher) {
            // Review lane 3 defect 5: OPML serialization (OpmlWriter + the
            // group/feed DAO reads) must never run on the caller's
            // dispatcher — MigrationViewModel.exportOpml() calls this from
            // viewModelScope (Main).
            val defaultGroup = groupDao.queryById(getDefaultGroupId(accountId))
            OpmlWriter().write(
                Opml(
                    "2.0",
                    Head(
                        accountService.getCurrentAccount().name,
                        Date().toString(), null, null, null,
                        null, null, null, null,
                        null, null, null, null,
                    ),
                    Body(groupDao.queryAllGroupWithFeed(accountId).map {
                        Outline(
                            mutableMapOf(
                                "text" to it.group.name,
                                "title" to it.group.name,
                            ).apply {
                                if (attachInfo) {
                                    put("isDefault", (it.group.id == defaultGroup?.id).toString())
                                }
                            },
                            it.feeds.map { feed ->
                                Outline(
                                    mutableMapOf(
                                        "text" to feed.name,
                                        "title" to feed.name,
                                        "xmlUrl" to feed.url,
                                        "htmlUrl" to feed.siteUrl.ifBlank { feed.url },
                                    ).apply {
                                        // S33: category = feed.tags (web export parity).
                                        if (feed.tags.isNotBlank()) {
                                            put("category", feed.tags)
                                        }
                                        if (attachInfo) {
                                            put("isNotification", feed.isNotification.toString())
                                            put("isFullContent", feed.isFullContent.toString())
                                            put("isBrowser", feed.isBrowser.toString())
                                        }
                                    },
                                    listOf()
                                )
                            }
                        )
                    })
                )
            )!!
        }

    private fun getDefaultGroupId(accountId: Int): String = accountId.getDefaultGroupId()
}
