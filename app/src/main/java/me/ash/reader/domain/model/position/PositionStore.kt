package me.ash.reader.domain.model.position

import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.repository.PositionDao

/**
 * S24a (amended, R8) — canonical, INJECTIVE scope key for durable list positions.
 *
 * `scopeKey = "v1/" + [view, feedId ?: "", groupId ?: "", savedSearchId ?: "", query, sort]
 * .joinToString("/") { pctEncode(it) }`, where [pctEncode] percent-encodes every byte
 * outside RFC 3986 unreserved (`A-Z a-z 0-9 - . _ ~`). Because the delimiter `/` and
 * every metacharacter (`%`, `|`, `-`, …) are themselves encoded, slot structure can
 * never collide with slot content: an empty slot (`""`), a literal `-`, and a query
 * containing `/` all map to distinct keys. The `v1/` prefix versions the serialization.
 */
object ScopeKey {
    const val VERSION_PREFIX = "v1/"
    const val SLOT_COUNT = 6

    private val UNRESERVED =
        (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()

    fun pctEncode(value: String): String {
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in UNRESERVED) out.append(c) else out.append('%').append("%02X".format(b))
        }
        return out.toString()
    }

    fun pctDecode(value: String): String? {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 3 > value.length) return null
                val hex = value.substring(i + 1, i + 3)
                val byte = hex.toIntOrNull(16) ?: return null
                bytes.add(byte.toByte())
                i += 3
            } else {
                // Encoded form is pure ASCII; any non-ASCII char means corruption.
                if (c.code > 0x7F) return null
                bytes.add(c.code.toByte())
                i += 1
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    fun encode(
        view: String,
        feedId: String?,
        groupId: String?,
        savedSearchId: String?,
        query: String,
        sort: String,
    ): String =
        VERSION_PREFIX +
            listOf(view, feedId ?: "", groupId ?: "", savedSearchId ?: "", query, sort)
                .joinToString("/") { pctEncode(it) }

    /** Returns the six decoded slots, or `null` when [key] is not a well-formed v1 key. */
    fun decode(key: String): List<String>? {
        if (!key.startsWith(VERSION_PREFIX)) return null
        val body = key.substring(VERSION_PREFIX.length)
        val slots = body.split('/')
        if (slots.size != SLOT_COUNT) return null
        return slots.map { pctDecode(it) ?: return null }
    }
}

/** The scope a list position belongs to (S24a slot model; S25a sticky scope). */
data class PositionScope(
    val view: String,
    val feedId: String? = null,
    val groupId: String? = null,
    val savedSearchId: String? = null,
    val query: String = "",
    val sort: String = "desc",
) {
    val key: String
        get() = ScopeKey.encode(view, feedId, groupId, savedSearchId, query, sort)
}

/** A resolved restore target: list index plus pixel offset (S24a). */
data class RestoredPosition(val itemIndex: Int, val itemOffsetPx: Int)

/**
 * S24a/S26 — durable list positions over [PositionDao].
 *
 * Restore order: exact [anchorArticleId] if still in scope → else [anchorItemIndex]
 * clamped to the list → else `null` (top). Missing or corrupt rows never crash; they
 * fall back to top. [prune] drops rows older than [RETENTION_MS] (30 days, S24a).
 */
@Singleton
class PositionStore
@Inject
constructor(
    private val positionDao: PositionDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun persist(
        scope: PositionScope,
        anchorArticleId: String?,
        anchorItemIndex: Int,
        anchorOffsetPx: Int,
    ) {
        if (anchorItemIndex < 0) return
        positionDao.upsertListPosition(
            ListPosition(
                scopeKey = scope.key,
                anchorArticleId = anchorArticleId,
                anchorItemIndex = anchorItemIndex,
                anchorOffsetPx = anchorOffsetPx,
                updatedAt = now(),
            )
        )
    }

    /**
     * Resolves the restore target for [scope]. [itemIds] is the current in-scope
     * ordered id list (available at generation roll under S25a); pass the loaded
     * ids when the full list is unavailable.
     */
    suspend fun restore(scope: PositionScope, itemIds: List<String>): RestoredPosition? {
        val row =
            try {
                positionDao.queryListPosition(scope.key)
            } catch (_: Exception) {
                return null
            } ?: return null

        if (row.anchorItemIndex < 0) return null

        val anchorId = row.anchorArticleId
        if (anchorId != null) {
            val index = itemIds.indexOf(anchorId)
            if (index != -1) {
                return RestoredPosition(itemIndex = index, itemOffsetPx = row.anchorOffsetPx)
            }
        }
        if (itemIds.isEmpty()) return null
        val clamped = row.anchorItemIndex.coerceIn(0, itemIds.size - 1)
        return RestoredPosition(itemIndex = clamped, itemOffsetPx = row.anchorOffsetPx)
    }

    suspend fun prune() {
        positionDao.pruneListPositions(now() - RETENTION_MS)
    }

    companion object {
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
