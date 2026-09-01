package io.github.nolte.kamerplanter.core.connection

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
     * [credential] is the secret the instance handed back — the redeemed session on the QR
     * path, the key itself on the API-key path, and [Credential.None] on the light-mode path,
     * which has no secret to hold. It travels here because verification is where it comes
     * into existence, and it goes straight into [CredentialStore], which encrypts it under an
     * Android Keystore-backed key (R17). It never reaches [ConnectionState]: a secret has no
     * business in observable UI state (R19).
     */
    data class Verified(
        /** The signed-in identity where the instance reports one, for display only (R26). */
        val identity: String?,
        val tenants: List<Tenant>,
        val credential: Credential,
        /**
         * The instance answered and works, but its backend `apiVersion` sits below the
         * floor this app was built against (F-10). Verification does not refuse it — the
         * user still reaches their plants — but the connection carries the fact so the
         * connected screen can warn, and so a feature may fall back where an endpoint the
         * floor promised is missing.
         */
        val belowVersionFloor: Boolean = false,
    ) : ConnectionResult

    /** [reason] is a diagnostic string, not a user-facing message. */
    data class Failure(
        val reason: String,
        /**
         * Whether the instance never answered, as opposed to answering with a refusal.
         *
         * Carried because the two need different advice and cannot be told apart from the text.
         * The screen uses it to decide whether "local network access is missing" is worth
         * raising: a refused pairing code is not that, and an attempt that produced no answer
         * might be — including against an address that *looks* public. Split-horizon DNS is the
         * ordinary way to self-host with TLS, so `https://garden.example.org` resolving to
         * `192.168.x.x` is the common case rather than the exotic one, and judging the address
         * by how it is spelled misses exactly that.
         */
        val unreachable: Boolean = false,
    ) : ConnectionResult
}

/**
 * App-owned seam in front of a kamerplanter instance (mirrors the UVC isolation pattern of
 * `:feature:microscope`): this states *what* has to be proven, never *how* it travels. No
 * networking type crosses this interface, so the generated OpenAPI client stays inside
 * `core/network/` (R4, ADR 0001) — which is exactly what lets the implementation live there
 * while `:feature:settings` keeps consuming nothing but this.
 *
 * The per-variant split this used to carry is gone. It existed because no real
 * implementation had been built: debug bound a backend-free fake, release bound a
 * placeholder that refused every attempt (R34). `NetworkConnectionClient` in `:core:network`
 * now serves both variants, so a release build can connect for the first time.
 */
interface ConnectionClient {

    /** Probes the instance and proves [request]; suspends for exactly one round trip. */
    suspend fun connect(request: ConnectionRequest): ConnectionResult

    /**
     * Ends the instance-side session behind [credential], where one exists (F-8).
     *
     * Only a paired session has one: an API key is not a session and light mode holds no
     * credential at all, so both are a no-op. The caller clears the device *first* and then
     * calls this best-effort — an unreachable instance must never block a local disconnect,
     * and the reverse order would strand a user who wants out while offline.
     *
     * Deliberately not `/auth/logout`: that route spends the `kp_refresh` cookie a native
     * client never holds and answers `403` (R24). The session-delete route is the instrument
     * a bearer client is meant to use.
     *
     * Throws when the instance could not be told; the caller decides that this is tolerable.
     */
    suspend fun endSession(baseUrl: String, credential: Credential)
}
