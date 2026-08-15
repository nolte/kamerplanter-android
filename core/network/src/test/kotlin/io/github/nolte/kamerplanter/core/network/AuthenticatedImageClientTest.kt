package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.FakeConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Guards the one control standing between the stored credential and a third-party host.
 *
 * Thumbnail URIs come out of the instance's own responses, and the backend already ships a
 * presigned-download shape. The day it answers with an object-store URL for a thumbnail, an
 * image loader that attaches the header to whatever URL it is handed sends the kamerplanter
 * credential somewhere it does not belong. Nothing about that failure is visible from the
 * app, which is why it needs a test rather than a comment.
 */
class AuthenticatedImageClientTest {

    private lateinit var instance: MockWebServer
    private lateinit var elsewhere: MockWebServer

    @Before
    fun setUp() {
        instance = MockWebServer().apply { start() }
        elsewhere = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        instance.shutdown()
        elsewhere.shutdown()
    }

    private fun client(
        credential: Credential = Credential.Session("at-1", "rt-1", 0L),
        connectedTo: String = instance.url("/").toString(),
    ) = AuthenticatedImageClient(
        OkHttpClient(),
        InMemoryCredentialStore(credential),
        FakeConnectionStore(Connection.QrPairing(baseUrl = connectedTo, tenantSlug = "demo")),
    ).client

    private fun MockWebServer.answerOnce() = enqueue(MockResponse().setBody("x"))

    @Test
    fun `attaches the credential to a thumbnail on the connected instance`() {
        instance.answerOnce()

        client().newCall(Request.Builder().url(instance.url("/thumbs/a.jpg")).build())
            .execute().close()

        assertEquals("Bearer at-1", instance.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `sends nothing to a host that is not the connected instance`() {
        elsewhere.answerOnce()

        client().newCall(Request.Builder().url(elsewhere.url("/thumbs/a.jpg")).build())
            .execute().close()

        assertNull(
            "a presigned object-store URL must not receive the kamerplanter credential",
            elsewhere.takeRequest().getHeader("Authorization"),
        )
    }

    /** An API key travels the same way a session does (R9). */
    @Test
    fun `attaches an api key the same way`() {
        instance.answerOnce()

        client(credential = Credential.ApiKey("kp_sk_x"))
            .newCall(Request.Builder().url(instance.url("/thumbs/a.jpg")).build())
            .execute().close()

        assertEquals("Bearer kp_sk_x", instance.takeRequest().getHeader("Authorization"))
    }

    /** Light mode has no credential, and its thumbnails need none. */
    @Test
    fun `sends nothing when there is no credential`() {
        instance.answerOnce()

        client(credential = Credential.None)
            .newCall(Request.Builder().url(instance.url("/thumbs/a.jpg")).build())
            .execute().close()

        assertNull(instance.takeRequest().getHeader("Authorization"))
    }

    /**
     * The stored base URL carries a path prefix and a trailing slash more often than not,
     * and the thumbnail URI carries neither. Comparing them literally would refuse the
     * credential to the very instance it belongs to.
     */
    @Test
    fun `matches the instance regardless of path prefix or trailing slash`() {
        instance.answerOnce()

        client(connectedTo = instance.url("/kamerplanter/").toString())
            .newCall(Request.Builder().url(instance.url("/thumbs/a.jpg")).build())
            .execute().close()

        assertEquals("Bearer at-1", instance.takeRequest().getHeader("Authorization"))
    }
}
