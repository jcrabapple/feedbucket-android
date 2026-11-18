package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.net.Download
import me.ash.reader.infrastructure.net.LatestRelease
import me.ash.reader.infrastructure.net.NetworkDataSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.Response
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * AppService update seam tests (task t_6c189857): debug builds never touch the
 * network, and downloads install only after the SHA-256 sidecar verifies.
 */
class AppServiceUpdateTest {

    private class FakeNetworkDataSource : NetworkDataSource {
        var releaseCalls = 0
        var downloadCalls = 0
        val responses = mutableMapOf<String, ResponseBody>()

        override suspend fun getReleases(url: String): Response<List<LatestRelease>> {
            releaseCalls++
            error("unexpected releases call: $url")
        }

        override suspend fun downloadFile(url: String): ResponseBody {
            downloadCalls++
            return responses[url] ?: error("unexpected download: $url")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun service(context: Context, fake: NetworkDataSource) = AppService(
        context,
        fake,
        Dispatchers.Unconfined,
        Dispatchers.Unconfined,
    )

    @Test
    fun debugApplicationIdMakesZeroNetworkCalls() = runBlocking {
        val context = mock<Context>()
        whenever(context.packageName).thenReturn("us.snakepit.feedbucket.debug")
        val fake = FakeNetworkDataSource()

        val result = service(context, fake).checkUpdate(showToast = false)

        assertNull(result)
        assertEquals(0, fake.releaseCalls)
        assertEquals(0, fake.downloadCalls)
    }

    @Test
    fun downloadEmitsFinishedWhenShaMatches() = runBlocking {
        val dir = Files.createTempDirectory("feedbucket-dl").toFile()
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(dir)
        val fake = FakeNetworkDataSource()
        val apkUrl = "https://example.test/FeedBucket-0.1.0.apk"
        val apkBytes = "fake-apk-content".toByteArray()
        fake.responses[apkUrl] = apkBytes.toResponseBody("application/octet-stream".toMediaType())
        fake.responses["$apkUrl.sha256"] =
            "${sha256Hex(apkBytes)}  FeedBucket-0.1.0.apk\n".toResponseBody("text/plain".toMediaType())

        Mockito.mockStatic(Log::class.java).use {
            val emissions = service(context, fake).downloadFile(apkUrl).toList()
            assertTrue(emissions.last() is Download.Finished)
        }
        assertTrue(File(dir, "latest.apk").exists())
        assertEquals("fake-apk-content", File(dir, "latest.apk").readText())
    }

    @Test
    fun downloadMismatchDeletesFileAndNeverFinishes() = runBlocking {
        val dir = Files.createTempDirectory("feedbucket-dl").toFile()
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(dir)
        val fake = FakeNetworkDataSource()
        val apkUrl = "https://example.test/FeedBucket-0.1.0.apk"
        fake.responses[apkUrl] =
            "fake-apk-content".toByteArray().toResponseBody("application/octet-stream".toMediaType())
        fake.responses["$apkUrl.sha256"] =
            "${"0".repeat(64)}  FeedBucket-0.1.0.apk\n".toResponseBody("text/plain".toMediaType())

        Mockito.mockStatic(Log::class.java).use {
            val emissions = service(context, fake).downloadFile(apkUrl).toList()
            assertTrue(emissions.none { it is Download.Finished })
            assertTrue(emissions.last() is Download.NotYet)
        }
        assertFalse(File(dir, "latest.apk").exists())
    }

    @Test
    fun downloadSidecarParseFailureDeletesFileAndNeverFinishes() = runBlocking {
        val dir = Files.createTempDirectory("feedbucket-dl").toFile()
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(dir)
        val fake = FakeNetworkDataSource()
        val apkUrl = "https://example.test/FeedBucket-0.1.0.apk"
        fake.responses[apkUrl] =
            "fake-apk-content".toByteArray().toResponseBody("application/octet-stream".toMediaType())
        fake.responses["$apkUrl.sha256"] = "garbage\n".toResponseBody("text/plain".toMediaType())

        Mockito.mockStatic(Log::class.java).use {
            val emissions = service(context, fake).downloadFile(apkUrl).toList()
            assertTrue(emissions.none { it is Download.Finished })
        }
        assertFalse(File(dir, "latest.apk").exists())
    }
}
