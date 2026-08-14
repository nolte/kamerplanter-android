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
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * The real [ConnectionClient], replacing the debug fake and the release placeholder that
 * stood in for it (R34). This is the first build in which a release APK can connect.
 *
 * Every path starts at `GET /api/health`, which is the only route that answers before any
 * credential exists (R-HEALTH-1). It settles two questions at once: whether the address is a
 * kamerplanter instance at all, and whether it runs in `light` mode — a light instance has no
 * accounts and answers `/api/v1/auth/…` with 404, so pairing against one would fail deeper in
 * with a far less obvious error (R10, R11).
 *
 * Nothing here decides *policy*: tenant adoption, what gets stored and what a failure means
 * for an existing connection all stay in `SettingsViewModel` (R13, R14, R15). This reports
 * what the instance said and nothing more.
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
            // The message is a diagnostic, never a UI string — and never one that could echo
            // the secret it failed on, which is why the request's masking toString is used.
            ConnectionResult.Failure(
                "could not reach ${request.baseUrl}: ${failure.diagnostic()}",
            )
        }

    private suspend fun connectLightMode(request: ConnectionRequest.LightMode): ConnectionResult {
        val mode = probeMode(request.baseUrl) ?: return unreachable()
        if (mode != LIGHT_MODE) {
            return ConnectionResult.Failure(
                "instance is not in light mode (reports '$mode'); it needs a credential",
            )
        }
        // No accounts, so no identity, no tenants and no secret to hold (R10).
        return ConnectionResult.Verified(identity = null, tenants = emptyList(), credential = Credential.None)
    }

    private suspend fun connectQrPairing(request: ConnectionRequest.QrPairing): ConnectionResult {
        val mode = probeMode(request.baseUrl) ?: return unreachable()
        if (mode == LIGHT_MODE) return lightModeRejects("pairing")

        // Unauthenticated: redeeming the code is what produces the credential.
        val anonymous = apis.create(request.baseUrl)
        val redeemed = anonymous.create(AuthApi::class.java)
            .redeemDevicePairingApiV1AuthDevicePairingRedeemPost(
                DevicePairingRedeemRequest(code = request.code, deviceName = DEVICE_NAME),
            )
            .bodyOrNull()
            ?: return ConnectionResult.Failure(
                "the instance rejected the pairing code (it expires within two minutes and " +
                    "can be redeemed only once)",
            )

        val session = Credential.Session(
            accessToken = redeemed.accessToken,
            refreshToken = redeemed.refreshToken,
            accessTokenExpiresAtEpochMillis = clock() + redeemed.expiresIn * MILLIS_PER_SECOND,
        )
        val authenticated = apis.create(request.baseUrl) { session }
        return ConnectionResult.Verified(
            identity = authenticated.identityOrNull(),
            tenants = authenticated.tenants(),
            credential = session,
        )
    }

    private suspend fun connectApiKey(request: ConnectionRequest.ApiKey): ConnectionResult {
        val mode = probeMode(request.baseUrl) ?: return unreachable()
        if (mode == LIGHT_MODE) return lightModeRejects("an API key")

        // Validation both proves the key and reports its tenant scope, so no second call is
        // needed to learn which tenants it may address (R9, R15).
        val validated = apis.create(request.baseUrl)
            .create(AuthApi::class.java)
            .validateServiceAccountApiV1AuthServiceAccountsValidatePost(
                ServiceAccountValidateRequest(apiKey = request.key),
            )
            .bodyOrNull()
            ?: return ConnectionResult.Failure("the instance rejected the API key")

        return ConnectionResult.Verified(
            identity = validated.displayName,
            tenants = validated.tenants.map { Tenant(slug = it.tenantSlug, displayName = it.tenantSlug) },
            credential = Credential.ApiKey(request.key),
        )
    }

    /** `null` when the instance did not answer the health route at all. */
    private suspend fun probeMode(baseUrl: String): String? {
        val health = apis.create(baseUrl)
            .create(HealthApi::class.java)
            .rootHealthApiHealthGet()
            .bodyOrNull()
            ?: return null
        // The route has no response schema upstream, so it generates as a free-form map and
        // its fields are read by hand. A missing `mode` means a full instance: light mode is
        // the deviation the backend labels, not the default.
        return health["mode"]?.jsonPrimitive?.contentOrNull() ?: FULL_MODE
    }

    private suspend fun Retrofit.identityOrNull(): String? =
        create(UsersApi::class.java).getProfileApiV1UsersMeGet().bodyOrNull()?.email

    private suspend fun Retrofit.tenants(): List<Tenant> =
        create(TenantsApi::class.java)
            .listMyTenantsApiV1TenantsGet()
            .bodyOrNull()
            .orEmpty()
            .map { Tenant(slug = it.slug, displayName = it.name) }

    private companion object {
        const val LIGHT_MODE = "light"
        const val FULL_MODE = "full"
        const val MILLIS_PER_SECOND = 1_000L

        /** Shown in the instance's session list, so the user can tell their devices apart. */
        const val DEVICE_NAME = "kamerplanter for Android"

        fun unreachable() = ConnectionResult.Failure(
            "no kamerplanter instance answered at this address",
        )

        fun lightModeRejects(what: String) = ConnectionResult.Failure(
            "this instance runs in light mode and has no accounts, so $what cannot be used",
        )

        fun <T> Response<T>.bodyOrNull(): T? = if (isSuccessful) body() else null

        fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
            if (isString) content else null

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
