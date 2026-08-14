package io.github.nolte.kamerplanter.core.connection

/**
 * How the app proves itself to a self-hosted kamerplanter instance. Exactly three methods
 * exist (requirement R6).
 *
 * Email + password is deliberately absent (R12): `POST /api/v1/auth/login` transports the
 * refresh token only as the HttpOnly cookie `kp_refresh`, so a natively logged-in client
 * would hold a 15-minute session with no documented way to renew it. The path returns once
 * the backend offers body transport on login (R38).
 */
enum class ConnectionMethod {

    /** A pairing QR code, redeemed at `POST /api/v1/auth/device-pairing/redeem` (R7, R8). */
    QR_PAIRING,

    /** A `kp_sk_…` API key that carries its own `tenant_scope` and needs no refresh (R9). */
    API_KEY,

    /**
     * A light-mode instance — `GET /api/health` reports `mode == "light"`, it has no
     * accounts, and `/api/v1/auth/…` answers `404`. Base URL only, no credential (R10, R11).
     */
    LIGHT_MODE,
}

/**
 * A tenant the connected instance offers. Tenant-scoped resources live under
 * `/api/v1/t/{slug}/…`, so the [slug] — not the display name — is what the connection
 * stores and what addresses the API later (R32).
 */
data class Tenant(
    val slug: String,
    val displayName: String = slug,
)

/**
 * An established connection to a kamerplanter instance: one of exactly three kinds (R6),
 * replacing the clickable dummy's single `{baseUrl, code}` pairing shape.
 *
 * This is the **non-secret** half only — base URL, method, tenant slug and what may be
 * displayed about the identity (R18). No refresh token, access token or API key ever
 * appears here, because those are stored encrypted under an Android Keystore-backed key
 * (R17) and must never reach plain DataStore. [ApiKey.keyHint] is already masked (R19).
 *
 * Light mode is structurally credential-free *and* tenant-free: an instance without
 * accounts has nothing to scope, which is why the tenant is a property of the two
 * credential-bearing kinds rather than of the interface.
 */
sealed interface Connection {

    /** The instance's base URL, as the user supplied it or as the pairing payload carried it. */
    val baseUrl: String

    val method: ConnectionMethod

    /** Paired by QR code; backed by a rotating refresh token (R8, R21, R22). */
    data class QrPairing(
        override val baseUrl: String,
        val tenantSlug: String,
        /** The signed-in identity where the instance reports one, for display only (R26). */
        val identity: String? = null,
    ) : Connection {
        override val method: ConnectionMethod = ConnectionMethod.QR_PAIRING
    }

    /**
     * Connected with a `kp_sk_…` key. The tenant comes from the key's own `tenant_scope`
     * (R9, R15) where the instance will report it — that route is gated behind the MCP flag
     * and 404s when MCP is off, in which case the tenants the key can reach are listed
     * instead, so a valid key still connects.
     */
    data class ApiKey(
        override val baseUrl: String,
        val tenantSlug: String,
        /** Masked remainder of the key — the most the UI may show of a secret (R19). */
        val keyHint: String,
    ) : Connection {
        override val method: ConnectionMethod = ConnectionMethod.API_KEY
    }

    /** Connected to a light-mode instance: no credential, no tenant (R10). */
    data class LightMode(
        override val baseUrl: String,
    ) : Connection {
        override val method: ConnectionMethod = ConnectionMethod.LIGHT_MODE
    }
}

/**
 * The most of a secret that may ever appear in a log line or a UI string — its last few
 * characters (R19). Anything short enough to be guessed from a hint is masked entirely.
 *
 * Public rather than `internal` because the masking rule has to hold on both sides of the
 * module boundary: [Credential] masks its tokens here, and `ConnectionRequest` in
 * `:feature:settings` masks the pairing code and API key the user just supplied. A second
 * copy over there would be a rule that can drift, and the direction it drifts in is one
 * where a secret starts appearing in full.
 */
fun maskSecret(secret: String): String =
    if (secret.length <= HINT_LENGTH) MASK else MASK + secret.takeLast(HINT_LENGTH)

private const val HINT_LENGTH = 4
private const val MASK = "…"
