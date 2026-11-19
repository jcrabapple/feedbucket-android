package me.ash.reader.domain.service

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import me.ash.reader.infrastructure.net.SsrfGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X4 task 4 — E3 one-time legacy disk-cache hygiene, unit-tested through the
 * temp-dir seam: `cacheDir/readability` is deleted recursively on coordinator
 * init (IO dispatcher in production — `Dispatchers.Unconfined` here so the
 * delete runs synchronously), failure is ignored, and a second init with the
 * directory already gone is a no-op.
 *
 * Scratch dirs live under `build/recon` (gitignored), never `/tmp`.
 *
 * REDCHECK (recorded in the review handoff): removing the init-block delete
 * turns [initDeletesLegacyCacheDirRecursivelyAndSecondInitIsNoOp] red.
 */
class ExtractCoordinatorLegacyCacheTest {

    private fun coordinatorWith(legacyDir: File?): ExtractCoordinator =
        ExtractCoordinator(
            fetch = ExtractFetch(FakeTransport(), SsrfGuard(FakeDns()), Dispatchers.Unconfined),
            articleDao = fakeArticleDao(DaoRecorder()),
            ioDispatcher = Dispatchers.Unconfined,
            legacyCacheDir = legacyDir,
        )

    private fun reconBase(): File =
        File(System.getProperty("user.dir"), "build/recon").apply { mkdirs() }

    @Test
    fun initDeletesLegacyCacheDirRecursivelyAndSecondInitIsNoOp() {
        val legacy = Files.createTempDirectory(reconBase().toPath(), "legacy-readability").toFile()
        try {
            // Mirror the real layout: cacheDir/readability/<accountId>/<sha>.html
            assertTrue(File(legacy, "1").mkdirs())
            File(legacy, "1/deadbeef.html").writeText("<p>stale cached extraction</p>")
            assertTrue(File(legacy, "2").mkdirs())
            File(legacy, "2/cafe0001.html").writeText("<p>more stale cache</p>")
            assertTrue(legacy.exists())

            coordinatorWith(legacy) // Unconfined → delete runs inside init
            assertFalse("E3: legacy readability cache dir must be gone after init", legacy.exists())

            // Second init with the dir already removed: no-op, no exception.
            coordinatorWith(legacy)
            assertFalse(legacy.exists())
        } finally {
            legacy.deleteRecursively()
        }
    }

    @Test
    fun initWithMissingDirOrNullSeamIsNoOp() {
        val missing = File(reconBase(), "never-created-readability-${System.nanoTime()}")
        assertFalse(missing.exists())
        coordinatorWith(missing) // must not throw
        assertFalse(missing.exists())
        // Null seam (tests, non-Android hosts) skips the cleanup entirely.
        coordinatorWith(null)
    }
}
