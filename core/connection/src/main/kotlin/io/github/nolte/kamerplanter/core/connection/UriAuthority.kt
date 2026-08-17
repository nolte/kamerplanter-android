package io.github.nolte.kamerplanter.core.connection

import java.net.URI

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
 * Not a looser rule, only a completer parse: the authority is the same text minus userinfo and
 * port, and every decision still runs on the host alone.
 */
internal fun URI.hostOrAuthority(): String? =
    (host ?: rawAuthority()?.substringBeforeLast(':', missingDelimiterValue = rawAuthority().orEmpty()))
        ?.takeIf { it.isNotBlank() }

/**
 * The port, or `-1` where the address names none.
 *
 * Needed for the same reason as [hostOrAuthority]: `getPort()` is `-1` for an authority
 * `java.net.URI` declined to parse, so an instance on `:8000` would have its port dropped
 * while its address was being normalised — and two different instances would then compare
 * equal.
 */
internal fun URI.portOrAuthority(): Int {
    if (port != -1) return port
    val authority = rawAuthority() ?: return -1
    val afterColon = authority.substringAfterLast(':', missingDelimiterValue = "")
    return afterColon.toIntOrNull() ?: -1
}

/** The authority without any `user:password@` prefix; userinfo is not part of the address. */
private fun URI.rawAuthority(): String? = authority?.substringAfterLast('@')
