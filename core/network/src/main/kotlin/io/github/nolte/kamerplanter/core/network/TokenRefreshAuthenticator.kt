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
 * Returning `null` tells OkHttp to give up and hand the 401 to the caller, which is what
 * happens when the refresh itself fails: [SessionRefresher] has cleared the credential by
 * then (R23), and the caller's own 401 handling takes it from there.
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

        // One attempt. OkHttp chains priorResponse for every retry it has already made here,
        // so a token the server keeps rejecting cannot spin.
        if (response.priorResponse != null) return null

        // Blocking on purpose: Authenticator has no suspending form, and this already runs
        // on OkHttp's own call thread — the one that would otherwise be parked waiting for
        // the response this replaces.
        val session = runBlocking { refresher.refresh(stale) } ?: return null

        return response.request.newBuilder()
            .header(AUTHORIZATION, BEARER_PREFIX + session.accessToken)
            .build()
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
