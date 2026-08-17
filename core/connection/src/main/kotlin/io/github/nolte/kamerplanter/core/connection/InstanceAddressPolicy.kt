package io.github.nolte.kamerplanter.core.connection

import java.net.URI

/**
 * Which instance addresses this app is willing to talk to.
 *
 * TLS is required, with one exception: an instance on the network the phone is already on may
 * be reached over plain `http`. That exception is not a convenience — a self-hosted
 * kamerplanter on a home server is the normal deployment, its address comes out of
 * `window.location.origin` in the instance's own web UI, and demanding a certificate for
 * `192.168.x.x` would mean the app cannot be connected to the machine it was written for.
 *
 * The exception stops at the edge of that network. A plain-`http` address on a routable host
 * is refused, because the pairing payload carries a one-time credential and every later
 * request carries the token it buys: on the path to a public host those are readable by
 * everyone in between, and no part of this app can tell whether that path is safe. Inside a
 * home network the user's own trust boundary is the answer; outside it there is none.
 *
 * Enforced here rather than only in the platform's network-security configuration because that
 * configuration cannot express "any private address" — it matches named domains, and the
 * address of a home instance is not known when the app is built. This predicate is what keeps
 * a cleartext request from ever being constructed; the platform config only stops it from
 * being blocked outright.
 */
object InstanceAddressPolicy {

    private const val SCHEME_HTTPS = "https"
    private const val SCHEME_HTTP = "http"

    /**
     * Whether an address is one only reachable from inside the user's own network.
     *
     * Public because a screen has to know: the hint about local-network access is help for a
     * home instance and noise for a public one, and shown for every failure it is noise the
     * user learns to skip.
     */
    fun isPrivate(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        return uri.hostOrAuthority()?.isPrivateAddress() ?: false
    }

    /** Whether a whole instance URL may be used. */
    fun permits(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        return permits(uri.scheme, uri.hostOrAuthority())
    }

    /** Whether a scheme/host pair may be used, for callers that already parsed the URL. */
    fun permits(scheme: String?, host: String?): Boolean {
        val cleanHost = host?.takeIf { it.isNotBlank() } ?: return false
        return when (scheme?.lowercase()) {
            SCHEME_HTTPS -> true
            SCHEME_HTTP -> cleanHost.isPrivateAddress()
            else -> false
        }
    }
}

/**
 * The host, taken from the authority when [URI] declines to name one.
 *
 * `URI.getHost()` returns null for a host name containing an underscore — the syntax it
 * enforces is stricter than what DNS resolvers, OkHttp and home NAS boxes actually accept.
 * A pairing payload naming `http://mein_nas.local:8000` was therefore refused and reported as
 * a foreign QR code, for an address every other part of the stack would have handled.
 *
 * The authority is the same text minus any userinfo and port, so reading it back is not a
 * looser rule — the decision below still runs on the host alone.
 */
private fun URI.hostOrAuthority(): String? =
    (host ?: authority?.substringAfterLast('@')?.substringBeforeLast(':'))
        ?.takeIf { it.isNotBlank() }

/** Suffixes reserved for names that only resolve inside a local network. */
private val PRIVATE_SUFFIXES = listOf(".local", ".home.arpa", ".internal")

private const val IPV4_PARTS = 4
private const val IPV4_MAX = 255

/**
 * Whether a host can only be reached from inside the user's own network.
 *
 * Deliberately a decision about the literal address, not a DNS lookup: resolving a name would
 * make the answer depend on which network the phone is on at that moment, so the same code
 * could be accepted at home and refused on mobile data — or, worse, a name under an attacker's
 * control could be pointed at a private address to pass the check and then moved.
 */
private fun String.isPrivateAddress(): Boolean {
    val host = trim().trimStart('[').trimEnd(']').lowercase()
    // An IPv6 literal always contains a colon, and no hostname may. Checked first and used as
    // the gate, because the IPv6 test is prefix matching: applied to any string it hands the
    // cleartext exception to `fdroid.example.com`, `fc-nas.example.org` and anything else
    // beginning with those two letters — routable hosts, reached in the clear, carrying a
    // pairing credential. The rule is meant to be about literal addresses; this is what makes
    // it one.
    if (host.contains(':')) return host.isIpv6Private()
    return host == "localhost" ||
        PRIVATE_SUFFIXES.any { host.endsWith(it) } ||
        host.ipv4Octets()?.isPrivateIpv4() == true
}

/** Whether an IPv6 literal — never a hostname; see the gate in [isPrivateAddress]. */
private fun String.isIpv6Private(): Boolean =
    this == "::1" ||
        // fe80::/10 link-local, fc00::/7 unique-local — the IPv6 equivalents of the ranges below.
        startsWith("fe8") || startsWith("fe9") || startsWith("fea") || startsWith("feb") ||
        startsWith("fc") || startsWith("fd")

private fun String.ipv4Octets(): List<Int>? {
    val parts = split('.')
    if (parts.size != IPV4_PARTS) return null
    val octets = parts.mapNotNull { it.asOctet() }
    return octets.takeIf { it.size == IPV4_PARTS }
}

/**
 * One dotted part of an IPv4 address, or `null`.
 *
 * The round-trip comparison rejects "01" and "+1": those are octal to some parsers and decimal
 * to others, and an address two readers disagree about is not one that may earn the cleartext
 * exception.
 */
private fun String.asOctet(): Int? =
    toIntOrNull()?.takeIf { it in 0..IPV4_MAX && it.toString() == this }

@Suppress("MagicNumber")
private fun List<Int>.isPrivateIpv4(): Boolean = when (this[0]) {
    10 -> true // 10.0.0.0/8
    127 -> true // loopback
    169 -> this[1] == 254 // 169.254.0.0/16 link-local
    172 -> this[1] in 16..31 // 172.16.0.0/12
    192 -> this[1] == 168 // 192.168.0.0/16
    // 100.64.0.0/10, the shared address space a private overlay such as Tailscale hands out.
    100 -> this[1] in 64..127
    else -> false
}
