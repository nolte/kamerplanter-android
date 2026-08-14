package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.CredentialStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The HTTP client image loading has to use.
 *
 * Attachment URIs are tenant-scoped and authenticated, so a plain image request for a
 * thumbnail comes back 401 and the row shows a broken image. This is the same
 * [OkHttpClient] the API calls use, plus an interceptor that attaches the stored credential —
 * so a thumbnail is fetched with the same authority as the call that produced its URL.
 *
 * Exposed as a plain `OkHttpClient` rather than as a Coil type on purpose: Coil belongs to
 * whichever feature renders images, and this module has no business depending on it.
 */
@Singleton
class AuthenticatedImageClient @Inject constructor(
    httpClient: OkHttpClient,
    credentials: CredentialStore,
) {

    val client: OkHttpClient = httpClient.newBuilder()
        .addInterceptor(
            CredentialInterceptor {
                // Coil's fetchers are not suspending, so the credential is read blocking. It
                // comes from a Keystore-backed DataStore read — microseconds, off the main
                // thread, on a dispatcher Coil already owns. Caching it here instead would
                // mean a thumbnail request outliving the credential it was issued for.
                runBlocking { credentials.load() }
            },
        )
        .build()
}
