package me.ash.reader

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FeedBucket migration test harness (Wave A scaffold, plan task 9).
 *
 * Boots [MigrationTestHelper] against the exported Room schemas
 * (app/schemas -> androidTest assets). Wave B (S5) adds MIGRATION_7_8 and
 * turns [v7SchemaOpens] into the full v7 -> v8 Migration7To8Test: fixture
 * rows, exact schema/data/index assertions vs 8.json, FTS rebuild
 * round-trip, and behavioral trigger sync.
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
    fun v7SchemaOpens() {
        // Create an empty v7 database from the exported 7.json schema.
        helper.createDatabase(TEST_DB, 7).close()

        // Open + validate at version 7 with the current entity definitions.
        // Wave B replaces this with runMigrationsAndValidate(TEST_DB, 8, true)
        // after registering MIGRATION_7_8.
        helper.runMigrationsAndValidate(TEST_DB, 7, true)
    }

    companion object {
        private const val TEST_DB = "feedbucket-migration-test"
    }
}
