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
        // No accounts, so no identity, no tenants and no secret to hold (R10).
        return ConnectionResult.Verified(
            identity = null,
            tenants = emptyList(),
            credential = Credential.None,
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
                            "failed (${failure.diagnostic()}). The code is now spent — " +
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

        // R15 wants the tenant taken from the key's own `tenant_scope`, and
        // `POST /auth/service-accounts/validate` is the only route that reports it. It is
        // not always reachable: the backend gates it behind the instance's MCP flag and
        // answers 404 when MCP is off, so relying on it alone would report a perfectly valid
        // key as rejected on any instance that does not run MCP. Hence the fallback — the
        // requirement is met wherever the route exists, and the connection still works where
        // it does not.
        val scoped = runCatchingCancellable { retrofit.tenantsFromKeyScope(request.key) }
            .getOrElse { failure ->
                if (failure is HttpFailure && failure.status == HTTP_NOT_FOUND) null else throw failure
            }

        return ConnectionResult.Verified(
            // A service-account key has no user profile, so an unreadable identity is not a
            // failed connection — the instance simply has nothing to volunteer.
            identity = runCatchingCancellable { retrofit.identityOrNull() }.getOrNull(),
            tenants = scoped ?: retrofit.tenants(),
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
            return body() ?: throw ConnectionFailure("${call.description} returned an empty body")
        }

        fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun Throwable.asReason(baseUrl: String): String = when (this) {
            is ConnectionFailure -> message.orEmpty()
            is HttpFailure -> reason(baseUrl)
            else -> "could not reach $baseUrl: ${diagnostic()}"
        }

        /**
         * The statuses come from the endpoints' own descriptions in the vendored schema, and
         * they matter because the obvious reading of each is wrong: a locked-out address or a
         * rate limit is not an expired code, and an instance answering at all is not a wrong
         * address.
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
            Call.VALIDATE_KEY, Call.LIST_TENANTS -> message.orEmpty()
        }

        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_LOCKED = 423
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * Deliberately the exception's type and message rather than the whole throwable: a
         * stack trace of a failed connection attempt can carry the request that produced it.
         */
        fun Throwable.diagnostic(): String = "${this::class.simpleName}: ${message.orEmpty()}"

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
