package me.ash.reader.domain.model.importing

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * S2.3/S32a — one import run ledger (feedbucket full-state migration or
 * OPML). `sourceFingerprint` is the sha256 of the imported file bytes;
 * `sourcePath` is the DURABLE retained copy (filesDir/imports/<runId>.db),
 * NULL once purged. `countsJson` carries the imported/skipped/failed ledger.
 */
@Entity(tableName = "import_run", indices = [Index("state")])
data class ImportRun(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "sourceFingerprint")
    val sourceFingerprint: String,
    @ColumnInfo(name = "sourceUri")
    val sourceUri: String? = null,
    @ColumnInfo(name = "sourcePath")
    val sourcePath: String? = null,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "startedAt")
    val startedAt: Long,
    @ColumnInfo(name = "finishedAt")
    val finishedAt: Long? = null,
    @ColumnInfo(name = "countsJson", defaultValue = "{}")
    val countsJson: String = "{}",
) {

    companion object {
        const val KIND_FEEDBUCKET = "feedbucket"
        const val KIND_OPML = "opml"
        const val STATE_RUNNING = "running"
        const val STATE_COMPLETED = "completed"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
    }
}
