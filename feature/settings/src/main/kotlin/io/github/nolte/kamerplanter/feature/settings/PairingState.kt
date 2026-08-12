package io.github.nolte.kamerplanter.feature.settings

/** The states of the Settings pairing flow (requirement R9). */
sealed interface PairingState {

    /** Reading the persisted pairing on startup; resolves to [Idle] or [Paired] (R12). */
    data object Loading : PairingState

    /** Not paired, ready to start scanning. */
    data object Idle : PairingState

    /** Camera is live; waiting for a valid pairing QR. */
    data object Scanning : PairingState

    /** The device camera could not be bound (in use, hardware error, no back camera). */
    data object CameraUnavailable : PairingState

    /** A payload was decoded; the (fake) backend call is in flight. */
    data class Verifying(val payload: PairingPayload) : PairingState

    /** Paired and persisted. */
    data class Paired(val payload: PairingPayload) : PairingState

    /** The backend rejected the pairing; [reason] is a diagnostic, not a UI string. */
    data class Failed(val reason: String) : PairingState
}
