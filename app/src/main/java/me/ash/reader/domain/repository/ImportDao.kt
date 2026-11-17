package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.ash.reader.domain.model.importing.ImportIdentity
import me.ash.reader.domain.model.importing.ImportRun

@Dao
interface ImportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: ImportRun): Long

    @Query("SELECT * FROM import_run WHERE id = :id")
    suspend fun queryRun(id: Long): ImportRun?

    @Query("SELECT * FROM import_run WHERE state = 'running' ORDER BY id DESC LIMIT 1")
    suspend fun queryRunningRun(): ImportRun?

    @Query("UPDATE import_run SET state = :state, finishedAt = :finishedAt, countsJson = :countsJson WHERE id = :id")
    suspend fun updateRunState(id: Long, state: String, finishedAt: Long?, countsJson: String)

    @Query(
        """
        SELECT * FROM import_identity
        WHERE source = :source AND sourceId = :sourceId AND targetKind = :targetKind
        """
    )
    suspend fun queryIdentity(source: String, sourceId: String, targetKind: String): ImportIdentity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIdentities(identities: List<ImportIdentity>): List<Long>

    // --- Wave G (S32a): resume/remove/GC support. Queries below never
    // reference the web `users` table (SecretGuard-allowlisted source). ---

    /** S32a step 7 — every identity row this run CREATED (provenance set).
     *  Deterministic order: removal is a two-phase algorithm and must never
     *  depend on default DAO row order. */
    @Query("SELECT * FROM import_identity WHERE createdByRunId = :runId ORDER BY targetKind, sourceId, targetId")
    suspend fun queryCreatedIdentitiesByRun(runId: Long): List<ImportIdentity>

    /**
     * S32a steps 6/8 — the latest RETRYABLE run: 'running', or 'failed' with
     * the retained copy still on disk (retry after fingerprint mismatch /
     * apply-time failure; review round 1 finding 4). Failed runs whose copy
     * was purged are not retryable and are excluded by sourcePath IS NOT NULL.
     */
    @Query(
        """
        SELECT * FROM import_run
        WHERE state = 'running' OR (state = 'failed' AND sourcePath IS NOT NULL)
        ORDER BY id DESC LIMIT 1
        """,
    )
    suspend fun queryResumableRun(): ImportRun?

    /** S32a — all identity rows recorded by a run (removed after removal). */
    @Query("SELECT * FROM import_identity WHERE importRunId = :runId")
    suspend fun queryIdentitiesByRun(runId: Long): List<ImportIdentity>

    @Query("DELETE FROM import_identity WHERE importRunId = :runId")
    suspend fun deleteIdentitiesByRun(runId: Long)

    @Query("UPDATE import_run SET sourcePath = :sourcePath WHERE id = :id")
    suspend fun updateRunSourcePath(id: Long, sourcePath: String?)

    @Query("UPDATE import_run SET sourceFingerprint = :fingerprint WHERE id = :id")
    suspend fun updateRunFingerprint(id: Long, fingerprint: String)

    @Query("SELECT * FROM import_run WHERE sourcePath = :sourcePath LIMIT 1")
    suspend fun queryRunBySourcePath(sourcePath: String): ImportRun?

    /** Latest run of a kind (result screen after process recreation). */
    @Query("SELECT * FROM import_run WHERE kind = :kind ORDER BY id DESC LIMIT 1")
    suspend fun queryLatestRun(kind: String): ImportRun?

    /**
     * S32a steps 4/5/7 — one import feed chunk / removal runs inside a
     * SINGLE Room transaction (bounded blast radius + atomic provenance).
     * Transaction bodies live on the DAO (StateApplyDao precedent) so the
     * behavior is unit-testable with fake DAOs. A fun interface (not a bare
     * suspend lambda) keeps Room KSP type resolution happy.
     */
    @Transaction
    suspend fun runInImportTransaction(chunk: ImportChunk) {
        chunk()
    }
}

/** Transaction body for [ImportDao.runInImportTransaction]. */
fun interface ImportChunk {
    suspend operator fun invoke()
}
