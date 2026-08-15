package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
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
    private val credentials: CredentialStore,
    private val connections: ConnectionStore,
) {

    val client: OkHttpClient = httpClient.newBuilder()
        .addInterceptor(InstanceOnlyCredentialInterceptor())
        .build()

    /**
     * Attaches the credential to thumbnail requests — but only to ones aimed at the
     * connected instance.
     *
     * The host check is the point. Thumbnail URIs come from the instance's own responses,
     * and the backend already ships a presigned-download shape; the day it answers with an
     * object-store URL for a thumbnail, an interceptor that attaches the header to whatever
     * URL it is handed would send the kamerplanter credential to a third party. An
     * unauthenticated request to that host simply works, because a presigned URL carries its
     * own authorization.
     */
    private inner class InstanceOnlyCredentialInterceptor : Interceptor {

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val token = tokenFor(request.url)
            val outgoing = token
                ?.let { request.newBuilder().header("Authorization", "Bearer $it").build() }
                ?: request
            return chain.proceed(outgoing)
        }

        /**
         * The credential to send to [target], or `null` where none may be sent there.
         *
         * Coil's fetchers do not suspend, so both reads are blocking. They are a DataStore
         * read plus a Keystore decrypt — a binder round trip, milliseconds rather than the
         * microseconds an earlier version of this comment claimed — running on the IO thread
         * Coil already owns, once per visible row. Caching instead would mean a thumbnail
         * request outliving the credential it was issued for.
         */
        private fun tokenFor(target: HttpUrl): String? {
            val instance = runBlocking { connections.currentBaseUrl() } ?: return null
            if (!target.sameInstanceAs(instance)) return null
            return when (val credential = runBlocking { credentials.load() }) {
                is Credential.Session -> credential.accessToken
                is Credential.ApiKey -> credential.key
                Credential.None -> null
            }
        }
    }

    private suspend fun ConnectionStore.currentBaseUrl(): HttpUrl? =
        runCatching { connection.first()?.baseUrl?.toHttpUrlOrNull() }.getOrNull()
}

private fun HttpUrl.sameInstanceAs(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port
