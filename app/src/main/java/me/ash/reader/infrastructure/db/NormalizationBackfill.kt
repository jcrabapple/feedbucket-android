package me.ash.reader.infrastructure.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.ash.reader.domain.model.query.TextNormalizer

/**
 * S5 step 7 — one-shot, resumable Kotlin normalization backfill.
 *
 * Runs after MIGRATION_7_8 (the SQL migration adds the `*Lower` shadow
 * columns with DEFAULT '' and deliberately does NOT backfill them — SQLite
 * `lower()` is ASCII-only and would not equal the canonical Kotlin
 * normalization for non-ASCII text).
 *
 * For every article where a source column is non-empty and its shadow is
 * '', recompute via [TextNormalizer] (S12a). Batched <= 500 rows per
 * transaction; the emptiness condition IS the resume locator (no meta
 * table), so an interrupted pass resumes where it left off and a completed
 * pass is a no-op. Idempotent: safe to run on every DB open.
 */
object NormalizationBackfill {

    private const val BATCH = 500

    private val FIELD_PAIRS = listOf(
        "title" to "titleLower",
        "shortDescription" to "shortLower",
        "rawDescription" to "rawLower",
        "fullContent" to "fullLower",
    )

    /** Wired into [AndroidDatabase] via [RoomDatabase.Callback.onOpen]. */
    val CALLBACK = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            Thread {
                runCatching { run(db) }
            }.apply {
                isDaemon = true
                name = "feedbucket-normalization-backfill"
            }.start()
        }
    }

    fun run(db: SupportSQLiteDatabase) {
        for ((source, shadow) in FIELD_PAIRS) {
            backfillField(db, source, shadow)
        }
    }

    private fun backfillField(db: SupportSQLiteDatabase, source: String, shadow: String) {
        while (true) {
            db.beginTransaction()
            try {
                val rows = mutableListOf<Pair<String, String>>()
                db.query(
                    """
                    SELECT id, $source FROM article
                    WHERE $source IS NOT NULL AND $source != '' AND $shadow = ''
                    LIMIT $BATCH
                    """
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(cursor.getString(0) to cursor.getString(1))
                    }
                }
                for ((id, value) in rows) {
                    db.execSQL(
                        "UPDATE article SET $shadow = ? WHERE id = ?",
                        arrayOf(TextNormalizer.normalize(value), id)
                    )
                }
                db.setTransactionSuccessful()
                if (rows.size < BATCH) return
            } finally {
                db.endTransaction()
            }
        }
    }
}
