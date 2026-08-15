package io.github.nolte.kamerplanter.core.connection

import java.net.URI

/**
 * An instance address arrived at from a `/connect` link.
 *
 * Credential-free by construction, and that is the whole point of the shape. The pairing QR
 * carries a one-time `code`; this carries an origin and nothing else, which is why it may be a
 * publicly recognisable `https` URL that a phone's system camera will offer to open, while the
 * pairing payload stays opaque JSON that only this app's scanner reads. Turning the pairing
 * payload into a link would make a credential interceptable by whatever app the user picks
 * from the chooser — the separation is load-bearing.
 */
data class DiscoveryLink(
    /** Where the instance lives, ready to be handed to the connection flow. */
    val baseUrl: String,
)

/**
 * Reads the `https://<instance>/connect?v=1` link the backend documents this app as handling.
 *
 * Pure Kotlin rather than `android.net.Uri` so it is unit-testable on the JVM, in keeping with
 * [QrPayloadParser]. Anything that is not this exact shape yields `null`, and the caller treats
 * that as "not a link for us" rather than as an error worth showing.
 */
object DiscoveryLinkParser {

    /**
     * The payload version this app understands.
     *
     * Shared version space with the pairing QR (R7): a version this build has never heard of
     * describes a payload it cannot read, and reading it anyway is how a client ends up acting
     * on a field that has changed meaning. Refused rather than interpreted — including when
     * absent, because the contract specifies it and a link without one is either not
     * kamerplanter's or is from a shape this app was not written against.
     */
    private const val SUPPORTED_VERSION = "1"

    private const val PATH_SEGMENT = "connect"
    private const val PARAM_VERSION = "v"

    fun parse(raw: String): DiscoveryLink? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (query(uri.rawQuery)[PARAM_VERSION] != SUPPORTED_VERSION) return null

        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        if (segments.lastOrNull() != PATH_SEGMENT) return null

        // Everything before the `connect` segment belongs to the instance: kamerplanter can be
        // hosted under a path prefix, and dropping it would send the app to the wrong address.
        // The manifest filter only matches `/connect` at the root, so a prefixed instance
        // cannot reach this today — but that is the platform contract's limit, not a reason for
        // the parser to lose the information when it does arrive.
        val prefix = segments.dropLast(1).joinToString("/")
        val base = "https://$host${uri.explicitPort()}" + if (prefix.isEmpty()) "" else "/$prefix"
        return DiscoveryLink(baseUrl = base)
    }

    private fun query(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            pair.substring(0, separator) to pair.substring(separator + 1)
        }.toMap()
    }
}

/**
 * Whether two base URLs address the same kamerplanter instance.
 *
 * A textual comparison would call `https://plants.example/` and `https://plants.example`
 * different instances, and tell a user who scanned the code on the very instance they are
 * connected to that continuing would replace their connection. Scheme and host are compared
 * case-insensitively and a trailing slash is ignored; the **path is not**, because
 * kamerplanter can be hosted under a prefix and two prefixes on one host really are two
 * instances.
 */
fun String.sameInstanceAs(other: String): Boolean = normalizedInstance() == other.normalizedInstance()

private fun String.normalizedInstance(): String {
    val uri = runCatching { URI(trim()) }.getOrNull() ?: return trim().lowercase()
    val scheme = uri.scheme?.lowercase().orEmpty()
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.path.orEmpty().trimEnd('/')
    return "$scheme://$host${uri.explicitPort()}$path"
}

/**
 * The port, unless it is the one the scheme already implies.
 *
 * A poster that spells out `:443` names the same instance as one that does not, and treating
 * them as different tells a user that continuing would replace the connection they are already
 * on — the exact failure the comparison exists to avoid.
 */
private fun URI.explicitPort(): String = when (port) {
    -1, DEFAULT_PORTS[scheme?.lowercase()] -> ""
    else -> ":$port"
}

private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80

private val DEFAULT_PORTS = mapOf("https" to HTTPS_PORT, "http" to HTTP_PORT)
