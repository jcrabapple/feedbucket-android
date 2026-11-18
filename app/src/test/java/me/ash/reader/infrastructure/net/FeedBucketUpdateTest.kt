package me.ash.reader.infrastructure.net

import me.ash.reader.domain.model.general.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * FeedBucket release channel rules (task t_6c189857): only tags matching
 * `^feedbucket-v(\d+\.\d+\.\d+)$` count, assets are selected by name (never
 * index), and the APK must match its `.sha256` sidecar or be discarded.
 */
class FeedBucketUpdateTest {

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun apkAsset(version: String) = AssetsItem(
        name = "FeedBucket-$version.apk",
        size = 4242,
        browser_download_url = "https://github.com/jcrabapple/feedbucket-android/releases/download/feedbucket-v$version/FeedBucket-$version.apk",
    )

    private fun shaAsset(version: String) = AssetsItem(
        name = "FeedBucket-$version.apk.sha256",
        browser_download_url = "https://github.com/jcrabapple/feedbucket-android/releases/download/feedbucket-v$version/FeedBucket-$version.apk.sha256",
    )

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<AssetsItem> = listOf(apkAsset("0.1.0"), shaAsset("0.1.0")),
    ) = LatestRelease(
        tag_name = tag,
        draft = draft,
        prerelease = prerelease,
        assets = assets,
        body = "changelog",
        published_at = "2026-11-17T00:00:00Z",
    )

    // --- URL derivation ---

    @Test
    fun releaseApiUrlDerivesListEndpointFromRepoUrl() {
        assertEquals(
            "https://api.github.com/repos/jcrabapple/feedbucket-android/releases",
            FeedBucketUpdate.releaseApiUrl("https://github.com/jcrabapple/feedbucket-android"),
        )
    }

    @Test
    fun releasesPageUrlDerivesWebPageFromRepoUrl() {
        assertEquals(
            "https://github.com/jcrabapple/feedbucket-android/releases",
            FeedBucketUpdate.releasesPageUrl("https://github.com/jcrabapple/feedbucket-android"),
        )
    }

    // --- Tag filter ---

    @Test
    fun tagFilterAcceptsFeedbucketVersion() {
        val candidate = FeedBucketUpdate.pickUpdate(listOf(release("feedbucket-v0.1.0")))
        assertNotNull(candidate)
        assertEquals(0, candidate!!.version.compareTo(Version("0.1.0")))
    }

    @Test
    fun tagFilterRejectsUpstreamReadYouTag() {
        assertNull(FeedBucketUpdate.pickUpdate(listOf(release("v0.16.0"))))
    }

    @Test
    fun tagFilterRejectsDrafts() {
        assertNull(FeedBucketUpdate.pickUpdate(listOf(release("feedbucket-v0.1.0", draft = true))))
    }

    @Test
    fun tagFilterRejectsPrereleases() {
        assertNull(FeedBucketUpdate.pickUpdate(listOf(release("feedbucket-v0.1.0", prerelease = true))))
    }

    @Test
    fun tagFilterRejectsTwoPartTag() {
        assertNull(FeedBucketUpdate.pickUpdate(listOf(release("feedbucket-v0.1"))))
    }

    @Test
    fun tagFilterRejectsRcSuffixTag() {
        assertNull(FeedBucketUpdate.pickUpdate(listOf(release("feedbucket-v0.1.0-rc1"))))
    }

    // --- Highest version wins ---

    @Test
    fun highestVersionWins() {
        fun releaseFor(version: String) = release(
            "feedbucket-v$version",
            assets = listOf(apkAsset(version), shaAsset(version)),
        )
        val candidates = FeedBucketUpdate.pickUpdate(
            listOf(
                releaseFor("0.2.0"),
                release("v0.16.0"), // upstream noise, must be skipped
                releaseFor("0.10.0"),
                releaseFor("0.1.0"),
            ),
        )
        assertNotNull(candidates)
        assertEquals(0, candidates!!.version.compareTo(Version("0.10.0")))
    }

    // --- Asset selection by name ---

    @Test
    fun assetSelectionByNameNotIndex() {
        val scrambled = release(
            "feedbucket-v0.1.0",
            assets = listOf(
                AssetsItem(name = "ReadYou-0.16.0-3da5837e.apk", browser_download_url = "https://evil/ReadYou.apk"),
                AssetsItem(name = "source.zip", browser_download_url = "https://example/source.zip"),
                shaAsset("0.1.0"),
                apkAsset("0.1.0"),
            ),
        )
        val candidate = FeedBucketUpdate.pickUpdate(listOf(scrambled))
        assertNotNull(candidate)
        assertTrue(candidate!!.apkUrl.endsWith("/FeedBucket-0.1.0.apk"))
        assertTrue(candidate.shaUrl.endsWith("/FeedBucket-0.1.0.apk.sha256"))
        assertEquals(4242, candidate.size)
    }

    @Test
    fun missingSidecarMeansNoUpdate() {
        val release = release("feedbucket-v0.1.0", assets = listOf(apkAsset("0.1.0")))
        assertNull(FeedBucketUpdate.pickUpdate(listOf(release)))
    }

    @Test
    fun missingApkMeansNoUpdate() {
        val release = release("feedbucket-v0.1.0", assets = listOf(shaAsset("0.1.0")))
        assertNull(FeedBucketUpdate.pickUpdate(listOf(release)))
    }

    @Test
    fun duplicateApkAssetsMeanNoUpdate() {
        val release = release(
            "feedbucket-v0.1.0",
            assets = listOf(apkAsset("0.1.0"), apkAsset("0.1.0"), shaAsset("0.1.0")),
        )
        assertNull(FeedBucketUpdate.pickUpdate(listOf(release)))
    }

    // --- SHA-256 integrity ---

    @Test
    fun shaMatchKeepsFile() {
        val dir = Files.createTempDirectory("feedbucket-sha").toFile()
        val file = File(dir, "latest.apk").apply { writeBytes("fake-apk".toByteArray()) }
        val sidecar = "${sha256Hex("fake-apk".toByteArray())}  FeedBucket-0.1.0.apk\n"
        assertTrue(FeedBucketUpdate.verifyDownload(file, sidecar))
        assertTrue(file.exists())
    }

    @Test
    fun shaMismatchDeletesFile() {
        val dir = Files.createTempDirectory("feedbucket-sha").toFile()
        val file = File(dir, "latest.apk").apply { writeBytes("fake-apk".toByteArray()) }
        val sidecar = "${"0".repeat(64)}  FeedBucket-0.1.0.apk\n"
        assertFalse(FeedBucketUpdate.verifyDownload(file, sidecar))
        assertFalse(file.exists())
    }

    @Test
    fun sidecarParseFailureDeletesFile() {
        val dir = Files.createTempDirectory("feedbucket-sha").toFile()
        val file = File(dir, "latest.apk").apply { writeBytes("fake-apk".toByteArray()) }
        assertFalse(FeedBucketUpdate.verifyDownload(file, "not a sidecar\n"))
        assertFalse(file.exists())
    }

    @Test
    fun sidecarForNonApkFilenameIsRejected() {
        val dir = Files.createTempDirectory("feedbucket-sha").toFile()
        val file = File(dir, "latest.apk").apply { writeBytes("fake-apk".toByteArray()) }
        val sidecar = "${sha256Hex("fake-apk".toByteArray())}  notes.txt\n"
        assertFalse(FeedBucketUpdate.verifyDownload(file, sidecar))
        assertFalse(file.exists())
    }
}
