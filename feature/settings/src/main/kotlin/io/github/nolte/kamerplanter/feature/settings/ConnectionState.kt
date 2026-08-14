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

        /** Camera is live; waiting for a pairing QR code (R7). */
        data object ScanningQr : Collecting {
            override val method: ConnectionMethod = ConnectionMethod.QR_PAIRING
        }

        /** Waiting for a base URL and a `kp_sk_…` key (R9). */
        data object ApiKeyEntry : Collecting {
            override val method: ConnectionMethod = ConnectionMethod.API_KEY
        }

        /** Waiting for the base URL of a light-mode instance (R10). */
        data object LightModeEntry : Collecting {
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
     * The attempt failed and nothing was stored (R14). [method] is carried so a retry
     * returns to the collection step the user came from; [reason] is a diagnostic, not a
     * UI string.
     */
    data class Failed(
        val method: ConnectionMethod,
        val reason: String,
    ) : ConnectionState
}

/** The collection step a method starts in — the state machine's only entry per method (R29). */
internal fun ConnectionMethod.collectionState(): ConnectionState.Collecting = when (this) {
    ConnectionMethod.QR_PAIRING -> ConnectionState.Collecting.ScanningQr
    ConnectionMethod.API_KEY -> ConnectionState.Collecting.ApiKeyEntry
    ConnectionMethod.LIGHT_MODE -> ConnectionState.Collecting.LightModeEntry
}
