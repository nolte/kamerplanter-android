package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.network.generated.apis.AuthApi
import io.github.nolte.kamerplanter.core.network.generated.models.RefreshRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Renews an expired access token (R21, R22).
 *
 * Access tokens last fifteen minutes, so without this the app stops working a quarter of an
 * hour after pairing and every screen reports a refused credential.
 *
 * Deliberately builds its own Retrofit from the bare [OkHttpClient] rather than going
 * through [InstanceApiFactory]: the refresh call must carry no `Authorization` header — the
 * token it would carry is the expired one — and must not itself be subject to the
 * authenticator that calls it, which would recurse on the first failure.
 */
@Singleton
class SessionRefresher(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val credentials: CredentialStore,
    private val connections: ConnectionStore,
    private val clock: () -> Long,
) {

    /** Dagger does not read Kotlin default arguments; see `NetworkConnectionClient`. */
    @Inject
    constructor(
        httpClient: OkHttpClient,
        json: Json,
        credentials: CredentialStore,
        connections: ConnectionStore,
    ) : this(httpClient, json, credentials, connections, System::currentTimeMillis)

    /**
     * Serializes refreshes. Several requests routinely fail with 401 at the same moment —
     * the plant list alone fires a handful in parallel — and without this each would spend
     * the same refresh token. Rotation is single-use, so the first would succeed and the
     * rest would invalidate the session they were trying to save.
     */
    private val mutex = Mutex()

    /** What a refresh attempt can end in. */
    sealed interface Outcome {

        /** Use this session and retry the call. */
        data class Renewed(val session: Credential.Session) : Outcome

        /** The instance refused the refresh token; credential and connection are gone (R23). */
        data object Abandoned : Outcome

        /**
         * Nothing could be decided — the instance was unreachable, or answered with
         * something that says nothing about the token. The stored state is untouched.
         */
        data object Undecided : Outcome
    }

    /**
     * Renews the session that issued [staleAccessToken], for the instance [requestUrl]
     * addresses.
     *
     * [requestUrl] is checked against the stored connection rather than trusted. This is a
     * singleton attached to every session-bearing client — including the one the pairing
     * flow builds for an instance the user has only just typed in. Without the check, a 401
     * from that new address would be answered with the token issued by the old one: a
     * credential for host A sent to host B.
     */
    suspend fun refresh(staleAccessToken: String, requestUrl: HttpUrl): Outcome = mutex.withLock {
        val current = loadCredential()
        if (current !is Credential.Session) return Outcome.Undecided

        val connection = loadConnection() ?: return Outcome.Undecided
        val stored = connection.baseUrl.toHttpUrlOrNull() ?: return Outcome.Undecided
        if (!stored.sameInstanceAs(requestUrl)) return Outcome.Undecided

        // Someone else already refreshed while this call waited for the lock. Handing back
        // the fresh session lets the caller retry immediately instead of spending a refresh
        // token that has just been rotated away.
        if (current.accessToken != staleAccessToken) return Outcome.Renewed(current)

        val response = runCatchingCancellable {
            refreshApi(connection.baseUrl).refreshApiV1AuthRefreshPost(
                RefreshRequest(refreshToken = current.refreshToken),
            )
        }.getOrNull()
            // No answer at all: a dropped network, a timeout, an instance mid-restart. None
            // of that says the refresh token is dead, and clearing the connection over it
            // would send the user to re-pair against a server that is merely busy.
            ?: return Outcome.Undecided

        val body = response.takeIf { it.isSuccessful }?.body()
            // Only the instance refusing the token itself proves it is spent. A 502 from a
            // proxy in front of a restarting instance proves nothing.
            ?: return if (response.code() in TOKEN_REFUSED) abandon() else Outcome.Undecided

        val session = Credential.Session(
            accessToken = body.accessToken,
            // R22: the response carries a rotated refresh token and the previous one is
            // invalidated across both transports. Keeping the old one would work exactly
            // once more — never.
            refreshToken = body.refreshToken,
            accessTokenExpiresAtEpochMillis = clock() + body.expiresIn * MILLIS_PER_SECOND,
        )
        // The server has rotated by now, so the refresh token still in the store is dead. A
        // failure to persist the new one leaves a pair that cannot recover; clearing is the
        // honest end, and saves the user a round trip that can only fail.
        runCatchingCancellable { credentials.save(session) }.getOrElse { return abandon() }
        Outcome.Renewed(session)
    }

    /**
     * R23: a refresh the instance refused drops the app to the disconnected state and clears
     * the stored credential. Both halves go, because a connection whose secret is gone
     * cannot call anything, and leaving it behind would show a connected-looking Settings
     * screen that fails on every request.
     *
     * [NonCancellable] because this runs on the way out of a failed call, and a cancelled
     * erase is what would strand a credential the user believes is gone.
     */
    private suspend fun abandon(): Outcome {
        withContext(NonCancellable) {
            runCatchingCancellable { credentials.clear() }
            runCatchingCancellable { connections.clear() }
        }
        return Outcome.Abandoned
    }

    /**
     * Both stores can throw — a truncated DataStore file, a Keystore key the system
     * invalidated. This runs under `runBlocking` inside an OkHttp authenticator, where an
     * escaping RuntimeException is not a failed request but an uncaught exception on a
     * dispatcher thread: a crash rather than a 401.
     */
    private suspend fun loadCredential(): Credential? =
        runCatchingCancellable { credentials.load() }.getOrNull()

    private suspend fun loadConnection() =
        runCatchingCancellable { connections.connection.first() }.getOrNull()

    /**
     * The refresh call needs a dispatcher of its own, and this is not a tuning detail —
     * without it the app deadlocks.
     *
     * OkHttp allows five concurrent requests per host. The authenticator blocks its call
     * thread while refreshing, so once five calls to the same instance fail together — which
     * the plant list does routinely, firing photo requests in parallel — all five slots are
     * held by threads waiting for a refresh that cannot start, because it needs a sixth slot
     * on that same host. Nothing times out and nothing recovers.
     *
     * A separate dispatcher takes the refresh out of that queue. The connection pool is
     * inherited, so the socket is still reused.
     */
    private val refreshClient: OkHttpClient by lazy {
        httpClient.newBuilder().dispatcher(Dispatcher()).build()
    }

    private fun refreshApi(baseUrl: String): AuthApi = Retrofit.Builder()
        .baseUrl(baseUrl.withTrailingSlash())
        .client(refreshClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AuthApi::class.java)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * The status that is about *this token*, rather than about the server right now.
         *
         * 401 only. The schema declares 200, 401, 404, 409 and 422 for this route and no
         * 403 — so a 403 here comes from something in front of the instance (a WAF, a
         * corporate proxy), which is exactly the class of answer that must not delete a
         * working credential.
         */
        val TOKEN_REFUSED = setOf(401)

        /** Same instance, whatever path the failing endpoint happened to sit under. */
        fun HttpUrl.sameInstanceAs(other: HttpUrl): Boolean =
            scheme == other.scheme && host == other.host && port == other.port

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
