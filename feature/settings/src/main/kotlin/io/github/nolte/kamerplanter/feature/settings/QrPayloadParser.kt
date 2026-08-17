package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.DiscoveryLinkParser
import io.github.nolte.kamerplanter.core.connection.InstanceAddressPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What a scanned kamerplanter QR code turns out to be.
 *
 * Two shapes with two meanings, and they were briefly one: a discovery link was mapped
 * straight to a light-mode connection request, which reads a *location* as a *mode*. The link
 * says where an instance is and nothing else — the instance may well have accounts — so the
 * two cannot share a return type without the caller guessing.
 */
sealed interface QrPayload {

    /** A pairing payload, complete with the one-time credential it carries. */
    data class Pairing(val request: ConnectionRequest.QrPairing) : QrPayload

    /** A credential-free `/connect` link: an address, and an offer to use it. */
    data class Discovery(val baseUrl: String) : QrPayload

    /**
     * Recognisably a kamerplanter code, and one this build will not act on.
     *
     * Distinct from "not ours" because the two need opposite words. A refused payload used to
     * come back as `null` like a stranger's QR code, so someone scanning their own valid
     * pairing code was told it was not one — the same misdiagnosis this scanner was rewritten
     * to end, one layer further in.
     */
    data class Refused(val reason: RefusedReason) : QrPayload
}

/** Why a kamerplanter code was recognised and still refused. */
enum class RefusedReason {

    /** A payload version this build predates; reading it anyway is how fields change meaning. */
    UNSUPPORTED_VERSION,

    /**
     * The instance address in the payload is one the app may not talk to — plain `http` to a
     * routable host, or a scheme that is not http at all.
     */
    ADDRESS_NOT_ALLOWED,
}

/**
 * Reads a kamerplanter QR code into what it means.
 *
 * The pairing payload is the versioned JSON object the instance's web UI encodes verbatim:
 *
 * ```
 * {"v": 1, "url": "https://garten.example.org", "code": "Qm5kR2xoY0dWeUlHTnZaR1Vn…"}
 * ```
 *
 * Opaque JSON rather than a URL, and deliberately so: `code` is a one-time credential, and a
 * payload a phone's system camera recognised as openable could be routed to whichever app the
 * user picked from Android's chooser. Only the credential-free discovery link
 * (`https://…/connect?v=1`) may be publicly recognisable.
 *
 * Both are read, because the web UI shows them on the same dialogue and someone pointing a
 * camera at one cannot be expected to know which they are looking at — but they are returned
 * as the different things they are.
 *
 * Deliberately pure Kotlin (no `android.net.Uri`) so it is unit-testable on the JVM. Anything
 * that is not one of these two shapes — a foreign QR, a bare string, a missing field — yields
 * `null`, which the caller treats as "invalid, keep scanning" (R44).
 */
object QrPayloadParser {

    /**
     * The payload version this app understands.
     *
     * Present from v1 so a scanner can refuse a payload it predates rather than mis-parse it
     * (R7). A version this build has never heard of describes a shape it cannot read, and
     * reading one anyway is how a client acts on a field that has changed meaning — with a
     * one-time credential in it.
     */
    private const val SUPPORTED_VERSION = 1

    private const val FIELD_VERSION = "v"
    private const val FIELD_URL = "url"
    private const val FIELD_CODE = "code"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): QrPayload? {
        val text = raw.trim()
        // The pairing payload first. The two shapes cannot be confused — one starts with `{`
        // and the other with a scheme — so the order is for readability, not correctness.
        return text.asPairing()
            ?: DiscoveryLinkParser.parse(text)?.let { QrPayload.Discovery(it.baseUrl) }
    }

    /**
     * The pairing payload, or why it was refused — `null` only when this is not one at all.
     *
     * A version this build predates and an address it may not talk to are both recognisably
     * ours, and both used to leave here as `null`, which the scanner reports as a stranger's
     * code. That told a self-hoster running an instance on a routable address without TLS
     * that their own valid pairing code was not a kamerplanter code.
     */
    private fun String.asPairing(): QrPayload? {
        val fields = runCatching { json.parseToJsonElement(this) }.getOrNull() as? JsonObject
            ?: return null
        val version = fields.number(FIELD_VERSION) ?: return null
        val code = fields.text(FIELD_CODE) ?: return null
        val url = fields.text(FIELD_URL) ?: return null
        // Shape first, judgement after: a payload missing `url` or `code` is indistinguishable
        // from a foreign JSON QR code and stays `null`, while one that carries both is ours
        // and earns a reason.
        if (version != SUPPORTED_VERSION) return QrPayload.Refused(RefusedReason.UNSUPPORTED_VERSION)
        // The same address rule as the discovery link. A pairing payload names the instance
        // that will receive its one-time credential, so an address this app may not talk to is
        // not a payload it may act on.
        if (!InstanceAddressPolicy.permits(url)) {
            return QrPayload.Refused(RefusedReason.ADDRESS_NOT_ALLOWED)
        }
        return QrPayload.Pairing(ConnectionRequest.QrPairing(baseUrl = url, code = code))
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    /**
     * A numeric field, refusing a quoted one.
     *
     * `"v": "1"` is not the documented payload, and accepting it would mean guessing that a
     * producer which got the type wrong got the rest right.
     */
    private fun JsonObject.number(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}
