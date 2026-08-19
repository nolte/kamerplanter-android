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
 * The scheme is whatever the instance's own web UI was reached through — the link is built from
 * `window.location.origin` there — so a development instance emits `http://`. Which of those
 * are acceptable is [InstanceAddressPolicy]'s decision, not this parser's.
 *
 * Pure Kotlin rather than `android.net.Uri` so it is unit-testable on the JVM, in keeping with
 * [QrPayloadParser]. Anything that is not this exact shape yields `null`, and the caller treats
 * that as "not a link for us" rather than as an error worth showing.
 */
object DiscoveryLinkParser {

    private const val PATH_SEGMENT = "connect"
    private const val PARAM_VERSION = "v"

    /** The only schemes a kamerplanter instance's own web UI can build a link from. */
    private val WEB_SCHEMES = setOf("http", "https")

    /**
     * The version a `/connect` link declares, for anything shaped like one of ours.
     *
     * Offered so a caller can tell "a link from a release this app predates" from "not our
     * link at all". [parse] answers `null` to both, and the scanner words that as somebody
     * else's QR code — so an instance updated to v2 showed "newer than the app" for its
     * pairing code and "not a kamerplanter code" for its discovery link, depending on which
     * the camera decoded first. Both hang on the same dialogue.
     *
     * Deliberately does NOT ask [InstanceAddressPolicy] whether the address may be used. It
     * did, and that rebuilt the same contradiction one class down: a self-hosted instance on
     * plain `http` emits an `http://` link from its own web UI, so its pairing code answered
     * "reached without encryption" while its link — on that identical address — answered "not
     * a kamerplanter code". Whether an address is allowed is a separate question from whose
     * link it is, and the caller asks it separately.
     *
     * `null` when the shape is not a `/connect` link at all, and when the link declares no
     * readable version — the contract specifies one.
     */
    fun declaredVersion(raw: String): Int? = raw.shape()?.version

    fun parse(raw: String): DiscoveryLink? {
        val shape = raw.shape() ?: return null
        if (shape.version != PayloadVersion.SUPPORTED) return null
        if (!InstanceAddressPolicy.permits(shape.uri.scheme, shape.host)) return null

        // The scheme is carried over, not assumed: a development instance is reached over
        // `http`, and rewriting its address to `https` would send the app somewhere that does
        // not answer.
        val scheme = shape.uri.scheme.lowercase()
        val prefix = if (shape.prefix.isEmpty()) "" else "/${shape.prefix}"
        return DiscoveryLink(baseUrl = "$scheme://${shape.host}${shape.uri.explicitPort()}$prefix")
    }

    /**
     * Everything both readers above need, read once.
     *
     * They used to be hand-copied twins — same URI parse, same host lookup, same policy check,
     * same path rule, same query decode — and had already drifted apart: one compared the
     * version as text, the other as a number. That is the drift this very file documents as
     * how half the underscore fix shipped, so the two now differ only in what they decide,
     * never in what they read.
     *
     * What they decide differently is deliberate and new: the address policy moved out of here
     * into [parse] alone, so [declaredVersion] can name the version of a link at an address
     * this app may not use. That is the whole point — a plain-`http` instance's link is still
     * its link, and the scanner has to say so rather than call it foreign.
     *
     * The shape rule still holds the line against codes that were never ours. A kamerplanter
     * `/connect` link is built from `window.location.origin`, so it is `http` or `https` and
     * nothing else; without that check here, dropping the policy check let `myapp://server/
     * connect?v=2` through as "recognisably kamerplanter's, newer than this app".
     */
    private fun String.shape(): LinkShape? {
        val uri = runCatching { URI(trim()) }.getOrNull() ?: return null
        // Whose link it is, decided before whether its address may be used. A `/connect` link
        // comes out of a browser's own origin, so a scheme that is not http(s) is not one of
        // ours at all — as opposed to `http` to a routable host, which is ours and refused.
        if (uri.scheme?.lowercase() !in WEB_SCHEMES) return null
        // Read through the shared helper: `java.net.URI` refuses to name a host containing an
        // underscore, and this parser reading it differently from InstanceAddressPolicy is how
        // one instance's pairing code was accepted while its `/connect` link was called
        // foreign — both codes hang on the same dialogue.
        val host = uri.hostOrAuthority() ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        if (segments.lastOrNull() != PATH_SEGMENT) return null
        // Everything before the `connect` segment belongs to the instance: kamerplanter can be
        // hosted under a path prefix, and dropping it would send the app to the wrong address.
        // The manifest filter only matches `/connect` at the root, so a prefixed instance
        // cannot reach this today — but that is the platform contract's limit, not a reason for
        // the parser to lose the information when it does arrive.
        return LinkShape(
            uri = uri,
            host = host,
            prefix = segments.dropLast(1).joinToString("/"),
            version = query(uri.rawQuery)[PARAM_VERSION]?.toIntOrNull(),
        )
    }

    /** A `/connect` link taken apart, before anything has been decided about it. */
    private data class LinkShape(
        val uri: URI,
        val host: String,
        val prefix: String,
        val version: Int?,
    )

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
    val host = uri.hostOrAuthority()?.lowercase().orEmpty()
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
private fun URI.explicitPort(): String = when (val port = portOrAuthority()) {
    -1, DEFAULT_PORTS[scheme?.lowercase()] -> ""
    else -> ":$port"
}

private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80

private val DEFAULT_PORTS = mapOf("https" to HTTPS_PORT, "http" to HTTP_PORT)
