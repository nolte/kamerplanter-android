package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.InstanceAddressPolicy
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Refuses any request the app's address policy does not allow.
 *
 * The policy is already applied where an instance address is read — a scanned QR code, a
 * discovery link — but those are entry points, and entry points get added. This sits on the
 * one path every request must take, so a URL that reaches the client some other way still
 * cannot carry a credential over cleartext to a routable host.
 *
 * Fails as an [IOException] rather than by rewriting the request to `https`: an instance that
 * is only reachable over `http` does not answer on `443`, and silently retargeting it would
 * turn a refusal the user can act on into a timeout they cannot explain.
 */
class CleartextGuard : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!InstanceAddressPolicy.permits(url.scheme, url.host)) {
            throw IOException(
                "Refusing ${url.scheme} to ${url.host}: plain http is allowed only for an " +
                    "instance on a private network.",
            )
        }
        return chain.proceed(chain.request())
    }
}
