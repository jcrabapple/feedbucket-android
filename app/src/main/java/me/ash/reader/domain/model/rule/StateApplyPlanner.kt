package me.ash.reader.domain.model.rule

/**
 * S18a — pure decision core of the durable undo ledger (R2). The Room
 * transaction bodies (StateApplyDao defaults) call these functions, and
 * the unit tests pin them without a database.
 *
 *  - [beforeRows]: the ledger rows written in the SAME transaction as the
 *    effects; `stateVersionAtApply = stateVersion + 1` (the post-apply
 *    bumped version the row holds immediately after the effects land).
 *  - [shouldRestore]: undo restores ONLY rows whose current stateVersion
 *    still equals `stateVersionAtApply`; changed rows are skipped and
 *    counted (never a false undo).
 *  - [restoreValues]: the exact before-state to write back. `wasStarred=1`
 *    restores to starred — undo NEVER clears pre-existing stars.
 */
object StateApplyPlanner {

    fun beforeRows(runId: Long, states: List<ArticleApplyState>): List<StateApplyBefore> =
        states.map {
            StateApplyBefore(
                runId = runId,
                articleId = it.id,
                wasUnread = it.isUnread,
                wasStarred = it.isStarred,
                wasUpdated = it.isUpdated,
                readAtBefore = it.readAt?.time,
                stateVersionAtApply = it.stateVersion + 1,
            )
        }

    fun shouldRestore(currentStateVersion: Long?, stateVersionAtApply: Long): Boolean =
        currentStateVersion != null && currentStateVersion == stateVersionAtApply

    data class RestoreValues(
        val isUnread: Boolean,
        val isStarred: Boolean,
        val isUpdated: Boolean,
        val readAtMillis: Long?,
    )

    fun restoreValues(row: StateApplyBefore): RestoreValues =
        RestoreValues(
            isUnread = row.wasUnread,
            isStarred = row.wasStarred,
            isUpdated = row.wasUpdated,
            readAtMillis = row.readAtBefore,
        )
}
