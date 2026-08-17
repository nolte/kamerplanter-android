package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.DiscoveryLinkParser
import io.github.nolte.kamerplanter.core.connection.InstanceAddressPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

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

    /**
     * A payload from a release this build predates.
     *
     * Separate from [PAYLOAD_TOO_OLD] because the advice is opposite: here the app is behind
     * and the user updates it. One shared "version mismatch" would have told the owner of an
     * older instance to update the wrong side.
     */
    PAYLOAD_TOO_NEW,

    /**
     * A payload older than this build reads — the instance is behind, not the app.
     *
     * Unreachable while v1 is both the first version and the supported one, and kept because
     * the day this app moves to v2 is the day an un-updated instance needs to be told which
     * side to update.
     */
    PAYLOAD_TOO_OLD,

    /**
     * Plain `http` to a routable host: an address the app will only use inside a private
     * network, because the payload carries a one-time credential.
     */
    ADDRESS_NOT_ENCRYPTED,

    /** An address this app cannot use at all — no scheme, or one that is not http(s). */
    ADDRESS_UNUSABLE,
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

    /** The first payload version kamerplanter published; nothing below it is one. */
    private const val FIRST_VERSION = 1

    /**
     * Field names a later payload version might plausibly use for the same two things.
     *
     * A guess, and stated as one. It only decides whether an unreadable code is reported as
     * "too new for this app" or as a stranger's — both of which are honest, and the second of
     * which is the safe way to be wrong.
     */
    private val FUTURE_FIELD_HINTS = setOf("origin", "instance", "server", "token", "pairing")

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): QrPayload? {
        val text = raw.trim()
        // The pairing payload first. The two shapes cannot be confused — one starts with `{`
        // and the other with a scheme — so the order is for readability, not correctness.
        return text.asPairing()
            ?: DiscoveryLinkParser.parse(text)?.let { QrPayload.Discovery(it.baseUrl) }
            // A `/connect` link this build cannot read is still recognisably ours, and gets
            // the same answer the pairing payload does. Reported as a stranger's code, the two
            // codes on one dialogue would contradict each other frame by frame.
            ?: text.asRefusedLink()
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
        if (version < FIRST_VERSION) return null
        if (version < SUPPORTED_VERSION) return QrPayload.Refused(RefusedReason.PAYLOAD_TOO_OLD)

        // Newer than this build: claimed as ours, but only on evidence. A version bump may
        // rename fields — that is what versions are for, so this cannot demand *this* build's
        // field names — while a bare `{"v":9}` from somebody else's app is not a kamerplanter
        // code and must not be reported as one. A pairing payload names an instance and
        // carries a credential; something recognisable as either is the anchor.
        if (version > SUPPORTED_VERSION) {
            return QrPayload.Refused(RefusedReason.PAYLOAD_TOO_NEW).takeIf { fields.looksLikePairing() }
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
            return QrPayload.Refused(url.addressRefusal())
        }
        return QrPayload.Pairing(ConnectionRequest.QrPairing(baseUrl = url, code = code))
    }

    /**
     * Whether the address is unencrypted-but-understood, or not one this app can use at all.
     *
     * Read through [URI], on the same parse the policy used to refuse it. Matching the raw
     * string's prefix instead let the two disagree: a leading space made a plainly
     * unencrypted address read as unusable, and `http:///pair` — no host — was reported as an
     * encryption problem, which it is not.
     */
    /** A `/connect` link whose version this build does not read; `null` when it is not one. */
    private fun String.asRefusedLink(): QrPayload? =
        when (val version = DiscoveryLinkParser.declaredVersion(this)) {
            null -> null
            in Int.MIN_VALUE until FIRST_VERSION -> null
            in FIRST_VERSION until SUPPORTED_VERSION ->
                QrPayload.Refused(RefusedReason.PAYLOAD_TOO_OLD)
            SUPPORTED_VERSION -> null
            else -> QrPayload.Refused(RefusedReason.PAYLOAD_TOO_NEW)
        }

    private fun String.addressRefusal(): RefusedReason {
        val scheme = runCatching { URI(trim()).scheme }.getOrNull()?.lowercase()
        return if (scheme == "http") RefusedReason.ADDRESS_NOT_ENCRYPTED else RefusedReason.ADDRESS_UNUSABLE
    }

    /**
     * Whether an object of an unknown version is recognisably a pairing payload.
     *
     * Deliberately loose: it may not require this build's field names, since renaming them is
     * exactly what a version bump is allowed to do. It only asks whether anything here names
     * an instance or carries a credential.
     */
    private fun JsonObject.looksLikePairing(): Boolean =
        keys.any { it == FIELD_URL || it == FIELD_CODE || it in FUTURE_FIELD_HINTS }

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
