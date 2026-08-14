package io.github.nolte.kamerplanter.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renews the session when a call comes back 401, and repeats the call with the new token.
 *
 * An OkHttp [Authenticator] rather than an interceptor that checks the clock: the server
 * decides when a token is spent, and a client-side expiry check is a guess that goes wrong
 * whenever the two disagree — a phone whose clock drifts, or a token the server revoked
 * early. Answering an actual 401 needs no agreement about the time.
 *
 * Returning `null` tells OkHttp to give up and hand the 401 to the caller. That is the right
 * answer for every outcome except a renewed session: the credential was not a session, the
 * instance was unreachable, or the refresh was refused — in which case [SessionRefresher]
 * has already cleared the stored state (R23).
 */
@Singleton
class TokenRefreshAuthenticator @Inject constructor(
    private val refresher: SessionRefresher,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val stale = response.request.header(AUTHORIZATION)
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() }
            // Nothing was authenticated, so a 401 is the endpoint's answer rather than an
            // expired token — light mode, or a call made before pairing.
            ?: return null

        // One refresh per call. Counting *401s* in the chain rather than testing
        // `priorResponse != null`: OkHttp sets priorResponse for every follow-up it makes,
        // including redirects. An instance behind a proxy that rewrites a path, or FastAPI's
        // own redirect_slashes 307, would otherwise arrive here with a non-null chain and
        // never refresh at all — the exact failure this class exists to prevent, under a
        // thoroughly ordinary deployment.
        if (response.unauthorizedAttempts() > 1) return null

        // Blocking on purpose: Authenticator has no suspending form, and this already runs
        // on OkHttp's own call thread — the one that would otherwise be parked waiting for
        // the response this replaces.
        val outcome = runBlocking { refresher.refresh(stale, response.request.url) }
        val session = (outcome as? SessionRefresher.Outcome.Renewed)?.session ?: return null

        return response.request.newBuilder()
            .header(AUTHORIZATION, BEARER_PREFIX + session.accessToken)
            .build()
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "

        /** How many times this call has already come back 401, this one included. */
        fun Response.unauthorizedAttempts(): Int {
            var count = 0
            var current: Response? = this
            while (current != null) {
                if (current.code == HTTP_UNAUTHORIZED) count++
                current = current.priorResponse
            }
            return count
        }

        const val HTTP_UNAUTHORIZED = 401
    }
}
