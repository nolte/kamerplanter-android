package io.github.nolte.kamerplanter.core.connection

import java.net.URI

/** A host and, where the address names one, its port. */
internal data class UriAuthority(val host: String, val port: Int)

/**
 * Reading a host and port out of a [URI] that `java.net.URI` will not name.
 *
 * `getHost()` enforces RFC 2396's server-authority grammar, which is stricter than what DNS
 * resolvers, OkHttp and home NAS boxes actually accept: a single underscore makes it return
 * `null`, and `getPort()` `-1` along with it. `http://mein_nas.local:8000` is a perfectly
 * ordinary address that this app would otherwise refuse and report as somebody else's QR code.
 *
 * Shared rather than private to one file, because it was private to one file and that is
 * exactly how half the fix shipped: [InstanceAddressPolicy] learned to read such a host while
 * [DiscoveryLinkParser] did not, so the same instance's pairing code was accepted and its
 * `/connect` link refused — with the web UI showing both on one dialogue and the scanner
 * taking whichever it decoded first.
 *
 * The fallback allows exactly what it exists for and refuses the rest. Its first version
 * salvaged whatever `java.net.URI` had declined to parse, which is a different and much larger
 * set than "a hostname with an underscore in it":
 *
 *  - It could return a host still containing `:`. [InstanceAddressPolicy] treats a colon as
 *    proof of an IPv6 literal and then matches prefixes, so `fd_nas.example.com:80:1` was read
 *    as a unique-local address — a routable host, judged private, permitted in the clear, and
 *    handed a pairing credential.
 *  - It read the percent-DECODED authority, so `%40` became a `@` and everything before it was
 *    dropped as userinfo: `https://192.168.0.1%40evil.example` was accepted and silently
 *    rewritten to `https://evil.example`, a host the scanned text never named.
 *  - A port it could not parse became "no port", so `:8O00` (letter O) connected to port 80
 *    instead of being refused, and `https://x:99999999999` compared equal to `https://x`.
 *
 * All three were regressions against the plain `uri.host` they replaced: that returned `null`
 * and the address was refused. So the rule is a whitelist, not a repair. A name is accepted
 * only if it reads like a DNS label plus the underscore this exists for; anything else — a
 * leftover colon, percent-encoding, an unbracketed IPv6 literal, an unusable port — is `null`,
 * and the caller refuses the address as it did before.
 */
internal fun URI.parsedAuthority(): UriAuthority? =
    // When java.net.URI named the host itself, it applied the stricter grammar and there is
    // nothing to salvage — take its answer, including a port it has already validated.
    host?.takeIf { it.isNotBlank() }?.let { UriAuthority(it, port) }
        // Deliberately the RAW authority. The decoded one invents delimiters the scanned text
        // does not contain, and the userinfo split is exactly where that mattered.
        ?: rawAuthority?.takeIf { it.isNotBlank() }?.substringAfterLast('@')?.asAuthority()

/**
 * An authority `java.net.URI` would not name, taken apart by hand.
 *
 * Both halves have to hold: an unusable port makes the whole address unusable rather than
 * port-less, because dropping it silently would send the request to the scheme's default port
 * — a different machine than the one the address names.
 */
private fun String.asAuthority(): UriAuthority? {
    val separator = lastIndexOf(':')
    val name = if (separator < 0) this else substring(0, separator)
    val port = if (separator < 0) NO_PORT else substring(separator + 1).asPort()
    return if (HOST_NAME.matches(name) && port != null) UriAuthority(name, port) else null
}

/** The host, or `null` where the address does not usably name one. */
internal fun URI.hostOrAuthority(): String? = parsedAuthority()?.host

/**
 * The port, or `-1` where the address names none.
 *
 * Needed for the same reason as [hostOrAuthority]: `getPort()` is `-1` for an authority
 * `java.net.URI` declined to parse, so an instance on `:8000` would have its port dropped
 * while its address was being normalised — and two different instances would then compare
 * equal.
 */
internal fun URI.portOrAuthority(): Int = parsedAuthority()?.port ?: NO_PORT

private const val NO_PORT = -1
private const val MAX_PORT = 65535

/**
 * A hostname as the fallback is willing to read one: DNS labels plus the underscore that sent
 * `java.net.URI` here in the first place.
 *
 * A whitelist because the refusals are the point — `:` (the IPv6 heuristic's gate), `%` (a
 * delimiter the decoder would invent), `@` and `/` all fail here rather than downstream.
 */
private val HOST_NAME = Regex("[A-Za-z0-9._-]+")

/** A port number, or `null` for anything a connection could not be opened to. */
private fun String.asPort(): Int? = toIntOrNull()?.takeIf { it in 1..MAX_PORT && it.toString() == this }
