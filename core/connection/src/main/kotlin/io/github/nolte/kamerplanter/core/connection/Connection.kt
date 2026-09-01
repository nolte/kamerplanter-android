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
 * Light mode is structurally credential-free, but **not** tenant-free. It was modelled that
 * way — an instance without accounts looked like an instance with nothing to scope — and the
 * running backend says otherwise: a light-mode instance serves `GET /api/v1/tenants`
 * unauthenticated and answers with its system tenant, while every plant, diary and pest route
 * lives under `/api/v1/t/{slug}/…`. A connection without a slug can therefore address nothing,
 * which is exactly what it did: the app connected, and the plant list was empty for every
 * light-mode instance because it had no tenant to ask about.
 */
sealed interface Connection {

    /** The instance's base URL, as the user supplied it or as the pairing payload carried it. */
    val baseUrl: String

    val method: ConnectionMethod

    /**
     * Whether the instance's backend `apiVersion` sat below this app's compatibility floor
     * when the connection was verified (F-10). Checked at connect time only — an instance
     * upgraded afterwards keeps the flag until the next connect — and carried here rather
     * than in UI state so the warning survives an app restart along with the connection
     * it describes.
     */
    val belowVersionFloor: Boolean

    /**
     * The tenant this connection addresses.
     *
     * On the interface, and non-null, because every route the app uses is scoped to one —
     * plants, diary, pest detection alike. It lived on the two credential-bearing kinds while
     * light mode was believed to have none; the two clients that needed it each carried their
     * own `when` returning null for light mode, and both were wrong in the same way. Here the
     * compiler is what keeps a new kind from repeating it.
     */
    val tenantSlug: String

    /** Paired by QR code; backed by a rotating refresh token (R8, R21, R22). */
    data class QrPairing(
        override val baseUrl: String,
        override val tenantSlug: String,
        /** The signed-in identity where the instance reports one, for display only (R26). */
        val identity: String? = null,
        override val belowVersionFloor: Boolean = false,
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
        override val tenantSlug: String,
        /** Masked remainder of the key — the most the UI may show of a secret (R19). */
        val keyHint: String,
        /**
         * The signed-in identity, where the instance reports one (R26). A service-account
         * key usually has no user profile behind it, so this is more often null than not —
         * absent is normal here, not a failure.
         */
        val identity: String? = null,
        override val belowVersionFloor: Boolean = false,
    ) : Connection {
        override val method: ConnectionMethod = ConnectionMethod.API_KEY
    }

    /**
     * Connected to a light-mode instance: no credential, but a tenant like any other kind.
     *
     * The slug is non-null for the same reason it is on the other two: everything this app
     * does with an instance is tenant-scoped, so a connection that cannot name one is not a
     * usable connection, and storing it as absent would only move the failure to every screen
     * that later tries to read something.
     */
    data class LightMode(
        override val baseUrl: String,
        override val tenantSlug: String,
        override val belowVersionFloor: Boolean = false,
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
