package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionMethod
import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.Tenant

/**
 * The states of the Settings connection flow (requirement R29). It widens the clickable
 * dummy's QR-only `loading → idle → scanning → verifying → paired | failed` into a
 * method-aware shape: collection happens per [ConnectionMethod], verification is shared,
 * and tenant selection sits between verification and a connection that counts as
 * established (R15).
 *
 * ```
 * Loading ─▶ Disconnected ─(startConnecting)─▶ Collecting.ScanningQr   ─┐
 *                ▲                             Collecting.ApiKeyEntry  ─┤
 *                │                             Collecting.LightModeEntry┤
 *
 * A `/connect` link (#13) enters from the side: Discovered rests beside Disconnected and
 * Connected, and leaves through the same startConnecting and cancel as everything else.
 *                │                                                      ▼
 *                │                                                  Verifying
 *                │                                                      │
 *                │            ┌──────────── SelectingTenant ◀───────────┤
 *                │            ▼                                         ▼
 *                └─(disconnect)── Connected ◀────────────────────── Failed
 * ```
 *
 * [Connected] is also a legal starting point for a new attempt: the user may change the
 * connection from any method to any other at any time, and a failed attempt leaves the
 * previous connection in place (R14, R27).
 *
 * One edge the diagram flattens: picking a tenant returns to [Verifying] while both halves
 * are written. The step is no longer *verifying* anything, but it is the same thing to the
 * user — work in flight, no input accepted — and giving it its own state would let a second
 * tap start a second write.
 */
sealed interface ConnectionState {

    /** Reading the persisted connection on startup; resolves to [Disconnected] or [Connected] (R20). */
    data object Loading : ConnectionState

    /** Nothing is stored; the user picks one of the three methods (R6, R28). */
    data object Disconnected : ConnectionState

    /** Collecting what one method needs, before anything is verified. */
    sealed interface Collecting : ConnectionState {

        val method: ConnectionMethod

        /**
         * Camera is live; waiting for a pairing QR code (R7).
         *
         * [prefilledBaseUrl] is the instance a `/connect` link named, when the user got here
         * from one. The pairing payload carries its own URL and that is what the attempt uses,
         * so this is not a prefill in the sense the other two methods mean — it is kept because
         * dropping it would leave the screen unable to say that the code just scanned belongs
         * to a *different* instance from the one the link promised.
         */
        data class ScanningQr(val prefilledBaseUrl: String? = null) : Collecting {
            override val method: ConnectionMethod = ConnectionMethod.QR_PAIRING
        }

        /**
         * Waiting for a base URL and a `kp_sk_…` key (R9).
         *
         * [prefilledBaseUrl] is set when the user arrived from a `/connect` link, which
         * carries the instance's address and nothing else (#13). Neither entry surface exists
         * yet, so nothing renders it today — but the address is what the link is *for*, and
         * dropping it here would mean re-typing something the app already knows.
         */
        data class ApiKeyEntry(val prefilledBaseUrl: String? = null) : Collecting {
            override val method: ConnectionMethod = ConnectionMethod.API_KEY
        }

        /** Waiting for the base URL of a light-mode instance (R10); see [ApiKeyEntry]. */
        data class LightModeEntry(val prefilledBaseUrl: String? = null) : Collecting {
            override val method: ConnectionMethod = ConnectionMethod.LIGHT_MODE
        }
    }

    /** The device camera could not be bound (in use, hardware error, no back camera) (R44). */
    data object CameraUnavailable : ConnectionState

    /**
     * The instance is being probed and the credential proven; nothing is stored yet (R13).
     *
     * Carries only [method], never the [ConnectionRequest] it was started from: this state is
     * observed by the UI, and a request holds the plaintext pairing code or `kp_sk_…` key
     * (R19). Masking `toString` would not be enough — `state.value` is public, so a collector,
     * a Compose state inspector or a state-dumping crash handler could read the field itself.
     * The request waits in the ViewModel alongside the credential instead.
     */
    data class Verifying(val method: ConnectionMethod) : ConnectionState

    /**
     * Verified, but the credential addresses more than one tenant, so the user picks the
     * one to store (R15).
     *
     * Like [Verifying], this deliberately carries no [ConnectionRequest] — only what the UI
     * has to render. The request and the credential are both held privately by
     * [SettingsViewModel] until the tenant is settled and the connection can be composed.
     */
    data class SelectingTenant(
        val method: ConnectionMethod,
        val tenants: List<Tenant>,
        val identity: String?,
    ) : ConnectionState

    /** Verified, persisted, and surviving an app restart (R13, R20). */
    data class Connected(val connection: Connection) : ConnectionState

    /**
     * A `/connect` link named an instance, and the user has not decided what to do about it
     * yet (#13).
     *
     * A resting state, not a step in an attempt: the link carries no credential and calls no
     * endpoint, so nothing has been proven and nothing is in flight. It exists because
     * arriving from a scanned link straight into a camera viewfinder would be an odd answer to
     * "open this instance" — and because [relation] is the one thing the user needs told
     * before anything happens.
     */
    data class Discovered(
        val baseUrl: String,
        val relation: DiscoveredInstance,
    ) : ConnectionState

    /**
     * The attempt failed and nothing was stored (R14). [method] is carried so a retry
     * returns to the collection step the user came from; [reason] is a diagnostic, not a
     * UI string.
     */
    data class Failed(
        val method: ConnectionMethod,
        val reason: String,
        /**
         * The address the attempt was made against.
         *
         * Carried so the screen can tell a home instance from a public one. The advice that
         * follows a failure differs completely between them, and a hint about local-network
         * access shown after a refused credential on a public host is noise — the kind a user
         * learns to skip, taking the useful hints with it.
         */
        val baseUrl: String,
    ) : ConnectionState
}

/**
 * How a discovered instance stands to the one already connected.
 *
 * The distinction is the whole reason a link does not simply start a connection attempt: a
 * user who scans the code on their own instance while already connected to it wants to be told
 * so, not walked through pairing again — and one whose phone is connected somewhere else is
 * about to replace a working connection and should know that before, not after.
 */
enum class DiscoveredInstance {

    /** Nothing is connected; the link is simply an offer. */
    NEW,

    /** Already connected to this very instance — the link asks for something already true. */
    ALREADY_CONNECTED,

    /** Connected to a different instance, which continuing would replace. */
    REPLACES_ANOTHER,
}

/** The collection step a method starts in — the state machine's only entry per method (R29). */
internal fun ConnectionMethod.collectionState(
    prefilledBaseUrl: String? = null,
): ConnectionState.Collecting = when (this) {
    ConnectionMethod.QR_PAIRING -> ConnectionState.Collecting.ScanningQr(prefilledBaseUrl)
    ConnectionMethod.API_KEY -> ConnectionState.Collecting.ApiKeyEntry(prefilledBaseUrl)
    ConnectionMethod.LIGHT_MODE -> ConnectionState.Collecting.LightModeEntry(prefilledBaseUrl)
}
