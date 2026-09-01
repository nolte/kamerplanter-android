package io.github.nolte.kamerplanter.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * F-10 acceptance-3, the half a live handshake cannot pin: which throwable *counts* as a
 * certificate failure. The suppressed case is the one that matters — OkHttp reports the
 * address that failed first and files the others as suppressed, so on a host that also
 * resolves to an unrouted IPv6 address the handshake failure is never the top-level one.
 */
class CertificateFailureTest {

    @Test
    fun `a handshake failure and a hostname mismatch are certificate failures`() {
        assertTrue(SSLHandshakeException("PKIX path building failed").isCertificateFailure())
        assertTrue(SSLPeerUnverifiedException("Hostname not verified").isCertificateFailure())
    }

    @Test
    fun `a refused connection is not one`() {
        assertFalse(ConnectException("Connection refused").isCertificateFailure())
        assertFalse(IOException("unexpected end of stream").isCertificateFailure())
    }

    @Test
    fun `a refused route that hides a failed handshake behind it still names the certificate`() {
        val raced = ConnectException("Connection refused").apply {
            addSuppressed(SSLHandshakeException("PKIX path building failed"))
        }

        assertTrue(raced.isCertificateFailure())
    }

    /**
     * The shape a coroutine hands back under stack-trace recovery: a fresh copy of the raced
     * exception, with the original — and its suppressed handshake — reachable only as the
     * cause. This is the exact tree the connect path saw on CI.
     */
    @Test
    fun `a copy that only reaches the raced exception through its cause still counts`() {
        val original = ConnectException("Failed to connect to /[::1]:443").apply {
            initCause(ConnectException("Connection refused"))
            addSuppressed(SSLHandshakeException("PKIX path building failed"))
        }
        val recovered = ConnectException(original.message).apply { initCause(original) }

        assertTrue(recovered.isCertificateFailure())
    }
}
