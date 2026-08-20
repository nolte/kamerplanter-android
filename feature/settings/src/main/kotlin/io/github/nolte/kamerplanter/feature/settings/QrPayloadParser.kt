package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.DiscoveryLinkParser
import io.github.nolte.kamerplanter.core.connection.DiscoveryOutcome
import io.github.nolte.kamerplanter.core.connection.InstanceAddressPolicy
import io.github.nolte.kamerplanter.core.connection.PayloadRefusal
import io.github.nolte.kamerplanter.core.connection.PayloadVersion
import io.github.nolte.kamerplanter.core.connection.addressRefusalOf
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
    data class Refused(val reason: PayloadRefusal) : QrPayload
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
 * Deliberately pure Kotlin (no `android.net.Uri`) so it is unit-testable on the JVM.
 *
 * Three answers, not two. A stranger's QR, a bare string or a payload missing its fields yield
 * `null` — "invalid, keep scanning" (R44). A code that is recognisably kamerplanter's and
 * still unusable yields [QrPayload.Refused] with a reason, because telling someone holding
 * their own valid pairing code that it is not one sends them looking in the wrong place.
 */
object QrPayloadParser {

    private const val FIELD_VERSION = "v"
    private const val FIELD_URL = "url"
    private const val FIELD_CODE = "code"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): QrPayload? {
        val text = raw.trim()
        // The pairing payload first. The two shapes cannot be confused — one starts with `{`
        // and the other with a scheme — so the order is for readability, not correctness.
        return text.asPairing() ?: text.asLink()
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

        // Below the first version kamerplanter ever published, this is not a kamerplanter
        // payload at all — v1 is where the format starts. Claiming it would tell someone
        // scanning a foreign `{"v":0,…}` code to go and update their instance, which is the
        // same misdiagnosis this parser exists to end, pointing the other way.
        if (version < PayloadVersion.FIRST) return null
        if (version < PayloadVersion.SUPPORTED) {
            return QrPayload.Refused(PayloadRefusal.PAYLOAD_TOO_OLD)
        }

        // Newer than this build: claimed as ours, but only on evidence. A version bump may
        // rename fields — that is what versions are for, so this cannot demand *this* build's
        // field names — while a bare `{"v":9}` from somebody else's app is not a kamerplanter
        // code and must not be reported as one. A pairing payload names an instance and
        // carries a credential; something recognisable as either is the anchor.
        if (version > PayloadVersion.SUPPORTED) {
            return QrPayload.Refused(PayloadRefusal.PAYLOAD_TOO_NEW).takeIf { fields.looksLikePairing() }
        }

        // Missing fields at the version this build does read: indistinguishable from a foreign
        // JSON QR code, so not claimed as ours.
        val code = fields.text(FIELD_CODE) ?: return null
        val url = fields.text(FIELD_URL) ?: return null

        // The same address rule as the discovery link. A pairing payload names the instance
        // that will receive its one-time credential, so an address this app may not talk to is
        // not a payload it may act on. The two refusals are told apart because the advice is:
        // "your instance has no TLS" is help, and it is wrong for an address with no scheme.
        if (!InstanceAddressPolicy.permits(url)) {
            return QrPayload.Refused(addressRefusalOf(url))
        }
        return QrPayload.Pairing(ConnectionRequest.QrPairing(baseUrl = url, code = code))
    }

    /**
     * A `/connect` link, usable or refused; `null` when it is not one of ours.
     *
     * Both halves come from [DiscoveryLinkParser.read], which is the one place that decides
     * what a link is. It used to be decided here as well, from the version and the address
     * separately, and the deep-link channel had no way to ask — so the same URL explained
     * itself when scanned and vanished when tapped (#40).
     */
    private fun String.asLink(): QrPayload? = when (val outcome = DiscoveryLinkParser.read(this)) {
        null -> null
        is DiscoveryOutcome.Usable -> QrPayload.Discovery(outcome.link.baseUrl)
        is DiscoveryOutcome.Refused -> QrPayload.Refused(outcome.reason)
    }

    /**
     * Whether an object of an unknown version is recognisably a pairing payload.
     *
     * Asks for the field names this build knows, and nothing more. A version bump is allowed
     * to rename them, so this can be wrong — but only in the harmless direction: a v2 that
     * renamed BOTH fields falls through as a stranger's code, which is what the scanner said
     * before any of this and is honest about what the app can tell.
     *
     * The alternative was worse and shipped briefly: a list of names a future format might
     * plausibly use (`server`, `token`, `origin`…). Those are generic enough to appear in
     * anybody's QR code, so a foreign `{"v":2,"server":…,"token":…}` was claimed as
     * kamerplanter's and the user told to update their instance for a code that was never
     * ours — the same misdiagnosis this parser exists to end, pointing outward.
     */
    private fun JsonObject.looksLikePairing(): Boolean =
        keys.any { it == FIELD_URL || it == FIELD_CODE }

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
