package io.github.nolte.kamerplanter.core.connection

/**
 * Why a payload this app recognises as kamerplanter's will not be acted on.
 *
 * Shared by both shapes the instance publishes — the pairing QR and the `/connect` link — and
 * by both ways one of them reaches the app: scanned in-app, or tapped as a deep link. That is
 * the whole reason it lives here rather than beside the scanner. The same URL used to explain
 * itself when scanned and vanish when tapped, and a reason that only one entry point can name
 * is a reason the other will keep forgetting (#40).
 *
 * Distinct from "not one of ours" throughout, which stays `null` at every layer: telling
 * someone holding their own valid code that it is not a kamerplanter code sends them looking
 * in the wrong place.
 */
enum class PayloadRefusal {

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
 * The policy's reason, in the terms a payload is refused in.
 *
 * Asked rather than re-derived: an earlier version decided by matching the raw string's prefix
 * while [InstanceAddressPolicy] refused on a parsed URI, and the two disagreed — a leading
 * space made a plainly unencrypted address read as unusable, and `http:///pair`, which names
 * no host, was reported as an encryption problem it does not have.
 */
fun addressRefusalOf(rawUrl: String): PayloadRefusal = when (InstanceAddressPolicy.refusalFor(rawUrl)) {
    AddressRefusal.NOT_ENCRYPTED -> PayloadRefusal.ADDRESS_NOT_ENCRYPTED
    // `null` cannot occur — callers only ask about an address already refused — and if it ever
    // does, the answer that promises the least is the right one.
    AddressRefusal.UNUSABLE, null -> PayloadRefusal.ADDRESS_UNUSABLE
}
