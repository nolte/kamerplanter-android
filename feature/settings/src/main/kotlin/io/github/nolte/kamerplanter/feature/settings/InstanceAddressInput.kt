package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.InstanceAddressPolicy
import io.github.nolte.kamerplanter.core.connection.PayloadRefusal
import io.github.nolte.kamerplanter.core.connection.addressRefusalOf

/**
 * What the app makes of a server address the user *typed*, as opposed to one a QR payload
 * or a `/connect` link carried (F-7, F-11).
 *
 * The scanned paths receive full URLs, because a machine wrote them. A person writes
 * `garden.example.org` — the scheme is the app's business, not theirs — so a schemeless
 * address is completed with `https` before the policy judges it. Never with `http`: the
 * cleartext exception exists for an address the user *deliberately* spelled `http://` on
 * their own network, and defaulting into it would downgrade everyone else silently.
 *
 * Judged by the same [InstanceAddressPolicy] as every other entry point, and refused with
 * the same [PayloadRefusal] wordings the scanner shows — one policy, one set of sentences,
 * whichever way an address arrives.
 */
internal object InstanceAddressInput {

    private const val SCHEME_SEPARATOR = "://"
    private const val DEFAULT_SCHEME = "https"

    /** The address as the connection attempt should use it. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.contains(SCHEME_SEPARATOR)) return trimmed
        return "$DEFAULT_SCHEME$SCHEME_SEPARATOR$trimmed"
    }

    /** Why the normalized address may not be used, or `null` when it may. */
    fun refusalFor(raw: String): PayloadRefusal? {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return PayloadRefusal.ADDRESS_UNUSABLE
        return if (InstanceAddressPolicy.permits(normalized)) null else addressRefusalOf(normalized)
    }
}
