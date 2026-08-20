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
 * What reading a candidate `/connect` link produced.
 *
 * Two answers where there was one, and the missing one is what #40 was about: a refusal that
 * cannot be carried is a refusal the deep-link channel drops on the floor, leaving the user
 * looking at an app that opened and did nothing.
 */
sealed interface DiscoveryOutcome {

    /** A link this build can act on. */
    data class Usable(val link: DiscoveryLink) : DiscoveryOutcome

    /** Recognisably kamerplanter's, and not usable — with the reason to say out loud. */
    data class Refused(val reason: PayloadRefusal) : DiscoveryOutcome
}

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
 *
 * The one reader, for both entry points. A link tapped in a browser and the same link scanned
 * in-app used to be read by two different code paths, and only one of them could name a
 * refusal — so the identical URL explained itself under the scanner and vanished when tapped
 * (#40).
 */
object DiscoveryLinkParser {

    private const val PATH_SEGMENT = "connect"
    private const val PARAM_VERSION = "v"

    /** The only schemes a kamerplanter instance's own web UI can build a link from. */
    private val WEB_SCHEMES = setOf("http", "https")

    /**
     * What a string turns out to be, as far as the `/connect` contract goes.
     *
     * `null` for anything that is not one of ours — a foreign URL that happens to carry
     * `/connect`, a bare string, a link declaring no readable version. Those are dropped
     * without a word wherever they arrive, deliberately: the user asked to open a web address,
     * and landing in this app on a screen complaining about it is a worse answer than the app
     * not claiming it.
     *
     * Everything else is claimed and answered for. A link this build cannot read is still
     * recognisably kamerplanter's, and both the scanner and the deep-link channel say the same
     * thing about it because both ask this one function (#40).
     */
    fun read(raw: String): DiscoveryOutcome? {
        val shape = raw.shape() ?: return null
        // No version, no claim: the contract specifies one, and a `/connect` path on its own
        // is a path anybody's web app may have.
        val version = shape.version ?: return null
        return when {
            // Below the first version kamerplanter ever published this is not its payload at
            // all. Claiming it would tell someone to update an instance that was never ours.
            version < PayloadVersion.FIRST -> null
            version < PayloadVersion.SUPPORTED ->
                DiscoveryOutcome.Refused(PayloadRefusal.PAYLOAD_TOO_OLD)
            version > PayloadVersion.SUPPORTED ->
                DiscoveryOutcome.Refused(PayloadRefusal.PAYLOAD_TOO_NEW)
            // Whether the address may be used is asked only once the version says this build
            // can read the link at all: an app that cannot read the payload cannot judge the
            // address it names either.
            !InstanceAddressPolicy.permits(shape.uri.scheme, shape.host) ->
                DiscoveryOutcome.Refused(addressRefusalOf(raw.trim()))
            else -> DiscoveryOutcome.Usable(DiscoveryLink(baseUrl = shape.baseUrl()))
        }
    }

    /**
     * The address, rebuilt from the parts that were read.
     *
     * The scheme is carried over, not assumed: a development instance is reached over `http`,
     * and rewriting its address to `https` would send the app somewhere that does not answer.
     */
    private fun LinkShape.baseUrl(): String {
        val scheme = uri.scheme.lowercase()
        val pathPrefix = if (prefix.isEmpty()) "" else "/$prefix"
        return "$scheme://$host${uri.explicitPort()}$pathPrefix"
    }

    /**
     * A candidate taken apart, before anything has been decided about it.
     *
     * Reading and deciding are separate on purpose. This used to be two hand-copied readers —
     * same URI parse, same host lookup, same path rule, same query decode — which had already
     * drifted apart: one compared the version as text, the other as a number, and the two
     * codes on one dialogue contradicted each other frame by frame.
     *
     * The address policy is deliberately *not* asked here, so a link at an address this app
     * may not use is still recognised as its link: a plain-`http` instance's link is the
     * instance's own, and both entry points have to say so rather than call it foreign.
     *
     * The shape rule holds the line against codes that were never ours. A kamerplanter
     * `/connect` link is built from `window.location.origin`, so it is `http` or `https` and
     * nothing else; without that check, `myapp://server/connect?v=2` would pass as
     * "recognisably kamerplanter's, newer than this app".
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
