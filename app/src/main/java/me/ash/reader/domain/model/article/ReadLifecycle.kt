package me.ash.reader.domain.model.article

/**
 * S21a/S23 — the read-lifecycle transition authority: WHAT each read-state
 * transition writes. The ArticleDao writers ([me.ash.reader.domain.repository.ArticleDao])
 * implement exactly this table in one transaction per write, bumping
 * `stateVersion` on every touched row (S18a choke point). Instrumented tests
 * verify the SQL against a real DB; [ReadLifecycleTest] pins the policy here.
 */
enum class ReadFieldChange { SET, CLEAR, UNCHANGED }

data class ReadStateDelta(
    /** Target `isUnread`; null leaves the flag unchanged. */
    val isUnread: Boolean?,
    /** readAt is SET to "now" on mark-read and CLEARED on mark-unread (web set_read parity). */
    val readAt: ReadFieldChange,
    /** isUpdated is drained (cleared) on mark-read only (S21a); never set here. */
    val isUpdated: ReadFieldChange,
    /** Every state write bumps stateVersion (S18a). */
    val bumpsStateVersion: Boolean = true,
)

object ReadLifecycle {

    /** S23 — mark-read: isUnread=0, readAt=now, isUpdated drained, version bumped. */
    val MARK_READ = ReadStateDelta(
        isUnread = false,
        readAt = ReadFieldChange.SET,
        isUpdated = ReadFieldChange.CLEAR,
    )

    /** Mark-unread: isUnread=1, readAt cleared (S21a); isUpdated untouched. */
    val MARK_UNREAD = ReadStateDelta(
        isUnread = true,
        readAt = ReadFieldChange.CLEAR,
        isUpdated = ReadFieldChange.UNCHANGED,
    )

    /**
     * S23 — opening an article produces [MARK_READ] only when mark-read-on-open
     * is enabled AND the article was unread; otherwise no write fires.
     */
    fun onOpen(markReadOnOpen: Boolean, wasUnread: Boolean): ReadStateDelta? =
        if (markReadOnOpen && wasUnread) MARK_READ else null
}
