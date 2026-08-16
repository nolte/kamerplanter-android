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
import io.github.nolte.kamerplanter.core.network.generated.models.ServiceAccountValidateRequest
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton
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
            when (request) {
                is ConnectionRequest.LightMode -> connectLightMode(request)
                is ConnectionRequest.QrPairing -> connectQrPairing(request)
                is ConnectionRequest.ApiKey -> connectApiKey(request)
            }
        }.getOrElse { failure ->
            // A diagnostic, never a UI string — and never one that could echo the secret it
            // failed on, which is why nothing here interpolates the request itself.
            ConnectionResult.Failure(failure.asReason(request.baseUrl))
        }

    private suspend fun connectLightMode(request: ConnectionRequest.LightMode): ConnectionResult {
        val health = probeHealth(request.baseUrl)
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
                    )
                },
                onFailure = { ConnectionResult.Failure(it.lightModeTenantsReason()) },
            )
    }

    private suspend fun connectQrPairing(request: ConnectionRequest.QrPairing): ConnectionResult {
        val health = probeHealth(request.baseUrl)
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
                    ConnectionResult.Verified(identity, tenants, session)
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

    private suspend fun connectApiKey(request: ConnectionRequest.ApiKey): ConnectionResult {
        val health = probeHealth(request.baseUrl)
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
    }

    /** An HTTP status the caller did not expect, kept so the reason can name it. */
    private class HttpFailure(val status: Int, val call: Call) :
        Exception("${call.description} failed with HTTP $status")

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
            if (!isSuccessful) throw HttpFailure(code(), call)
            return body() ?: throw ConnectionFailure(
                "the instance answered ${call.description} with an empty body",
            )
        }

        fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun Throwable.asReason(baseUrl: String): String = when (this) {
            is ConnectionFailure -> message.orEmpty()
            is HttpFailure -> reason(baseUrl)
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
                        "attempts; wait before trying again"
                HTTP_TOO_MANY_REQUESTS ->
                    "too many pairing attempts in a short time; wait before trying again"
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
            // Unreachable: the only caller swallows every failure from this route, because
            // by then the credential has already authenticated elsewhere. Kept for
            // exhaustiveness and deliberately generic — the sentences above would both be
            // wrong here, where a 401 can also mean a valid key that is simply not a service
            // account.
            Call.VALIDATE_KEY -> message.orEmpty()
        }

        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_LOCKED = 423
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_NOT_FOUND = 404

        /**
         * Deliberately the exception's type and message rather than the whole throwable: a
         * stack trace of a failed connection attempt can carry the request that produced it.
         */
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
            else -> "could not read the instance's tenants (${shortCause()})"
        }

        fun Throwable.diagnostic(): String = "${this::class.simpleName}: ${message.orEmpty()}"

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
