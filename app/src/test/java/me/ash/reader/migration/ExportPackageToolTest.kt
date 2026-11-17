package me.ash.reader.migration

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance #8a (JVM wrapper) — runs the S32a export tool's authoritative
 * python test suite (`app/src/test/python/test_export_feedbucket_package.py`,
 * stdlib unittest) as a subprocess so `bash scripts/gate.sh G` covers the
 * export tool through the standard unit-test task. The python suite builds
 * synthetic web-schema DBs, runs `scripts/export_feedbucket_package.py`, and
 * asserts integrity/allowlist/counts/secret-scan/source-unmodified gates.
 *
 * Requires `python3` on the test machine PATH (present on the dev host and
 * on the ubuntu-latest CI runner); a missing interpreter fails the test
 * loudly rather than skipping.
 */
class ExportPackageToolTest {

    /** Walk ancestors from the JVM working dir to the repo root. */
    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (true) {
            if (File(dir, "scripts/export_feedbucket_package.py").isFile) return dir
            dir = dir.parentFile ?: error("repo root not found above ${File("").absolutePath}")
        }
    }

    @Test
    fun exportToolPythonSuitePasses() {
        val root = repoRoot()
        val python = System.getenv("PYTHON") ?: "python3"
        val testFile = File(root, "app/src/test/python/test_export_feedbucket_package.py")
        assertTrue("python suite not found: $testFile", testFile.isFile)

        val process = ProcessBuilder(python, testFile.absolutePath)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(5, TimeUnit.MINUTES)
        assertTrue("python suite timed out", finished)
        assertEquals(
            "export tool python suite failed (exit ${process.exitValue()}):\n$output",
            0,
            process.exitValue(),
        )
        assertTrue("python suite did not report OK:\n$output", output.contains("OK"))
    }
}
