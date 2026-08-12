package io.github.nolte.kamerplanter.feature.settings

/**
 * A decoded kamerplanter pairing payload — the minimal shape the dummy parses from the
 * scanned QR code. The backend is self-hosted, so the base URL travels in the QR; the
 * real wire format and the OIDC handshake are owned upstream by
 * [kamerplanter#1118](https://github.com/nolte/kamerplanter/issues/1118) and stay behind
 * [PairingClient], so a format change is contained to this module.
 */
data class PairingPayload(
    val baseUrl: String,
    val code: String,
)

/** The outcome of a pairing attempt. */
sealed interface PairingResult {
    data object Success : PairingResult

    /** [reason] is a diagnostic string, not a user-facing message. */
    data class Failure(val reason: String) : PairingResult
}

/**
 * App-owned seam in front of the kamerplanter pairing backend (mirrors the UVC isolation
 * pattern of `:feature:microscope`). In this working copy the only implementation is
 * [FakePairingClient]; swapping in the #1118-backed real client is a one-line Hilt binding
 * change in `SettingsModule` with no UI change.
 */
interface PairingClient {
    suspend fun pair(payload: PairingPayload): PairingResult
}
