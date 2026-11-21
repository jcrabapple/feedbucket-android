package me.ash.reader.domain.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Regression coverage for the live 0.1.8 crash: a truncated
 * `cache/diff/<id>/diff_map.json` (MalformedJsonException, unterminated
 * string at the 24576-byte boundary) used to escape `commitDiffsFromCache`
 * as an uncaught JsonSyntaxException on the IO dispatcher and re-crash on
 * every account emission because the corrupt file stayed on disk.
 *
 * Pins the fixed contract: corrupt/truncated/empty caches are deleted and
 * recovered without crashing, valid caches still restore and commit,
 * writes are atomic (temp file + move, never observable half-written, no
 * leftover temp files), and [Diff] carries R8-safe serialization names.
 */
class DiffMapHolderTest {

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Diff>>() {}.type

    private class Harness(
        val holder: DiffMapHolder,
        val scope: CoroutineScope,
        val repo: AbstractRssRepository,
        val uncaught: MutableList<Throwable>,
    )

    private fun newHolder(
        cacheRoot: File,
        initialAccount: Account?,
        stubRepo: (suspend (AbstractRssRepository) -> Unit)? = null,
    ): Harness {
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(cacheRoot)

        val accountService = mock<AccountService>()
        whenever(accountService.currentAccountFlow)
            .thenReturn(MutableStateFlow<Account?>(initialAccount))

        val repo = mock<AbstractRssRepository>()
        val rssService = mock<RssService>()
        whenever(rssService.get()).thenReturn(repo)
        if (stubRepo != null) {
            runBlocking { stubRepo(repo) }
        }

        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, throwable -> uncaught.add(throwable) }
        )
        val holder = DiffMapHolder(
            context, scope, Dispatchers.IO, accountService, rssService
        )
        return Harness(holder, scope, repo, uncaught)
    }

    private fun accountDir(cacheRoot: File, accountId: Int): File =
        File(cacheRoot, "diff/$accountId").apply { mkdirs() }

    private fun awaitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        var satisfied = condition()
        while (!satisfied && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            satisfied = condition()
        }
        assertTrue("$message (within 15s)", satisfied)
    }

    private fun assertNoTempFiles(dir: File) {
        assertFalse(
            "no leftover temp file in $dir",
            File(dir, "diff_map.json.tmp").exists()
        )
    }

    private fun bigDiffMap(entries: Int, idPad: Int): LinkedHashMap<String, Diff> {
        val map = LinkedHashMap<String, Diff>()
        for (i in 0 until entries) {
            val id = "article-" + i.toString().padStart(6, '0') + "-" + "x".repeat(idPad)
            map[id] = Diff(isUnread = i % 2 == 0, articleId = id, feedId = "feed-$i")
        }
        return map
    }

    // ------------------------------------------------------------------
    // 1. Truncated cache file (the live 0.1.8 crash) must not crash and
    //    must be deleted so it cannot re-crash the next account emission.
    // ------------------------------------------------------------------
    @Test
    fun truncatedCacheFileIsDeletedWithoutCrash() {
        val root = Files.createTempDirectory("diffmap-truncated").toFile()
        try {
            val accountId = 7
            val dir = accountDir(root, accountId)
            val cacheFile = File(dir, "diff_map.json")

            // Fixture: a >40K-char diff map cut mid-string near the exact
            // 24576-byte boundary from the crash report (column 24577 =
            // EOF reached inside an unterminated string).
            val full = gson.toJson(bigDiffMap(entries = 300, idPad = 120))
            assertTrue("fixture must exceed the crash boundary", full.length > 40_000)
            var cut = 24_576
            while (cut > 0 && full.substring(0, cut).count { it == '"' } % 2 == 0) {
                cut--
            }
            val truncated = full.substring(0, cut)
            var fixtureIsCorrupt = false
            try {
                gson.fromJson<Map<String, Diff>>(truncated, mapType)
            } catch (expected: JsonSyntaxException) {
                fixtureIsCorrupt = true
            }
            assertTrue("fixture must be malformed JSON", fixtureIsCorrupt)
            cacheFile.writeText(truncated)

            val harness = newHolder(root, Account(id = accountId, name = "trunc", type = AccountType.Local))
            try {
                awaitUntil("corrupt cache file is deleted") { !cacheFile.exists() }
                assertTrue(
                    "load must not crash the scope: ${harness.uncaught}",
                    harness.uncaught.isEmpty()
                )
                assertTrue("diff map stays empty", harness.holder.diffMap.isEmpty())
                assertNoTempFiles(dir)
            } finally {
                harness.scope.cancel()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 2. Empty / garbage / literal-null cache files recover the same way.
    // ------------------------------------------------------------------
    @Test
    fun emptyAndGarbageCacheFilesRecoverAndAreCleanedUp() {
        val cases = listOf(
            "garbage" to "}{ not json at all [[[",
            "empty" to "",
            "null-literal" to "null",
        )
        for ((label, content) in cases) {
            val root = Files.createTempDirectory("diffmap-$label").toFile()
            try {
                val accountId = 3
                val dir = accountDir(root, accountId)
                val cacheFile = File(dir, "diff_map.json")
                cacheFile.writeText(content)

                val harness = newHolder(root, Account(id = accountId, name = label, type = AccountType.Local))
                try {
                    awaitUntil("$label cache file is deleted") { !cacheFile.exists() }
                    assertTrue(
                        "$label load must not crash the scope: ${harness.uncaught}",
                        harness.uncaught.isEmpty()
                    )
                    assertTrue("$label keeps diff map empty", harness.holder.diffMap.isEmpty())
                    assertNoTempFiles(dir)
                } finally {
                    harness.scope.cancel()
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. A valid cache file restores its diffs and commits them to the db.
    // ------------------------------------------------------------------
    @Test
    fun validCacheFileRestoresDiffsAndCommitsThem() {
        val root = Files.createTempDirectory("diffmap-valid").toFile()
        try {
            val accountId = 5
            val dir = accountDir(root, accountId)
            val cacheFile = File(dir, "diff_map.json")
            val diffs = LinkedHashMap<String, Diff>()
            diffs["a1"] = Diff(isUnread = false, articleId = "a1", feedId = "f1")
            diffs["a2"] = Diff(isUnread = true, articleId = "a2", feedId = "f2")
            cacheFile.writeText(gson.toJson(diffs))

            val calls = CopyOnWriteArrayList<Pair<Set<String>, Boolean>>()
            val latch = CountDownLatch(2)
            val harness = newHolder(
                root,
                Account(id = accountId, name = "valid", type = AccountType.Local),
            ) { repo ->
                whenever(repo.batchMarkAsRead(any(), any())).thenAnswer { invocation ->
                    calls.add(
                        invocation.getArgument<Set<String>>(0) to
                            invocation.getArgument<Boolean>(1)
                    )
                    latch.countDown()
                    Unit
                }
            }
            try {
                assertTrue(
                    "loaded diffs are committed to the db",
                    latch.await(15, TimeUnit.SECONDS)
                )
                val markedRead = calls.filter { !it.second }.flatMap { it.first }.toSet()
                val markedUnread = calls.filter { it.second }.flatMap { it.first }.toSet()
                assertEquals(setOf("a1"), markedRead)
                assertEquals(setOf("a2"), markedUnread)
                awaitUntil("committed diffs are drained") { harness.holder.diffMap.isEmpty() }
                awaitUntil("cache file is removed after commit") { !cacheFile.exists() }
                assertTrue("no crash: ${harness.uncaught}", harness.uncaught.isEmpty())
                assertNoTempFiles(dir)
            } finally {
                harness.scope.cancel()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 4. Malformed entries Gson can materialize for damaged records (null
    //    values, missing ids) are ignored; only valid diffs are committed.
    // ------------------------------------------------------------------
    @Test
    fun malformedEntriesAreIgnoredAndOnlyValidDiffsCommit() {
        val root = Files.createTempDirectory("diffmap-partial").toFile()
        try {
            val accountId = 11
            val dir = accountDir(root, accountId)
            val cacheFile = File(dir, "diff_map.json")
            cacheFile.writeText(
                "{\"good\":{\"isUnread\":true,\"articleId\":\"good\",\"feedId\":\"f1\"}," +
                    "\"nullEntry\":null," +
                    "\"partial\":{\"isUnread\":false}}"
            )

            val calls = CopyOnWriteArrayList<Pair<Set<String>, Boolean>>()
            val latch = CountDownLatch(2)
            val harness = newHolder(
                root,
                Account(id = accountId, name = "partial", type = AccountType.Local),
            ) { repo ->
                whenever(repo.batchMarkAsRead(any(), any())).thenAnswer { invocation ->
                    calls.add(
                        invocation.getArgument<Set<String>>(0) to
                            invocation.getArgument<Boolean>(1)
                    )
                    latch.countDown()
                    Unit
                }
            }
            try {
                assertTrue(
                    "valid entry still commits",
                    latch.await(15, TimeUnit.SECONDS)
                )
                val committedIds = calls.flatMap { it.first }.toSet()
                assertEquals(setOf("good"), committedIds)
                assertTrue(
                    "the unread diff for the valid entry is committed as unread",
                    calls.any { it.second && it.first == setOf("good") }
                )
                awaitUntil("diff map drains after commit") { harness.holder.diffMap.isEmpty() }
                awaitUntil("cache file is removed after commit") { !cacheFile.exists() }
                assertTrue("no crash: ${harness.uncaught}", harness.uncaught.isEmpty())
                assertNoTempFiles(dir)
            } finally {
                harness.scope.cancel()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 5. Atomic writes: readers only ever see complete JSON, repeated
    //    sequential and concurrent writes leave no temp file behind.
    // ------------------------------------------------------------------
    @Test
    fun atomicWritesExposeOnlyCompleteJsonAndLeaveNoTempFiles() {
        val root = Files.createTempDirectory("diffmap-atomic").toFile()
        try {
            val dir = File(root, "diff/9")
            val cacheFile = File(dir, "diff_map.json")
            val snapshots = (0 until 6).map { round ->
                LinkedHashMap<String, Diff>().also { map ->
                    for (j in 0 until 1_500) {
                        val id = "r$round-article-$j-${"y".repeat(100)}"
                        map[id] = Diff(isUnread = j % 2 == 0, articleId = id, feedId = "feed-$j")
                    }
                }
            }

            val harness = newHolder(root, null)
            try {
                val violations = CopyOnWriteArrayList<String>()
                val done = AtomicBoolean(false)
                val reader = Thread {
                    while (!done.get()) {
                        if (cacheFile.exists()) {
                            try {
                                val parsed = gson.fromJson<Map<String, Diff>>(
                                    cacheFile.readText(), mapType
                                )
                                if (parsed == null) {
                                    violations.add("parsed null from a non-empty file")
                                }
                            } catch (torn: Exception) {
                                violations.add("torn read: ${torn.message?.take(100)}")
                            }
                        }
                        Thread.sleep(1)
                    }
                }.apply { isDaemon = true; start() }

                try {
                    runBlocking {
                        snapshots.forEachIndexed { index, snapshot ->
                            harness.holder.writeDiffsToCache(snapshot, dir).join()
                            val parsed = gson.fromJson<Map<String, Diff>>(
                                cacheFile.readText(), mapType
                            )
                            assertEquals("sequential write $index round-trips", snapshot, parsed)
                            assertNoTempFiles(dir)
                        }
                        val jobs = (0 until 24).map {
                            harness.holder.writeDiffsToCache(snapshots[it % snapshots.size], dir)
                        }
                        jobs.forEach { it.join() }
                    }
                } finally {
                    done.set(true)
                    reader.join(5_000)
                }

                assertTrue("concurrent readers saw torn content: $violations", violations.isEmpty())
                assertNoTempFiles(dir)
                val final = gson.fromJson<Map<String, Diff>>(cacheFile.readText(), mapType)
                assertNotNull("final file parses", final)
                assertTrue("final file is non-empty", final!!.isNotEmpty())
                assertTrue("no crash: ${harness.uncaught}", harness.uncaught.isEmpty())
            } finally {
                harness.scope.cancel()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 6. R8 safety: Diff keeps its wire field names via annotations.
    // ------------------------------------------------------------------
    @Test
    fun diffSerializationUsesSerializedNameFields() {
        // Keep has CLASS retention, so it is invisible to runtime reflection;
        // pin it in the compiled class file's constant pool instead.
        val classBytes = Diff::class.java.classLoader
            .getResourceAsStream("me/ash/reader/domain/data/Diff.class")
            ?.use { it.readBytes() }
        assertNotNull("Diff.class must be on the test classpath", classBytes)
        assertTrue(
            "Diff must carry the Keep annotation for R8",
            String(classBytes!!, Charsets.ISO_8859_1).contains("Landroidx/annotation/Keep;")
        )
        for (name in listOf("isUnread", "articleId", "feedId")) {
            val field = Diff::class.java.getDeclaredField(name)
            val annotation = field.getAnnotation(SerializedName::class.java)
            assertNotNull("field $name lacks a serialization name", annotation)
            assertEquals(name, annotation!!.value)
        }
        val json = Gson().toJson(Diff(isUnread = true, articleId = "aid", feedId = "fid"))
        val obj = JsonParser.parseString(json).asJsonObject
        assertTrue(obj.has("isUnread"))
        assertTrue(obj.has("articleId"))
        assertTrue(obj.has("feedId"))
        assertEquals(true, obj.get("isUnread").asBoolean)
        assertEquals("aid", obj.get("articleId").asString)
        assertEquals("fid", obj.get("feedId").asString)
        assertEquals(
            Diff(isUnread = true, articleId = "aid", feedId = "fid"),
            Gson().fromJson(json, Diff::class.java)
        )
    }
}
