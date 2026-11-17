package me.ash.reader.infrastructure.rss

import android.content.Context
import be.ceau.opml.OpmlParser
import be.ceau.opml.entity.Outline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.spacerDollar
import java.io.InputStream
import java.util.*
import javax.inject.Inject

/**
 * S33 OPML import (web `opml.py` walk parity): NESTED outlines are walked
 * recursively; folder and `category` names ACCUMULATE into the feed's tags
 * CSV (lowercase, order-preserving dedupe); the group comes from the FIRST
 * folder level (ReadYou single-display-folder parity, S4); `htmlUrl` ->
 * `siteUrl`. Feeds without `xmlUrl` are folders; the default group collects
 * top-level feeds. Duplicate `xmlUrl` values WITHIN the document are
 * skipped and counted (ON CONFLICT DO NOTHING parity, S33).
 */
class OPMLDataSource @Inject constructor(
    @ApplicationContext
    private val context: Context,
    @IODispatcher
    private val ioDispatcher: CoroutineDispatcher,
) {

    /** Parse output: grouped feeds + in-document duplicate skip count. */
    data class OpmlParseResult(
        val groupWithFeedList: List<GroupWithFeed>,
        val duplicateCount: Int,
    )

    @Throws(Exception::class)
    suspend fun parseFileInputStream(
        inputStream: InputStream,
        defaultGroup: Group,
        targetAccountId: Int,
    ): OpmlParseResult {
        val opml = OpmlParser().parse(inputStream)
        val result = ParseResult(defaultGroup)
        for (outline in opml.body.outlines) {
            walk(outline, result, firstFolder = null, inheritedTags = emptyList(), targetAccountId)
        }
        return OpmlParseResult(result.groupWithFeedList, result.duplicateCount)
    }

    private fun walk(
        outline: Outline?,
        result: ParseResult,
        firstFolder: Group?,
        inheritedTags: List<String>,
        targetAccountId: Int,
    ) {
        if (outline == null || outline.attributes == null) return
        val tags = inheritedTags + parseTags(outline.attributes["category"])
        val xmlUrl = outline.extractUrl()

        if (xmlUrl != null) {
            // S33: duplicate xmlUrl within the document is skipped+counted.
            if (!result.seenUrls.add(xmlUrl)) {
                result.duplicateCount += 1
                return
            }
            val group = firstFolder ?: result.defaultGroup
            result.addFeed(
                group,
                Feed(
                    id = targetAccountId.spacerDollar(UUID.randomUUID().toString()),
                    name = outline.extractName(),
                    url = xmlUrl,
                    groupId = group.id,
                    accountId = targetAccountId,
                    isNotification = outline.extractPresetNotification(),
                    isFullContent = outline.extractPresetFullContent(),
                    isBrowser = outline.extractPresetBrowser(),
                    siteUrl = outline.attributes["htmlUrl"] ?: "",
                    tags = tags.distinct().joinToString(","),
                ),
            )
            return
        }

        // Folder: group from the FIRST folder level only; deeper folders and
        // folder/category names accumulate into tags (web walk parity).
        val folderTags = tags + outline.extractName().trim().lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }.let { listOfNotNull(it) }
        val folder: Group? = when {
            outline.isDefaultGroup() -> firstFolder
            firstFolder == null -> {
                val group = Group(
                    id = targetAccountId.spacerDollar(UUID.randomUUID().toString()),
                    name = outline.extractName(),
                    accountId = targetAccountId,
                )
                result.addGroup(group)
                group
            }
            else -> null // deeper folder: tags only, no display group
        }
        val childFirstFolder = folder ?: firstFolder ?: result.defaultGroup
        for (subOutline in outline.subElements) {
            walk(subOutline, result, childFirstFolder, folderTags, targetAccountId)
        }
    }

    /** Web `parse_tags` port: split, trim, lowercase, drop empties, dedupe order-preserving. */
    private fun parseTags(value: String?): List<String> =
        value?.split(',')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()

    /** Ordered group list; feeds attach to their group's slot (default group first). */
    private class ParseResult(val defaultGroup: Group) {
        val groupWithFeedList = mutableListOf<GroupWithFeed>()
        val seenUrls = mutableSetOf<String>()
        var duplicateCount = 0

        init {
            groupWithFeedList.add(GroupWithFeed(group = defaultGroup, feeds = mutableListOf()))
        }

        fun addGroup(group: Group) {
            groupWithFeedList.add(GroupWithFeed(group = group, feeds = mutableListOf()))
        }

        fun addFeed(group: Group, feed: Feed) {
            val slot = groupWithFeedList.firstOrNull { it.group.id == group.id }
                ?: GroupWithFeed(group = group, feeds = mutableListOf()).also {
                    groupWithFeedList.add(it)
                }
            slot.feeds.add(feed)
        }
    }

    private fun Outline?.extractName(): String {
        if (this == null) return ""
        return attributes.getOrDefault("title", null)
            ?: text
            ?: attributes.getOrDefault("xmlUrl", null).extractDomain()
            ?: attributes.getOrDefault("htmlUrl", null).extractDomain()
            ?: attributes.getOrDefault("url", null).extractDomain()
            ?: ""
    }

    private fun Outline?.extractUrl(): String? {
        if (this == null) return null
        val url = attributes.getOrDefault("xmlUrl", null)
            ?: attributes.getOrDefault("url", null)
        return if (url.isNullOrBlank()) null else url
    }

    private fun Outline?.extractPresetNotification(): Boolean =
        this?.attributes?.getOrDefault("isNotification", null).toBoolean()

    private fun Outline?.extractPresetFullContent(): Boolean =
        this?.attributes?.getOrDefault("isFullContent", null).toBoolean()

    private fun Outline?.extractPresetBrowser(): Boolean =
        this?.attributes?.getOrDefault("isBrowser", null).toBoolean()

    private fun Outline?.isDefaultGroup(): Boolean =
        this?.attributes?.getOrDefault("isDefault", null).toBoolean()
}
