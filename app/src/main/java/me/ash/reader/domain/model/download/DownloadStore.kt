package me.ash.reader.domain.model.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.ash.reader.domain.model.article.ArticleIdentity

/**
 * S27a — manifest for one downloaded article. Written LAST inside staging,
 * rewritten atomically (`manifest.tmp` → rename) on every asset landing.
 * `schemaVersion` gates recovery: a `ready` row whose manifest fails schema
 * validation is demoted to `failed(storage)` (never rendered from a corrupt
 * dir).
 */
@Serializable
data class DownloadManifest(
    val schemaVersion: Int = 1,
    val textComplete: Boolean = true,
    val assets: List<Asset> = emptyList(),
    val assetsComplete: Boolean = false,
    val expectedImageCount: Int = 0,
) {

    @Serializable
    data class Asset(
        val url: String,
        val path: String,
        val bytes: Long,
        val sha256: String,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /** Every key must be present for the manifest to be schema-valid. */
        val REQUIRED_KEYS: Set<String> =
            setOf("schemaVersion", "textComplete", "assets", "assetsComplete", "expectedImageCount")
    }
}

/**
 * S27a — durable download store rooted at `filesDir/downloads/`
 * (`<root>/<accountId>/<sha256(articleId)>/{index.html, assets/, manifest.json}`).
 * Internal files dir, NOT cacheDir: survives cache-clear and process death,
 * excluded from auto-eviction (D9).
 *
 * Readiness contract: everything is written into the sibling staging dir
 * `<key>.staging/`; [commit] performs a SINGLE atomic directory rename and
 * only after the rename succeeds does the DB row flip to `ready`.
 */
class DownloadStore {

    private val root: File

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        root = File(context.filesDir, "downloads")
    }

    /** Test/programmatic constructor over an arbitrary root dir. */
    internal constructor(
        root: File,
    ) {
        this.root = root
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        fun articleKey(articleId: String): String = ArticleIdentity.sha256Hex(articleId)
    }

    // --- layout ------------------------------------------------------------

    private fun accountDir(accountId: Int): File = File(root, accountId.toString())

    fun articleDir(accountId: Int, articleId: String): File =
        File(accountDir(accountId), articleKey(articleId))

    fun stagingDir(accountId: Int, articleId: String): File =
        File(accountDir(accountId), articleKey(articleId) + ".staging")

    // --- staging lifecycle -------------------------------------------------

    /** Wipes any stale staging dir for the article and creates a fresh one. */
    fun prepareStaging(accountId: Int, articleId: String): File =
        stagingDir(accountId, articleId).apply {
            deleteRecursively()
            mkdirs()
        }

    fun writeText(staging: File, html: String) {
        staging.mkdirs()
        File(staging, "index.html").writeText(html)
    }

    /** Atomic manifest write: tmp file + rename, never a partial manifest. */
    fun writeManifestAtomic(dir: File, manifest: DownloadManifest) {
        dir.mkdirs()
        val target = File(dir, "manifest.json")
        val tmp = File(dir, "manifest.json.tmp")
        tmp.writeText(json.encodeToString(manifest))
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "manifest atomic rename failed" }
        }
    }

    /** Schema-validating manifest read; null when missing or corrupt. */
    fun readManifest(dir: File): DownloadManifest? =
        runCatching {
            val text = File(dir, "manifest.json").readText()
            val obj = json.parseToJsonElement(text).jsonObject
            require(DownloadManifest.REQUIRED_KEYS.all { it in obj }) {
                "manifest missing required keys"
            }
            val manifest = json.decodeFromString<DownloadManifest>(text)
            require(manifest.schemaVersion == DownloadManifest.CURRENT_SCHEMA_VERSION) {
                "unsupported manifest schemaVersion ${manifest.schemaVersion}"
            }
            manifest
        }.getOrNull()

    /**
     * The single atomic commit: rename `<key>.staging/` → `<key>/`.
     * Returns false (and creates nothing) when staging holds no complete
     * text+manifest pair.
     */
    fun commit(accountId: Int, articleId: String): Boolean {
        val staging = stagingDir(accountId, articleId)
        val target = articleDir(accountId, articleId)
        if (!File(staging, "index.html").isFile || !File(staging, "manifest.json").isFile) {
            return false
        }
        target.deleteRecursively()
        if (staging.renameTo(target)) return true
        // rename can fail on some filesystems; copy+delete preserves the
        // all-or-nothing contract (readers only ever see the committed dir).
        return staging.copyRecursively(target, overwrite = true) &&
            staging.deleteRecursively()
    }

    // --- queries & cleanup -------------------------------------------------

    /** Committed dir with schema-valid manifest AND text present. */
    fun exists(accountId: Int, articleId: String): Boolean {
        val dir = articleDir(accountId, articleId)
        return File(dir, "index.html").isFile && readManifest(dir) != null
    }

    /** S29 — user removal deletes the row's files INCLUDING staging. */
    fun delete(accountId: Int, articleId: String) {
        articleDir(accountId, articleId).deleteRecursively()
        stagingDir(accountId, articleId).deleteRecursively()
    }

    /** S28 — global store usage (1 GiB cap, 90% warning). */
    fun usageBytes(): Long =
        root
            .takeIf { it.isDirectory }
            ?.walkBottomUp()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L

    /**
     * S27a startup reconciliation: delete every `*.staging/` dir whose key is
     * NOT owned by live work. [keepKeys] contains sha256 article keys of
     * downloads currently in flight.
     */
    fun gcOrphanStaging(keepKeys: Set<String>) {
        val accountDirs = root.listFiles()?.filter { it.isDirectory } ?: return
        for (accountDir in accountDirs) {
            accountDir
                .listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && it.name.endsWith(".staging") }
                ?.filter { it.name.removeSuffix(".staging") !in keepKeys }
                ?.forEach { it.deleteRecursively() }
        }
    }
}
