package me.ash.reader

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.MIGRATION_7_8
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FeedBucket migration test harness (Wave A scaffold, plan task 9).
 *
 * Boots [MigrationTestHelper] against the exported Room schemas
 * (app/schemas -> androidTest assets). The full v7 -> v8 assertion suite
 * lives in `me.ash.reader.db.Migration7To8Test` (S5.6); this scaffold now
 * smoke-validates the 7 -> 8 path end to end.
 *
 * RED-phase note (amended, R4): before MIGRATION_7_8 existed, opening the
 * v7 fixture at version 8 threw
 * IllegalStateException("A migration from 7 to 8 was required but not
 * found…") from RoomOpenHelper — a RUNTIME failure, not a compile failure
 * (captured in the Wave B review packet).
 *
 * Runs on an explicitly selected emulator only (D15).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTestHarness {

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AndroidDatabase::class.java,
            emptyList(),
        )

    @Test
    fun v7MigratesToV8AndValidates() {
        helper.createDatabase(TEST_DB, 7).close()
        helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8).close()
    }

    companion object {
        private const val TEST_DB = "feedbucket-migration-test"
    }
}
