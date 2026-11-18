package me.ash.reader.infrastructure.net

import me.ash.reader.domain.model.general.Version
import java.io.File
import java.security.MessageDigest

/**
 * FeedBucket release channel rules. Single source of truth is
 * `R.string.feedbucket_release_repo`; the API list endpoint and the web
 * releases page are derived from it here.
 *
 * Only releases tagged exactly `feedbucket-v<major>.<minor>.<point>` count
 * (drafts, prereleases, and upstream tags such as `v0.16.0` are skipped).
 * Assets are selected by name — `FeedBucket-<version>.apk` plus its sibling
 * `.sha256` sidecar — never by index, and the downloaded APK must match the
 * sidecar or it is deleted and never installed.
 */
object FeedBucketUpdate {

    data class Candidate(
        val version: Version,
        val log: String,
        val publishDate: String,
        val size: Int,
        val apkUrl: String,
        val shaUrl: String,
    )

    private val releaseTag = Regex("""^feedbucket-v(\d+\.\d+\.\d+)$""")

    // sha256sum sidecar format: `<64 hex>  <name>.apk`
    private val sidecarLine = Regex("""^([0-9a-fA-F]{64})\s+\S+\.apk\s*$""")

    fun releaseApiUrl(repoUrl: String): String =
        repoUrl.trimEnd('/').replace("https://github.com/", "https://api.github.com/repos/") + "/releases"

    fun releasesPageUrl(repoUrl: String): String = repoUrl.trimEnd('/') + "/releases"

    /**
     * The highest-version release on the FeedBucket channel that carries exactly
     * one `FeedBucket-<version>.apk` asset and its `.sha256` sibling; null means
     * there is no update to offer.
     */
    fun pickUpdate(releases: List<LatestRelease>): Candidate? {
        val best = releases.mapNotNull { release ->
            val tag = release.tag_name ?: return@mapNotNull null
            val match = releaseTag.matchEntire(tag) ?: return@mapNotNull null
            if (release.draft == true || release.prerelease == true) return@mapNotNull null
            release to Version(match.groupValues[1])
        }.maxWithOrNull { a, b -> a.second.compareTo(b.second) }?.first ?: return null
        val version = releaseTag.find(best.tag_name!!)!!.groupValues[1]
        val assets = best.assets.orEmpty()
        val apk = assets.filter { it.name == "FeedBucket-$version.apk" }
        val sha = assets.filter { it.name == "FeedBucket-$version.apk.sha256" }
        if (apk.size != 1 || sha.size != 1) return null
        return Candidate(
            version = Version(version),
            log = best.body ?: "",
            publishDate = best.published_at ?: best.created_at ?: "",
            size = apk[0].size ?: 0,
            apkUrl = apk[0].browser_download_url ?: return null,
            shaUrl = sha[0].browser_download_url ?: return null,
        )
    }

    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    /**
     * Verifies [file] against the `<hex>  <name>.apk` sidecar content. On a
     * mismatch or parse failure the download is deleted and false is returned,
     * so a tampered or corrupt APK can never reach the installer.
     */
    fun verifyDownload(file: File, sidecar: String): Boolean {
        val expected = sidecar.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { sidecarLine.matchEntire(it)?.groupValues?.get(1) }
        if (expected == null || !sha256(file).equals(expected, ignoreCase = true)) {
            file.delete()
            return false
        }
        return true
    }
}
