package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.ConnectionClient
import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.ConnectionResult
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.Tenant
import io.github.nolte.kamerplanter.core.network.generated.apis.AuthApi
import io.github.nolte.kamerplanter.core.network.generated.apis.HealthApi
import io.github.nolte.kamerplanter.core.network.generated.apis.TenantsApi
import io.github.nolte.kamerplanter.core.network.generated.apis.UsersApi
import io.github.nolte.kamerplanter.core.network.generated.models.DevicePairingRedeemRequest
import io.github.nolte.kamerplanter.core.network.generated.models.RefreshRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ServiceAccountValidateRequest
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The real [ConnectionClient], replacing the debug fake and the release placeholder that
 * stood in for it (R34). This is the first build in which a release APK can connect.
 *
 * Every path starts at `GET /api/health`, the only route that answers before any credential
 * exists (R-HEALTH-1). It settles three things at once: whether the address is a
 * kamerplanter instance, whether it reports itself healthy, and whether it runs in `light`
 * mode — a light instance has no accounts and answers `/api/v1/auth/…` with 404, so pairing
 * against one would fail deeper in with a far less obvious error (R10, R11).
 *
 * Nothing here decides *policy*: tenant adoption, what gets stored and what a failure means
 * for an existing connection all stay in `SettingsViewModel` (R13, R14, R15). This reports
 * what the instance said, and says which call said it.
 */
@Singleton
class NetworkConnectionClient(
    private val apis: InstanceApiFactory,
    private val clock: () -> Long,
) : ConnectionClient {

    /**
     * A second constructor rather than a default parameter: Dagger does not read Kotlin
     * default arguments, so `clock: () -> Long = …` on the injected constructor makes it
     * demand a binding for `Function0<Long>` that nobody provides.
     */
    @Inject
    constructor(apis: InstanceApiFactory) : this(apis, System::currentTimeMillis)

    override suspend fun connect(request: ConnectionRequest): ConnectionResult =
        runCatchingCancellable {
            // One probe for every path, and the compatibility gate right behind it: an
            // instance on a foreign API major is refused before any credential is spent on
            // it (F-10) — a pairing code redeemed against an instance the app cannot talk
            // to would be a code destroyed for nothing.
            val health = probeHealth(request.baseUrl)
            health.incompatibility() ?: when (request) {
                is ConnectionRequest.LightMode -> connectLightMode(request, health)
                is ConnectionRequest.QrPairing -> connectQrPairing(request, health)
                is ConnectionRequest.ApiKey -> connectApiKey(request, health)
            }
        }.getOrElse { failure ->
            // A diagnostic, never a UI string — and never one that could echo the secret it
            // failed on, which is why nothing here interpolates the request itself.
            //
            // An IOException is the instance not answering; anything else is it answering
            // something the app could not use. Only the first can be a missing local-network
            // grant, and only this layer knows which happened.
            ConnectionResult.Failure(
                reason = failure.asReason(request.baseUrl),
                // A failed TLS handshake is an IOException, but "unreachable" it is not:
                // the instance answered, and its certificate was the problem. Reporting it
                // as silence would surface the local-network hint — advice about precisely
                // the wrong thing (F-10).
                unreachable = failure is IOException && !failure.isCertificateFailure(),
            )
        }

    private suspend fun connectLightMode(
        request: ConnectionRequest.LightMode,
        health: HealthReport,
    ): ConnectionResult {
        if (health.mode != LIGHT_MODE) {
            return ConnectionResult.Failure(
                "instance is not in light mode (reports '${health.mode}'); it needs a credential",
            )
        }
        // No accounts, so no identity and no secret to hold — but tenants all the same. The
        // list is served unauthenticated here, and it has to be read: every route this app
        // uses afterwards is scoped to a slug, so returning none left the app connected to an
        // instance it could ask nothing about.
        //
        // Its failures are described here rather than by the shared reason table, which was
        // written for the two methods that carry a credential: a 401 there reads "the instance
        // refused this credential", a sentence about something light mode never sent.
        return runCatchingCancellable { apis.create(request.baseUrl).tenants() }
            .fold(
                onSuccess = {
                    ConnectionResult.Verified(
                        identity = null,
                        tenants = it,
                        credential = Credential.None,
                        belowVersionFloor = health.belowVersionFloor,
                    )
                },
                onFailure = { ConnectionResult.Failure(it.lightModeTenantsReason()) },
            )
    }

    private suspend fun connectQrPairing(
        request: ConnectionRequest.QrPairing,
        health: HealthReport,
    ): ConnectionResult {
        if (health.mode == LIGHT_MODE) return lightModeRejects("pairing")

        // Unauthenticated: redeeming the code is what produces the credential.
        val redeemed = apis.create(request.baseUrl)
            .create(AuthApi::class.java)
            .redeemDevicePairingApiV1AuthDevicePairingRedeemPost(
                DevicePairingRedeemRequest(code = request.code, deviceName = DEVICE_NAME),
            )
            .bodyOrThrow(Call.REDEEM_PAIRING)

        val session = Credential.Session(
            accessToken = redeemed.accessToken,
            refreshToken = redeemed.refreshToken,
            accessTokenExpiresAtEpochMillis = clock() + redeemed.expiresIn * MILLIS_PER_SECOND,
        )

        // Past this point the code is spent: it is single-use with a 60–120 second life
        // (R7, R43). A failure here cannot be retried by tapping again — the user has to
        // generate a new code on the server — so the reason has to say so rather than read
        // like the code was wrong.
        return runCatchingCancellable { describe(request.baseUrl, session) }
            .fold(
                onSuccess = { (identity, tenants) ->
                    ConnectionResult.Verified(identity, tenants, session, health.belowVersionFloor)
                },
                onFailure = { failure ->
                    ConnectionResult.Failure(
                        "the pairing code was accepted, but reading the account behind it " +
                            "failed (${failure.shortCause()}). The code is now spent — " +
                            "generate a new one and scan again.",
                    )
                },
            )
    }

    private suspend fun connectApiKey(
        request: ConnectionRequest.ApiKey,
        health: HealthReport,
    ): ConnectionResult {
        if (health.mode == LIGHT_MODE) return lightModeRejects("an API key")

        val credential = Credential.ApiKey(request.key)
        val retrofit = apis.create(request.baseUrl) { credential }

        // Order matters, and both calls earn their place.
        //
        // Listing tenants comes first because it is the only *authenticated* call on this
        // path, and R13 will not let a connection be established without one succeeding. The
        // scope route cannot serve that purpose: the key travels in its JSON body, it carries
        // `security: []`, and it answers the same generic 401 for an invalid key as for a
        // valid non-service one — so a success there says nothing about whether the key
        // authenticates anything.
        //
        // The scope route runs second, for R15: `tenant_scope` from the key itself is what
        // the requirement asks for, and this is the only route that reports it. The backend
        // gates it behind the instance's MCP flag and answers 404 when MCP is off, so a
        // missing route falls back to the listing already in hand — the requirement holds
        // wherever it exists, and a valid key still connects where it does not.
        val reachable = retrofit.tenants()
        // Any failure falls back, not just 404. Once the listing above has succeeded the key
        // has authenticated, so this route is no longer a verdict on it — it is one source of
        // tenants and the listing is the other. Letting a 500, a rate limit or the masked 401
        // it returns for a valid non-service key sink the whole connection would throw away a
        // credential that has just demonstrably worked.
        val scoped = runCatchingCancellable { retrofit.tenantsFromKeyScope(request.key) }.getOrNull()

        return ConnectionResult.Verified(
            // A service-account key has no user profile, so an unreadable identity is not a
            // failed connection — the instance simply has nothing to volunteer.
            identity = runCatchingCancellable { retrofit.identityOrNull() }.getOrNull(),
            // `scoped` wins whenever the route answered, empty list included: a key whose
            // scope is genuinely empty grants nothing, and substituting the wider listing
            // there would hand it tenants it was never scoped to.
            tenants = scoped ?: reachable,
            credential = credential,
            belowVersionFloor = health.belowVersionFloor,
        )
    }

    /** The tenants the key itself is scoped to (R15), or `null` where MCP is switched off. */
    private suspend fun Retrofit.tenantsFromKeyScope(key: String): List<Tenant> =
        create(AuthApi::class.java)
            .validateServiceAccountApiV1AuthServiceAccountsValidatePost(
                ServiceAccountValidateRequest(apiKey = key),
            )
            .bodyOrThrow(Call.VALIDATE_KEY)
            .tenants
            .map { Tenant(slug = it.tenantSlug, displayName = it.tenantSlug) }

    /**
     * What `/api/health` reported. Throws when the instance did not answer the route, and
     * also when it answered but called itself something other than healthy (R-HEALTH-3).
     */
    private suspend fun probeHealth(baseUrl: String): HealthReport {
        val payload = apis.create(baseUrl)
            .create(HealthApi::class.java)
            .rootHealthApiHealthGet()
            .bodyOrThrow(Call.HEALTH_PROBE)

        // The route has no response schema upstream, so it generates as a free-form map and
        // its fields are read by hand.
        val status = payload["status"]?.stringOrNull()
        val version = payload["version"]?.stringOrNull()
        // A missing `mode` means a full instance: light mode is the deviation the backend
        // labels, not the default.
        val mode = payload["mode"]?.stringOrNull() ?: FULL_MODE

        if (status != null && status != HEALTHY_STATUS) {
            throw ConnectionFailure(
                "the instance reports itself as '$status'" +
                    (version?.let { " (version $it)" } ?: "") + "; try again once it recovers",
            )
        }
        return HealthReport(mode = mode, version = version)
    }

    private data class HealthReport(val mode: String, val version: String?)

    /**
     * The one compatibility verdict that refuses a connection: no API major in common
     * (F-10). Everything this app calls lives under `/api/v1`, so an instance whose
     * `apiVersion` reports another major does not offer the surface the app would address —
     * connecting anyway just moves the failure onto the first real call.
     */
    private fun HealthReport.incompatibility(): ConnectionResult.Failure? =
        when (val verdict = ApiCompatibility.judge(version)) {
            is ApiCompatibility.Verdict.NoSharedMajor -> ConnectionResult.Failure(
                "the instance reports API version ${verdict.reported}, and this app speaks " +
                    "only major version ${ApiCompatibility.SUPPORTED_API_MAJOR} — there is " +
                    "no version in common. Update whichever of the two is older.",
            )
            else -> null
        }

    /** Same major but older than the floor: connect, and let the connection say so (F-10). */
    private val HealthReport.belowVersionFloor: Boolean
        get() = ApiCompatibility.judge(version) == ApiCompatibility.Verdict.BelowFloor

    /**
     * Ends the paired session on the instance via its own session-delete route (F-8).
     *
     * The route wants the session's document key, which the redeem response never carried —
     * so the session list is read first and the entry the instance marks `is_current` is the
     * one this very call authenticated with. No current entry means the instance no longer
     * holds the session; there is nothing left to end and nothing to report.
     */
    override suspend fun endSession(baseUrl: String, credential: Credential) {
        if (credential !is Credential.Session) return
        val usable = credential.refreshedIfExpired(baseUrl)
        val users = apis.create(baseUrl) { usable }.create(UsersApi::class.java)
        val sessions = users.listSessionsApiV1UsersMeSessionsGet().bodyOrThrow(Call.LIST_SESSIONS)
        val current = sessions.firstOrNull { it.isCurrent } ?: return
        val revoked = users.revokeSessionApiV1UsersMeSessionsSessionKeyDelete(current.key)
        if (!revoked.isSuccessful) throw HttpFailure(revoked.code(), Call.END_SESSION)
    }

    /**
     * Renews the in-hand session when its access token has already lapsed.
     *
     * The common disconnect happens more than fifteen minutes after the last call — the
     * token's whole lifetime — and by the time this runs the device stores are deliberately
     * empty (F-8: local first). The shared authenticator cannot help with that 401: it renews
     * from the *store*, finds nothing, and gives up — which made server-side revocation fail
     * on exactly the ordinary path. So the token is renewed here, from the credential still
     * in hand, before anything is asked of the session routes.
     *
     * The rotated pair is deliberately not persisted: the very next call deletes the session
     * it belongs to, and nothing on the device is supposed to hold a credential any more.
     * Like the redeem call, the refresh travels unauthenticated — the refresh token in the
     * body is the proof.
     */
    private suspend fun Credential.Session.refreshedIfExpired(baseUrl: String): Credential.Session {
        if (clock() < accessTokenExpiresAtEpochMillis) return this
        val renewed = apis.create(baseUrl)
            .create(AuthApi::class.java)
            .refreshApiV1AuthRefreshPost(RefreshRequest(refreshToken = refreshToken))
            .bodyOrThrow(Call.REFRESH_SESSION)
        return Credential.Session(
            accessToken = renewed.accessToken,
            refreshToken = renewed.refreshToken,
            accessTokenExpiresAtEpochMillis = clock() + renewed.expiresIn * MILLIS_PER_SECOND,
        )
    }

    /** Identity and tenants, both read with the credential just established. */
    private suspend fun describe(
        baseUrl: String,
        credential: Credential,
    ): Pair<String?, List<Tenant>> {
        val retrofit = apis.create(baseUrl) { credential }
        return retrofit.identityOrNull() to retrofit.tenants()
    }

    private suspend fun Retrofit.identityOrNull(): String? =
        create(UsersApi::class.java)
            .getProfileApiV1UsersMeGet()
            .let { if (it.isSuccessful) it.body()?.email else null }

    private suspend fun Retrofit.tenants(): List<Tenant> =
        create(TenantsApi::class.java)
            .listMyTenantsApiV1TenantsGet()
            .bodyOrThrow(Call.LIST_TENANTS)
            .map { Tenant(slug = it.slug, displayName = it.name) }

    /** Carries a reason that is already user-appropriate, so [asReason] passes it through. */
    private class ConnectionFailure(message: String) : Exception(message)

    /**
     * Which request failed, so [asReason] can dispatch on the call rather than on a substring
     * of its description. An earlier version matched `what.startsWith("redeeming")`, which
     * tied the diagnostics to prose nobody would think to keep in step when editing it.
     */
    private enum class Call(val description: String) {
        HEALTH_PROBE("probing the instance"),
        REDEEM_PAIRING("redeeming the pairing code"),
        VALIDATE_KEY("reading the key's tenant scope"),
        LIST_TENANTS("listing the tenants this credential may address"),
        REFRESH_SESSION("renewing the session before ending it"),
        LIST_SESSIONS("listing this account's sessions"),
        END_SESSION("ending the session on the instance"),
    }

    /** An HTTP status the caller did not expect, kept so the reason can name it. */
    private class HttpFailure(
        val status: Int,
        val call: Call,
        /** Whatever the instance sent as `Retry-After`, in seconds — where it sent one. */
        val retryAfterSeconds: Long? = null,
    ) : Exception("${call.description} failed with HTTP $status") {

        /**
         * How long the lockout lasts, where the instance said so (F-6). "Wait before trying
         * again" without a duration is a message, not an instruction — but when no
         * `Retry-After` arrived, no number may be invented for it either.
         */
        fun waitAdvice(): String {
            val seconds = retryAfterSeconds ?: return "; wait before trying again"
            val wait = if (seconds >= 2 * SECONDS_PER_MINUTE) {
                "about ${(seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE} minutes"
            } else {
                "$seconds seconds"
            }
            return "; try again in $wait"
        }
    }

    private companion object {
        const val LIGHT_MODE = "light"
        const val FULL_MODE = "full"
        const val HEALTHY_STATUS = "healthy"
        const val MILLIS_PER_SECOND = 1_000L

        /** Shown in the instance's session list, so the user can tell their devices apart. */
        const val DEVICE_NAME = "kamerplanter for Android"

        fun lightModeRejects(what: String) = ConnectionResult.Failure(
            "this instance runs in light mode and has no accounts, so $what cannot be used",
        )

        /**
         * Keeps the status code. Collapsing every non-2xx to one message makes the app
         * report a 503 from a starting instance, or a 502 from a proxy in front of a healthy
         * one, as "no instance answered here" — sending the user to check an address that is
         * perfectly correct.
         */
        fun <T> Response<T>.bodyOrThrow(call: Call): T {
            // The delta-seconds form only. The header's other legal form is an HTTP date,
            // which no route of this backend uses — and a guessed parse of one would put a
            // wrong number into a sentence whose entire value is being right.
            if (!isSuccessful) {
                throw HttpFailure(code(), call, headers()["Retry-After"]?.trim()?.toLongOrNull())
            }
            return body() ?: throw ConnectionFailure(
                "the instance answered ${call.description} with an empty body",
            )
        }

        fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun Throwable.asReason(baseUrl: String): String = when {
            // Before the generic transport case: a handshake failure is an IOException, and
            // the generic sentence would send the user to check an address that is fine and
            // an app that is working. The certificate is the cause, and the sentence has to
            // say so (F-10).
            isCertificateFailure() ->
                "the TLS certificate of $baseUrl could not be validated — the certificate " +
                    "is the problem (expired, self-signed, or issued for another name), " +
                    "not the address or this app. Renew or correctly install it on the " +
                    "instance, then try again."
            this is ConnectionFailure -> message.orEmpty()
            this is HttpFailure -> reason(baseUrl)
            else -> "could not reach $baseUrl: ${diagnostic()}"
        }

        /**
         * These matter because the obvious reading of each is wrong: a locked-out address is
         * not an expired code, and an instance answering at all is not a wrong address.
         *
         * 401, 422 and 423 come from the redeem endpoint's own description in the vendored
         * schema. 429 does not — the document never mentions it — but a pairing endpoint the
         * backend already rate-limits per source address is the obvious place for one, and
         * mapping it costs nothing if it never arrives.
         */
        fun HttpFailure.reason(baseUrl: String): String = when (call) {
            Call.REDEEM_PAIRING -> when (status) {
                HTTP_UNAUTHORIZED ->
                    "the instance rejected the pairing code — it expires within two minutes " +
                        "and can be redeemed only once"
                HTTP_LOCKED ->
                    "the instance has temporarily locked this device out after too many " +
                        "attempts${waitAdvice()}"
                HTTP_TOO_MANY_REQUESTS ->
                    "too many pairing attempts in a short time${waitAdvice()}"
                HTTP_UNPROCESSABLE ->
                    "the instance could not process this pairing code"
                else -> message.orEmpty()
            }
            Call.HEALTH_PROBE ->
                "$baseUrl answered HTTP $status instead of a health report — it may be a " +
                    "kamerplanter instance that is still starting, or not one at all"
            // Only the statuses that are actually about the credential may say so: a 503
            // from an instance that is still starting says nothing about it, and reporting
            // that as a refusal sends the user to replace a key that works.
            Call.LIST_TENANTS -> when (status) {
                HTTP_UNAUTHORIZED -> "the instance refused this credential"
                HTTP_FORBIDDEN ->
                    "this credential is accepted but not allowed to read the tenant list"
                else -> "the instance answered HTTP $status while ${call.description}"
            }
            // Best-effort routes: the disconnect that calls them tolerates any failure, so
            // these reasons only ever reach a log. The message already names call and
            // status, which is all a diagnostic needs.
            Call.REFRESH_SESSION, Call.LIST_SESSIONS, Call.END_SESSION -> message.orEmpty()
            // Unreachable: the only caller swallows every failure from this route, because
            // by then the credential has already authenticated elsewhere. Kept for
            // exhaustiveness and deliberately generic — the sentences above would both be
            // wrong here, where a 401 can also mean a valid key that is simply not a service
            // account.
            Call.VALIDATE_KEY -> message.orEmpty()
        }

        const val SECONDS_PER_MINUTE = 60L

        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_LOCKED = 423
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_NOT_FOUND = 404

        /**
         * Why a light-mode instance's tenant list could not be read.
         *
         * A 404 is worth naming: this route is where an instance older than the app's
         * expectations gives itself away, and "not found" alone would send the user looking at
         * their network for a problem that is not there.
         */
        fun Throwable.lightModeTenantsReason(): String = when {
            this is HttpFailure && status == HTTP_NOT_FOUND ->
                "this instance offers no tenant list — it may be older than this app expects"
            this is HttpFailure -> "the instance answered HTTP $status when asked for its tenants"
            // Not `shortCause()`: for an empty body it falls through to the shared sentence
            // about "the tenants this credential may address" — the very phrasing this
            // function exists to keep off a path that sends no credential.
            else -> "could not read the instance's tenants (${this::class.simpleName})"
        }

        /**
         * The type, plus the message only where the message describes the transport.
         *
         * This string is shown to the user, so it may not echo what the failure failed on: a
         * `JsonDecodingException` quotes the offending payload in its message, and a redeem
         * response quoted back would put a session token on screen (R19). An [IOException]
         * describes the connection instead — an address, a port, a timeout — and that is the
         * most useful sentence this app produces: "failed to connect to …:443 from … after
         * 10000ms" is what turned an unreachable instance from a guess into a measurement.
         * Dropping every message to be safe would cost exactly the one worth keeping.
         *
         * Never the whole throwable: a stack trace of a failed attempt can carry the request
         * that produced it.
         */
        fun Throwable.diagnostic(): String = when (this) {
            is IOException -> "${this::class.simpleName}: ${message.orEmpty()}"
            else -> this::class.simpleName.orEmpty()
        }

        /**
         * The most useful short form of a cause that is safe to show. A status where there
         * is one, the prepared sentence where the failure already carries one, and otherwise
         * the exception type alone.
         *
         * The type alone, even though an unexpected throwable usually has a message: a
         * `JsonDecodingException` quotes the offending payload in its message, and R19 does
         * not allow a failure reason to echo what it failed on.
         */
        fun Throwable.shortCause(): String = when (this) {
            is HttpFailure -> "HTTP $status"
            is ConnectionFailure -> message.orEmpty()
            else -> this::class.simpleName.orEmpty()
        }

        /**
         * [runCatching], minus the throwable it must never swallow. Catching
         * [CancellationException] would turn "the user navigated away" into an ordinary
         * connection failure and report it as one.
         */
        @Suppress("TooGenericExceptionCaught")
        inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
    }
}

/**
 * A TLS handshake the client would not complete. [SSLHandshakeException] is the validation
 * path (untrusted chain, expired leaf); [SSLPeerUnverifiedException] is hostname verification
 * refusing a certificate issued for another name.
 *
 * Judged over the whole tree — causes and suppressed exceptions — not the top-level type:
 *
 * - OkHttp races the addresses a host resolves to and reports whichever attempt failed
 *   *first*, filing the others as suppressed. A refused connection on an unrouted IPv6
 *   address fails faster than a handshake that has to build a certificate path, so on
 *   exactly the networks where the certificate is the only problem it is never the top-level
 *   failure.
 * - A coroutine resuming with that exception may hand back a *copy* of it (stack-trace
 *   recovery), which carries the original as its cause and none of its suppressed
 *   exceptions.
 */
internal fun Throwable.isCertificateFailure(): Boolean =
    this is SSLHandshakeException ||
        this is SSLPeerUnverifiedException ||
        cause?.isCertificateFailure() == true ||
        suppressed.any { it.isCertificateFailure() }
