package me.ash.reader.infrastructure.net

import java.net.InetAddress
import java.net.UnknownHostException
import me.ash.reader.domain.model.extract.ExtractPolicy
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E17 — SSRF guard reject/accept tables and the redirect-walker transport
 * seam. No HTTP-mock-server dependency (S36 — pinned by the gate's grep proof): hostname
 * resolution runs through a fake [Dns], redirects through a fake
 * [RedirectWalker.HopTransport].
 *
 * The address table is RECORDED PYTHON GROUND TRUTH (D14, raw JSON in
 * `build/recon/x2-parity-fixtures.json`, CPython `ipaddress` via the web
 * venv — exact command in `build/recon/x2_parity_fixtures.py`) with ONE
 * documented tightening: CPython reports multicast (224.0.0.0/4, ff00::/8)
 * as `is_global=True`, but E17's explicit enumeration rejects multicast —
 * the binding spec wins (fail-closed). Every other row matches Python's
 * `is_global` exactly.
 *
 * REDCHECK (recorded in the review handoff): remove `cidr("100.64.0.0", 10)`
 * from `NON_GLOBAL_V4` → the `http://100.64.0.1/` row of
 * `rejectTableOfNonGlobalLiteralAddresses` goes red.
 */
class SsrfGuardTest {

    /** Table-driven fake resolver; counts lookups; unknown hosts fail like real DNS. */
    private class FakeDns(
        private val table: Map<String, List<String>> = emptyMap(),
        private val emptyAnswer: Set<String> = emptySet(),
    ) : Dns {
        var lookupCount = 0
        override fun lookup(hostname: String): List<InetAddress> {
            lookupCount++
            if (hostname in emptyAnswer) return emptyList()
            return table[hostname]?.map { InetAddress.getByName(it) }
                ?: throw UnknownHostException(hostname)
        }
    }

    private fun guard(dns: Dns = FakeDns()) = SsrfGuard(dns)

    private fun assertRejected(reason: SsrfGuard.RejectReason, url: String, dns: Dns = FakeDns()) {
        assertEquals(
            "expected reject($reason) for $url",
            SsrfGuard.Verdict.Rejected(reason),
            SsrfGuard(dns).check(url),
        )
    }

    private fun assertAllowed(url: String, dns: Dns = FakeDns()) {
        assertEquals("expected allow for $url", SsrfGuard.Verdict.Allowed, SsrfGuard(dns).check(url))
    }

    // ---- (e) address table: recorded CPython is_global parity ----

    @Test
    fun rejectTableOfNonGlobalLiteralAddresses() {
        // Every row recorded is_global=False in D14 fixture JSON (plus the
        // E17 multicast tightening), as literal-IP URLs — the empty FakeDns
        // table means ANY accidental DNS resolution fails loudly instead of
        // silently passing (literal fast path pin).
        val rejectUrls = listOf(
            "http://127.0.0.1/",                    // loopback
            "http://127.9.9.9/x",                    // loopback /8 interior
            "http://10.0.0.1/",                      // 10/8
            "http://10.255.255.255/",                // 10/8 edge
            "http://172.16.0.1/",                    // 172.16/12 low edge
            "http://172.31.255.255/",                // 172.16/12 high edge
            "http://192.168.1.1/",                   // 192.168/16
            "http://169.254.169.254/metadata",       // link-local (cloud metadata)
            "http://0.0.0.0/",                       // unspecified
            "http://224.0.0.1/",                     // multicast (E17)
            "http://239.255.255.250/",               // multicast (E17)
            "http://100.64.0.1/",                    // CGNAT 100.64/10
            "http://100.127.255.255/",               // CGNAT edge
            "http://192.0.0.1/",                     // 192.0.0/24
            "http://192.0.0.170/",                   // 192.0.0.170/31 ⊂ /24
            "http://192.0.0.200/",                   // 192.0.0/24 interior
            "http://192.0.2.1/",                     // TEST-NET-1
            "http://198.18.0.1/",                    // benchmarking 198.18/15
            "http://198.19.255.255/",                // benchmarking edge
            "http://198.51.100.1/",                  // TEST-NET-2
            "http://203.0.113.1/",                   // TEST-NET-3
            "http://240.0.0.1/",                     // reserved 240/4
            "http://255.255.255.255/",               // broadcast ⊂ 240/4
            "http://[::1]/",                         // v6 loopback
            "http://[::]/",                          // v6 unspecified
            "http://[fe80::1]/",                     // v6 link-local
            "http://[fc00::1]/",                     // ULA fc00::/7
            "http://[fd12:3456:789a::1]/",           // ULA interior
            "http://[ff00::1]/",                     // v6 multicast (E17)
            "http://[::ffff:10.0.0.1]/",             // IPv4-mapped non-global
            "http://[2001::1]/",                     // Teredo 2001::/23
            "http://[2001:db8::1]/",                 // documentation
            "http://[2002:0101:0101::1]/",           // 6to4
            "http://[3fff::1]/",                     // documentation 3fff::/20
            "http://[100::1]/",                      // discard-only 100::/64
            "http://[64:ff9b:1::1]/",                // NAT64 64:ff9b:1::/48
        )
        for (url in rejectUrls) {
            assertRejected(SsrfGuard.RejectReason.NON_GLOBAL_ADDRESS, url)
        }
    }

    @Test
    fun acceptPublicLiteralAddresses() {
        val acceptUrls = listOf(
            "http://8.8.8.8/",
            "https://1.1.1.1/x",
            "http://93.184.216.34/article",
            "http://172.15.255.255/",   // just BELOW 172.16/12
            "http://172.32.0.0/",       // just ABOVE 172.16/12
            "http://100.63.255.255/",   // just BELOW 100.64/10
            "http://[2606:4700:4700::1111]/",
            "http://[2001:4860:4860::8888]/",
            "http://[::ffff:8.8.8.8]/", // IPv4-mapped GLOBAL → allowed (Python parity)
        )
        for (url in acceptUrls) {
            assertAllowed(url)
        }
    }

    @Test
    fun isGlobalMatchesRecordedPythonTable() {
        // Direct address-level pin of the recorded D14 table (is_global),
        // with the E17 multicast tightening marked inline.
        val g = guard()
        val recordedNonGlobal = listOf(
            "127.0.0.1", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "169.254.169.254", "0.0.0.0", "100.64.0.1", "100.127.255.255",
            "192.0.0.1", "192.0.0.170", "192.0.0.200", "192.0.2.1", "198.18.0.1",
            "198.19.255.255", "198.51.100.1", "203.0.113.1", "240.0.0.1", "255.255.255.255",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456:789a::1", "::ffff:10.0.0.1",
            "2001::1", "2001:db8::1", "2002:0101:0101::1", "3fff::1", "100::1", "64:ff9b:1::1",
        )
        val recordedGlobal = listOf(
            "8.8.8.8", "1.1.1.1", "93.184.216.34", "172.15.255.255", "172.32.0.0",
            "100.63.255.255", "2606:4700:4700::1111", "2001:4860:4860::8888", "::ffff:8.8.8.8",
        )
        // E17 tightening — CPython recorded is_global=TRUE for these, the
        // spec's explicit enumeration rejects multicast anyway.
        val specOnlyRejects = listOf("224.0.0.1", "239.255.255.250", "ff00::1")

        for (ip in recordedNonGlobal + specOnlyRejects) {
            assertTrue("expected NON-global: $ip", !g.isGlobalAddress(InetAddress.getByName(ip)))
        }
        for (ip in recordedGlobal) {
            assertTrue("expected global: $ip", g.isGlobalAddress(InetAddress.getByName(ip)))
        }
    }

    // ---- (a)–(d) URL structure: scheme, host, userinfo, port ----

    @Test
    fun rejectNonHttpSchemes() {
        assertRejected(SsrfGuard.RejectReason.SCHEME, "ftp://8.8.8.8/file")
        assertRejected(SsrfGuard.RejectReason.SCHEME, "file:///etc/passwd")
        assertRejected(SsrfGuard.RejectReason.SCHEME, "gopher://8.8.8.8/")
        assertRejected(SsrfGuard.RejectReason.SCHEME, "")
        assertRejected(SsrfGuard.RejectReason.SCHEME, "example.com/article")
        // Scheme check is case-insensitive on the ACCEPT side too.
        assertAllowed("HTTP://8.8.8.8/Upper")
    }

    @Test
    fun rejectUserinfo() {
        // Web `safe_web_url:223–224` — username OR password → reject.
        assertRejected(SsrfGuard.RejectReason.USERINFO, "http://user:pass@8.8.8.8/")
        assertRejected(SsrfGuard.RejectReason.USERINFO, "http://user@8.8.8.8/")
        assertRejected(SsrfGuard.RejectReason.USERINFO, "https://:secret@8.8.8.8/")
    }

    @Test
    fun rejectInvalidPortAndMalformedUrls() {
        assertRejected(SsrfGuard.RejectReason.MALFORMED_URL, "http://8.8.8.8:99999/")
        assertRejected(SsrfGuard.RejectReason.MALFORMED_URL, "http://8.8.8.8:notaport/")
        assertRejected(SsrfGuard.RejectReason.MALFORMED_URL, "http://")
        assertRejected(SsrfGuard.RejectReason.MALFORMED_URL, "http://exa mple.com/")
        // A valid high port is fine.
        assertAllowed("https://8.8.8.8:8443/x")
    }

    // ---- (d/e) hostname resolution through the injectable Dns seam ----

    @Test
    fun hostnameResolvingToPublicAddressAllowed() {
        val dns = FakeDns(table = mapOf("public.test" to listOf("93.184.216.34")))
        assertAllowed("https://public.test/article?id=7", dns)
        assertEquals(1, dns.lookupCount)
    }

    @Test
    fun multiAddressHostWithOnePrivateAddressRejected() {
        // Web: ALL resolved addresses must be global — one bad answer taints
        // the whole host, in either order.
        val dns1 = FakeDns(table = mapOf("mixed.test" to listOf("93.184.216.34", "10.0.0.1")))
        assertRejected(SsrfGuard.RejectReason.NON_GLOBAL_ADDRESS, "http://mixed.test/", dns1)
        val dns2 = FakeDns(table = mapOf("mixed.test" to listOf("192.168.0.1", "8.8.8.8")))
        assertRejected(SsrfGuard.RejectReason.NON_GLOBAL_ADDRESS, "http://mixed.test/", dns2)
        // All-public multi-answer hosts pass.
        val dns3 = FakeDns(table = mapOf("multi.test" to listOf("8.8.8.8", "1.1.1.1")))
        assertAllowed("http://multi.test/", dns3)
    }

    @Test
    fun dnsFailureRejected() {
        // UnknownHostException parity (web gaierror → reject).
        assertRejected(SsrfGuard.RejectReason.DNS_FAILURE, "http://no-such-host.invalid/")
        // Empty answer → reject (web `if not addresses … raise`).
        val dns = FakeDns(emptyAnswer = setOf("empty.test"))
        assertRejected(SsrfGuard.RejectReason.DNS_FAILURE, "http://empty.test/", dns)
        assertEquals(1, dns.lookupCount)
    }

    // ---- E17 redirect walker (transport seam) ----

    private fun chainTransport(locations: Map<String, String?>): RedirectWalker.HopTransport =
        RedirectWalker.HopTransport { locations[it] }

    @Test
    fun walkSucceedsWithoutRedirects() {
        var guardCalls = 0
        var transportCalls = 0
        val walker = RedirectWalker(
            guard = { guardCalls++; guard().check(it) },
            transport = { transportCalls++; null },
        )
        val outcome = walker.walk("http://8.8.8.8/start")
        assertEquals(RedirectWalker.WalkOutcome.Succeeded("http://8.8.8.8/start"), outcome)
        assertEquals("start URL is hop-checked exactly once", 1, guardCalls)
        assertEquals(1, transportCalls)
    }

    @Test
    fun walkRejectsHopIntoPrivateRange() {
        var guardCalls = 0
        var transportCalls = 0
        val walker = RedirectWalker(
            guard = { guardCalls++; guard().check(it) },
            transport = {
                transportCalls++
                if (it == "http://8.8.8.8/start") "http://192.168.1.1/x" else null
            },
        )
        val outcome = walker.walk("http://8.8.8.8/start")
        assertEquals(
            RedirectWalker.WalkOutcome.Rejected(
                "http://192.168.1.1/x",
                SsrfGuard.RejectReason.NON_GLOBAL_ADDRESS,
            ),
            outcome,
        )
        assertEquals("guard runs on EVERY hop: start + redirect target", 2, guardCalls)
        assertEquals("the rejected hop is never fetched", 1, transportCalls)
    }

    @Test
    fun walkNeverCallsTransportWhenStartRejected() {
        var transportCalls = 0
        val walker = RedirectWalker(
            guard = { guard().check(it) },
            transport = { transportCalls++; null },
        )
        val outcome = walker.walk("http://10.0.0.1/")
        assertTrue(outcome is RedirectWalker.WalkOutcome.Rejected)
        assertEquals(0, transportCalls)
    }

    @Test
    fun sixthHopFailsWithPerHopGuardCounts() {
        assertEquals(5, ExtractPolicy.MAX_HOPS)
        var guardCalls = 0
        var transportCalls = 0
        // Infinite relative redirect chain /0 → /1 → /2 → …
        val walker = RedirectWalker(
            guard = { guardCalls++; guard().check(it) },
            transport = {
                transportCalls++
                val n = it.substringAfterLast('/').toInt()
                "/${n + 1}"
            },
        )
        val outcome = walker.walk("http://8.8.8.8/0")
        assertTrue("6th hop must fail: $outcome", outcome is RedirectWalker.WalkOutcome.TooManyHops)
        // URLs checked: /0.. /5 (start + 5 followed redirects); a 6th
        // redirect is refused BEFORE its target is guard-checked or fetched.
        assertEquals("guard invoked once per checked hop", 6, guardCalls)
        assertEquals(6, transportCalls)
    }

    @Test
    fun fiveRedirectsThenFinalResponseSucceeds() {
        var guardCalls = 0
        val walker = RedirectWalker(
            guard = { guardCalls++; guard().check(it) },
            transport = chainTransport(
                mapOf(
                    "http://8.8.8.8/0" to "/1",
                    "http://8.8.8.8/1" to "/2",
                    "http://8.8.8.8/2" to "/3",
                    "http://8.8.8.8/3" to "/4",
                    "http://8.8.8.8/4" to "/5",
                    "http://8.8.8.8/5" to null, // final response at exactly MAX_HOPS
                )
            ),
        )
        val outcome = walker.walk("http://8.8.8.8/0")
        assertEquals(RedirectWalker.WalkOutcome.Succeeded("http://8.8.8.8/5"), outcome)
        assertEquals(6, guardCalls)
    }

    @Test
    fun unresolvableLocationRejected() {
        val walker = RedirectWalker(
            guard = { guard().check(it) },
            transport = chainTransport(mapOf("http://8.8.8.8/start" to "http://[::1")),
        )
        val outcome = walker.walk("http://8.8.8.8/start")
        assertEquals(
            RedirectWalker.WalkOutcome.Rejected(
                "http://[::1",
                SsrfGuard.RejectReason.MALFORMED_URL,
            ),
            outcome,
        )
    }

    @Test
    fun absoluteLocationAcrossHostsIsGuarded() {
        var guardCalls = 0
        val dns = FakeDns(table = mapOf("good.test" to listOf("93.184.216.34")))
        val walker = RedirectWalker(
            guard = { guardCalls++; SsrfGuard(dns).check(it) },
            transport = chainTransport(
                mapOf("https://good.test/a" to "https://good.test/b", "https://good.test/b" to null)
            ),
        )
        assertEquals(
            RedirectWalker.WalkOutcome.Succeeded("https://good.test/b"),
            walker.walk("https://good.test/a"),
        )
        assertEquals(2, guardCalls)
        assertEquals("one DNS lookup per hostname hop", 2, dns.lookupCount)
    }
}
