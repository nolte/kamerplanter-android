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
class SessionRefresher @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val credentials: CredentialStore,
    private val connections: ConnectionStore,
) {

    /**
     * Serializes refreshes. Several requests routinely fail with 401 at the same moment —
     * the plant list alone fires a handful in parallel — and without this each would spend
     * the same refresh token. Rotation is single-use, so the first would succeed and the
     * rest would invalidate the session they were trying to save.
     */
    private val mutex = Mutex()

    /**
     * Renews the session that issued [staleAccessToken], or returns `null` when it could not
     * be renewed.
     *
     * `null` is terminal: per R23 the credential and the connection are already cleared by
     * the time it returns, so the caller's job is to give up rather than to retry.
     */
    suspend fun refresh(staleAccessToken: String): Credential.Session? = mutex.withLock {
        val current = credentials.load()
        if (current !is Credential.Session) return null

        // Someone else already refreshed while this call waited for the lock. Handing back
        // the fresh session lets the caller retry immediately instead of spending a refresh
        // token that has just been rotated away.
        if (current.accessToken != staleAccessToken) return current

        val baseUrl = connections.connection.first()?.baseUrl ?: return abandon()

        val renewed = runCatchingCancellable {
            refreshApi(baseUrl).refreshApiV1AuthRefreshPost(
                RefreshRequest(refreshToken = current.refreshToken),
            )
        }.getOrNull()

        val body = renewed?.takeIf { it.isSuccessful }?.body() ?: return abandon()

        val session = Credential.Session(
            accessToken = body.accessToken,
            // R22: the response carries a rotated refresh token and the previous one is
            // invalidated across both transports. Keeping the old one would work exactly
            // once more — never.
            refreshToken = body.refreshToken,
            accessTokenExpiresAtEpochMillis = System.currentTimeMillis() +
                body.expiresIn * MILLIS_PER_SECOND,
        )
        runCatchingCancellable { credentials.save(session) }.getOrElse { return abandon() }
        session
    }

    /**
     * R23: a failed refresh drops the app to the disconnected state and clears the stored
     * credential. Both halves go, because a connection whose secret is gone cannot call
     * anything, and leaving it behind would show a connected-looking Settings screen that
     * fails on every request.
     *
     * [NonCancellable] because this runs on the way out of a failed call, and a cancelled
     * erase is what would strand a credential the user believes is gone.
     */
    private suspend fun abandon(): Credential.Session? {
        withContext(NonCancellable) {
            runCatchingCancellable { credentials.clear() }
            runCatchingCancellable { connections.clear() }
        }
        return null
    }

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
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(refreshClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AuthApi::class.java)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

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
