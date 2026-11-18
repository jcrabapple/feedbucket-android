package me.ash.reader.infrastructure.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import me.ash.reader.domain.model.extract.ExtractPolicy
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * E17 — fetch safety: public-IP + redirect guard, web `require_public_url`
 * parity (`feedbucket/security.py:217–267`). Pure and JVM-unit-testable via
 * the injectable [Dns] seam (no HTTP-mock-server dependency — S36).
 *
 * [check] rejects, in web order (`safe_web_url` → `require_public_url`):
 *  (a) scheme ∉ {http, https};
 *  (b) malformed URL / missing host / invalid port (okhttp's own [HttpUrl]
 *      parser is used deliberately: the guard and the fetching client must
 *      never disagree about what a URL means — parser-differential safety);
 *  (c) userinfo (`username`/`password` rejected — web `safe_web_url:223–224`);
 *  (d) DNS resolution failure or empty answer (web gaierror → reject);
 *  (e) ANY resolved address non-global (web: ALL addresses must satisfy
 *      `ipaddress.is_global`; a multi-address host with ONE private answer
 *      is rejected).
 *
 * Literal IP hosts bypass DNS and are evaluated directly (web
 * `ipaddress.ip_address(host)` fast path).
 *
 * The non-global set mirrors CPython `ipaddress.ip_address(...).is_global`
 * (3.13/3.14 semantics, recorded ground truth in
 * `build/recon/x2-parity-fixtures.json`): IPv4 `_private_networks` ∪ the
 * 100.64.0.0/10 is_global exception; IPv6 `_private_networks` with
 * IPv4-mapped addresses delegated to their mapped v4 semantics (`address
 * .is_global == address.ipv4_mapped.is_global`). ONE documented tightening:
 * CPython reports multicast (224.0.0.0/4, ff00::/8) as is_global=True, but
 * E17's explicit enumeration rejects multicast — the binding spec wins
 * (stricter, fail-closed).
 *
 * Residual risk recorded honestly (E17): DNS-rebinding TOCTOU between guard
 * resolution and OkHttp's own resolution exists in the web design too;
 * accepted at parity, mitigated per-hop via [RedirectWalker]. The guard
 * applies to extraction fetches ONLY; feed fetching keeps its behavior.
 */
class SsrfGuard(private val dns: Dns = Dns.SYSTEM) {

    enum class RejectReason {
        /** Not an http(s) URL (web `safe_web_url` scheme check). */
        SCHEME,

        /** Unparseable URL, missing host, or invalid port (web ValueError parity). */
        MALFORMED_URL,

        /** Credentials in the URL (web `safe_web_url:223–224`). */
        USERINFO,

        /** Host could not be resolved, or resolved to zero addresses. */
        DNS_FAILURE,

        /** At least one resolved address is not global/public. */
        NON_GLOBAL_ADDRESS,
    }

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Rejected(val reason: RejectReason) : Verdict
    }

    /** Full guard on one URL. Every redirect hop must pass this (E17). */
    fun check(rawUrl: String): Verdict {
        val trimmed = rawUrl.trim()
        // (a) scheme — explicit prefix check so ftp:// etc. get the precise
        // reason; HttpUrl would reject it too, but indistinguishably.
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Verdict.Rejected(RejectReason.SCHEME)
        }
        // (b) structure — okhttp's parser: exactly what the client will honor.
        val url = trimmed.toHttpUrlOrNull()
            ?: return Verdict.Rejected(RejectReason.MALFORMED_URL)
        // (c) userinfo.
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Verdict.Rejected(RejectReason.USERINFO)
        }
        // (d/e) resolve + global check.
        // HttpUrl.host is already canonical (punycode/lowercased); IPv6 may
        // arrive bracketed, so strip defensively before literal parsing.
        val host = url.host.removeSurrounding("[", "]")
        val addresses = resolveHost(host)
            ?: return Verdict.Rejected(RejectReason.DNS_FAILURE)
        if (addresses.isEmpty()) {
            return Verdict.Rejected(RejectReason.DNS_FAILURE)
        }
        if (addresses.any { !isGlobalAddress(it) }) {
            return Verdict.Rejected(RejectReason.NON_GLOBAL_ADDRESS)
        }
        return Verdict.Allowed
    }

    /**
     * Literal IPs are evaluated directly (no DNS — web fast path);
     * hostnames go through the injected [Dns]. Any failure (including an
     * empty answer) → null → reject.
     */
    private fun resolveHost(host: String): List<InetAddress>? {
        literalAddress(host)?.let { return listOf(it) }
        return try {
            dns.lookup(host)
        } catch (_: UnknownHostException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Strict literal parse, DNS-free: dotted-quad IPv4 (4 × 0–255 only —
     * NOT Java's lenient `10.1` short forms, which okhttp would treat as a
     * hostname anyway) or a colon-bearing IPv6 literal.
     */
    private fun literalAddress(host: String): InetAddress? {
        // Strict octet validation FIRST: `InetAddress.getByName` on a
        // non-literal performs a real DNS lookup, which must never happen
        // inside the guard's fast path.
        val isV4Literal = isDottedQuad(host)
        val isV6Literal = host.contains(':')
        if (!isV4Literal && !isV6Literal) return null
        return try {
            // InetAddress.getByName performs NO lookup for literal IPs.
            InetAddress.getByName(host)
        } catch (_: UnknownHostException) {
            null
        }
    }

    /**
     * CPython `is_global` parity (+ the E17 multicast tightening). Public
     * for the unit-test table pin; the coordinator never calls it directly.
     */
    fun isGlobalAddress(address: InetAddress): Boolean =
        when (address) {
            is Inet4Address -> isGlobalV4(address)
            is Inet6Address -> isGlobalV6(address)
            else -> false
        }

    private fun isGlobalV4(address: InetAddress): Boolean =
        NON_GLOBAL_V4.none { (prefix, bits) -> inCidr(address, prefix, bits) }

    private fun isGlobalV6(address: Inet6Address): Boolean {
        // Python: IPv4-mapped delegates — `is_global == ipv4_mapped.is_global`.
        mappedV4(address)?.let { return isGlobalV4(it) }
        return NON_GLOBAL_V6.none { (prefix, bits) -> inCidr(address, prefix, bits) }
    }

    private fun mappedV4(address: Inet6Address): Inet4Address? {
        val bytes = address.address
        val mapped = bytes.copyOfRange(0, 12).contentEquals(IPV4_MAPPED_PREFIX)
        if (!mapped) return null
        return InetAddress.getByAddress(bytes.copyOfRange(12, 16)) as? Inet4Address
    }

    private fun inCidr(address: InetAddress, prefix: InetAddress, prefixBits: Int): Boolean {
        val a = address.address
        val p = prefix.address
        if (a.size != p.size) return false
        var remaining = prefixBits
        var i = 0
        while (remaining >= 8) {
            if (a[i] != p[i]) return false
            remaining -= 8
            i++
        }
        if (remaining > 0) {
            val mask = (0xFF shl (8 - remaining)) and 0xFF
            if ((a[i].toInt() and mask) != (p[i].toInt() and mask)) return false
        }
        return true
    }

    private companion object {
        val IPV4_DOTTED_QUAD = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        val IPV4_MAPPED_PREFIX = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte())

        /** Literal-prefix parse never triggers DNS. */
        fun cidr(literal: String, bits: Int): Pair<InetAddress, Int> =
            InetAddress.getByName(literal) to bits

        /**
         * CPython `_IPv4Constants._private_networks` ∪ {100.64.0.0/10} (the
         * is_global=False exception) ∪ {224.0.0.0/4} (E17 multicast reject —
         * stricter than CPython, recorded). 192.0.0.170/31 ⊂ 192.0.0.0/24;
         * 255.255.255.255/32 ⊂ 240.0.0.0/4.
         */
        val NON_GLOBAL_V4 = listOf(
            cidr("0.0.0.0", 8), // unspecified
            cidr("10.0.0.0", 8),
            cidr("100.64.0.0", 10), // carrier-grade NAT
            cidr("127.0.0.0", 8), // loopback
            cidr("169.254.0.0", 16), // link-local
            cidr("172.16.0.0", 12),
            cidr("192.0.0.0", 24),
            cidr("192.0.2.0", 24), // TEST-NET-1
            cidr("192.168.0.0", 16),
            cidr("198.18.0.0", 15), // benchmarking
            cidr("198.51.100.0", 24), // TEST-NET-2
            cidr("203.0.113.0", 24), // TEST-NET-3
            cidr("224.0.0.0", 4), // multicast (E17)
            cidr("240.0.0.0", 4), // reserved incl. 255.255.255.255
        )

        /**
         * CPython `_IPv6Constants._private_networks` (mapped /96 handled by
         * delegation, not listed) ∪ {ff00::/8} (E17 multicast reject).
         */
        val NON_GLOBAL_V6 = listOf(
            cidr("::", 128), // unspecified
            cidr("::1", 128), // loopback
            cidr("64:ff9b:1::", 48),
            cidr("100::", 64), // discard-only
            cidr("2001::", 23), // Teredo & IETF allocations
            cidr("2001:db8::", 32), // documentation
            cidr("2002::", 16), // 6to4
            cidr("3fff::", 20), // documentation
            cidr("fc00::", 7), // unique-local
            cidr("fe80::", 10), // link-local
            cidr("ff00::", 8), // multicast (E17)
        )

        /** IPv4 dotted-quad sanity: each octet 0–255. */
        fun isDottedQuad(host: String): Boolean {
            val m = IPV4_DOTTED_QUAD.matchEntire(host) ?: return false
            return (1..4).all { m.groupValues[it].toInt() <= 255 }
        }
    }
}

/**
 * E17 redirect walker — the transport seam for the X3 coordinator's manual
 * redirect loop (`followRedirects(false)`, E18). Runs the FULL [SsrfGuard]
 * check on EVERY hop URL including the start; a hop into a private range is
 * rejected, and a [ExtractPolicy.MAX_HOPS]+1-th redirect fails with
 * [WalkOutcome.TooManyHops]. No fetching happens here: [HopTransport]
 * supplies only "what Location did this URL answer with" so the walk is
 * pure and unit-testable (per-hop guard invocation counts included).
 */
class RedirectWalker(
    private val guard: (String) -> SsrfGuard.Verdict,
    private val transport: HopTransport,
    private val maxHops: Int = ExtractPolicy.MAX_HOPS,
) {

    /** Returns the redirect `Location` for [currentUrl], or null when the response is final. */
    fun interface HopTransport {
        fun nextLocation(currentUrl: String): String?
    }

    sealed interface WalkOutcome {
        /** A final (non-redirect) response was reached at a guard-approved URL. */
        data class Succeeded(val finalUrl: String) : WalkOutcome

        /** A hop URL failed the guard (or a Location was unresolvable). */
        data class Rejected(val url: String, val reason: SsrfGuard.RejectReason) : WalkOutcome

        /** The redirect chain exceeded [ExtractPolicy.MAX_HOPS]. */
        data class TooManyHops(val lastUrl: String) : WalkOutcome
    }

    fun walk(startUrl: String): WalkOutcome {
        var current = startUrl
        // hop = number of redirects already FOLLOWED; URLs checked = hop + 1.
        for (hop in 0..maxHops) {
            when (val verdict = guard(current)) {
                is SsrfGuard.Verdict.Rejected ->
                    return WalkOutcome.Rejected(current, verdict.reason)
                SsrfGuard.Verdict.Allowed -> Unit
            }
            val location = transport.nextLocation(current)
                ?: return WalkOutcome.Succeeded(current)
            current = resolve(current, location)
                ?: return WalkOutcome.Rejected(location, SsrfGuard.RejectReason.MALFORMED_URL)
        }
        return WalkOutcome.TooManyHops(current)
    }

    /** Absolute or relative Location resolution; null when unresolvable. */
    private fun resolve(base: String, location: String): String? =
        try {
            val resolved = URI(base).resolve(location.trim())
            resolved?.toString()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
}
