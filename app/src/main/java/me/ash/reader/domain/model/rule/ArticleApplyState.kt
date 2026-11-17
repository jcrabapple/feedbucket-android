package me.ash.reader.domain.model.rule

import java.util.Date

/**
 * S18a — the before-state of one matched article, captured inside the
 * apply transaction. `stateVersionAtApply` records the POST-apply (bumped)
 * version from the same apply transaction (Gate A resolution,
 * READINESS-v1.md): only later user edits or later runs change it, so
 * storing the pre-apply version would make every undo skip.
 */
data class ArticleApplyState(
    val id: String,
    val isUnread: Boolean,
    val isStarred: Boolean,
    val isUpdated: Boolean,
    val readAt: Date?,
    val stateVersion: Long,
)
