package io.github.nolte.kamerplanter.feature.settings

/**
 * What the user supplied for a connection attempt, before anything about it is proven —
 * one variant per [ConnectionMethod] (R6).
 *
 * Purely transient: it carries the raw secret into verification and is never persisted.
 * [toString] masks that secret so it cannot leak into a log or a test report (R19).
 */
sealed interface ConnectionRequest {

    val baseUrl: String

    val method: ConnectionMethod

    /** A scanned pairing payload, redeemable exactly once and only for 60–120 seconds (R7, R43). */
    data class QrPairing(
        override val baseUrl: String,
        val code: String,
    ) : ConnectionRequest {
        override val method: ConnectionMethod = ConnectionMethod.QR_PAIRING

        override fun toString(): String = "QrPairing(baseUrl=$baseUrl, code=${maskSecret(code)})"
    }

    /** A `kp_sk_…` key typed against a base URL (R9). */
    data class ApiKey(
        override val baseUrl: String,
        val key: String,
    ) : ConnectionRequest {
        override val method: ConnectionMethod = ConnectionMethod.API_KEY

        override fun toString(): String = "ApiKey(baseUrl=$baseUrl, key=${maskSecret(key)})"
    }

    /** A light-mode instance, which needs nothing but its base URL (R10). */
    data class LightMode(
        override val baseUrl: String,
    ) : ConnectionRequest {
        override val method: ConnectionMethod = ConnectionMethod.LIGHT_MODE
    }
}

/**
 * The outcome of proving a [ConnectionRequest] against the instance (R13). Nothing is
 * persisted until this reports [Verified]; a [Failure] leaves an existing connection
 * untouched (R14).
 */
sealed interface ConnectionResult {

    /**
     * The instance answered and accepted the request.
     *
     * [tenants] are the tenants this credential may address (R15): exactly one is adopted
     * automatically, several make the user pick, and none is a failure on a
     * credential-bearing method. The list is empty on the light-mode path, which has no
     * accounts to scope.
     *
     * The credential itself — the session's access and refresh tokens, or the API key —
     * is deliberately absent: it must be stored encrypted under an Android Keystore-backed
     * key (R17), which is its own step of [issue #8](https://github.com/nolte/kamerplanter-android/issues/8),
     * and it has no business travelling through the UI state.
     */
    data class Verified(
        /** The signed-in identity where the instance reports one, for display only (R26). */
        val identity: String?,
        val tenants: List<Tenant>,
    ) : ConnectionResult

    /** [reason] is a diagnostic string, not a user-facing message. */
    data class Failure(val reason: String) : ConnectionResult
}

/**
 * App-owned seam in front of a kamerplanter instance (mirrors the UVC isolation pattern of
 * `:feature:microscope`): this module states *what* has to be proven, never *how* it
 * travels. No networking type crosses this interface, so the generated OpenAPI client
 * stays inside `core/network/` (R4, ADR 0001).
 *
 * The only implementation in this working copy is [FakeConnectionClient]; the real one —
 * `/api/health` probe, pairing redemption, tenant lookup — replaces it through a single
 * Hilt binding, with no change to the state machine or the UI.
 */
interface ConnectionClient {

    /** Probes the instance and proves [request]; suspends for exactly one round trip. */
    suspend fun connect(request: ConnectionRequest): ConnectionResult
}
