package me.ash.reader.domain.model.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S27a — filesDir download store: layout, staging dirs, manifest schema,
 * atomic commit (single directory rename), atomic manifest rewrites, cleanup.
 * Pure JVM tests over a temp root.
 */
class DownloadStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newStore() = DownloadStore(tmp.newFolder("downloads"))

    private val accountId = 1
    private val articleId = "1\$article-uuid-1"
    private val key = DownloadStore.articleKey(articleId)

    private fun manifest(vararg assets: DownloadManifest.Asset) =
        DownloadManifest(
            schemaVersion = 1,
            textComplete = true,
            assets = assets.toList(),
            assetsComplete = assets.isNotEmpty(),
            expectedImageCount = assets.size,
        )

    // S27a: everything lands in <root>/<accountId>/<sha256(articleId)>/,
    // committed by ONE directory rename; the manifest exists only inside the
    // committed dir afterwards.
    @Test
    fun commitIsAtomicRenameAndManifestLast() {
        val store = newStore()
        val staging = store.prepareStaging(accountId, articleId)
        store.writeText(staging, "<html>body</html>")
        store.writeManifestAtomic(staging, manifest())

        assertTrue(store.commit(accountId, articleId))

        val dir = store.articleDir(accountId, articleId)
        assertTrue(dir.isDirectory)
        assertEquals("<html>body</html>", File(dir, "index.html").readText())
        assertEquals(manifest(), store.readManifest(dir))
        // staging dir must be gone after the rename
        assertFalse(staging.exists())
        assertFalse(store.stagingDir(accountId, articleId).exists())
    }

    @Test
    fun commitWithoutStagingReturnsFalseAndCreatesNothing() {
        val store = newStore()
        assertFalse(store.commit(accountId, articleId))
        assertFalse(store.articleDir(accountId, articleId).exists())
    }

    // S27a: a `ready` row whose manifest fails schema validation is demoted
    // (never rendered from a corrupt dir) — readManifest returns null.
    @Test
    fun manifestSchemaValidationRejectsGarbage() {
        val store = newStore()
        val staging = store.prepareStaging(accountId, articleId)
        store.writeText(staging, "x")
        store.writeManifestAtomic(staging, manifest())
        store.commit(accountId, articleId)

        val dir = store.articleDir(accountId, articleId)
        assertEquals(manifest(), store.readManifest(dir))

        File(dir, "manifest.json").writeText("{\"schemaVersion\":99}")
        assertNull(store.readManifest(dir))
        File(dir, "manifest.json").writeText("not json at all")
        assertNull(store.readManifest(dir))
    }

    // S27a: each asset landing rewrites the manifest atomically (tmp+rename);
    // no tmp file may remain observable.
    @Test
    fun manifestRewriteIsAtomic() {
        val store = newStore()
        val staging = store.prepareStaging(accountId, articleId)
        store.writeManifestAtomic(staging, manifest())
        val first =
            DownloadManifest.Asset(
                url = "https://example.com/a.png",
                path = "assets/a.png",
                bytes = 10,
                sha256 = "aa",
            )
        store.writeManifestAtomic(staging, manifest(first))
        val dir = staging // still staging; rewrite works in either dir
        assertEquals(manifest(first), store.readManifest(dir))
        assertFalse(File(dir, "manifest.json.tmp").exists())
    }

    @Test
    fun deleteRemovesFinalDirAndStaging() {
        val store = newStore()
        val staging = store.prepareStaging(accountId, articleId)
        store.writeText(staging, "x")
        store.writeManifestAtomic(staging, manifest())
        store.commit(accountId, articleId)
        // a stray staging dir (e.g. interrupted second attempt) also goes
        store.prepareStaging(accountId, articleId)

        store.delete(accountId, articleId)

        assertFalse(store.articleDir(accountId, articleId).exists())
        assertFalse(store.stagingDir(accountId, articleId).exists())
    }

    @Test
    fun existsRequiresCommittedDirWithValidManifest() {
        val store = newStore()
        assertFalse(store.exists(accountId, articleId))

        val staging = store.prepareStaging(accountId, articleId)
        // staging-only is NOT committed content
        assertFalse(store.exists(accountId, articleId))

        // manifest alone is not enough either: text+manifest commit together
        store.writeManifestAtomic(staging, manifest())
        assertFalse(store.commit(accountId, articleId))

        store.writeText(staging, "text")
        assertTrue(store.commit(accountId, articleId))
        assertTrue(store.exists(accountId, articleId))

        // corrupt manifest -> not exists (recovery demotes the row)
        File(store.articleDir(accountId, articleId), "manifest.json").writeText("{}")
        assertFalse(store.exists(accountId, articleId))
    }

    @Test
    fun usageBytesCountsWholeStore() {
        val store = newStore()
        val staging = store.prepareStaging(accountId, articleId)
        store.writeText(staging, "12345")
        store.writeManifestAtomic(staging, manifest())
        store.commit(accountId, articleId)
        val other = newStoreArticle(store, accountId, "1\$article-uuid-2", "ab")

        // expected: every file under the store root (text + manifests)
        val root = tmp.root.listFiles()!!.single { it.name == "downloads" }
        val precise = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        assertEquals(precise, store.usageBytes())
    }

    // S27a startup reconciliation: orphan *.staging/ dirs are garbage
    // collected; dirs in the keep set (owned by live work) survive.
    @Test
    fun gcOrphanStagingKeepsInFlightSet() {
        val store = newStore()
        store.prepareStaging(accountId, "1\$a")
        val keep = store.prepareStaging(accountId, "1\$b")
        store.prepareStaging(accountId, "1\$c")

        store.gcOrphanStaging(keepKeys = setOf(DownloadStore.articleKey("1\$b")))

        assertFalse(store.stagingDir(accountId, "1\$a").exists())
        assertTrue(keep.isDirectory)
        assertFalse(store.stagingDir(accountId, "1\$c").exists())
    }

    private fun newStoreArticle(store: DownloadStore, accountId: Int, articleId: String, text: String): String {
        val staging = store.prepareStaging(accountId, articleId)
        store.writeText(staging, text)
        store.writeManifestAtomic(staging, manifest())
        store.commit(accountId, articleId)
        return DownloadStore.articleKey(articleId)
    }
}
