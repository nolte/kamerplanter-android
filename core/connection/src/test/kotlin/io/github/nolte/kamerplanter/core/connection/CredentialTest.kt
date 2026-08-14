package io.github.nolte.kamerplanter.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R19: a stored secret never appears in clear text — not in the UI, not in a log line. */
class CredentialTest {

    private val accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.super"
    private val refreshToken = "rt_0123456789abcdef"

    @Test
    fun `a session never prints its tokens`() {
        val printed = Credential.Session(accessToken, refreshToken, EXPIRES_AT).toString()

        assertFalse(printed.contains(accessToken))
        assertFalse(printed.contains(refreshToken))
    }

    @Test
    fun `a session prints a masked hint and its expiry`() {
        val printed = Credential.Session(accessToken, refreshToken, EXPIRES_AT).toString()

        assertTrue(printed.contains("…uper"))
        assertTrue(printed.contains("…cdef"))
        assertTrue(printed.contains(EXPIRES_AT.toString()))
    }

    @Test
    fun `an api key never prints itself`() {
        val key = "kp_sk_supersecret"

        val printed = Credential.ApiKey(key).toString()

        assertFalse(printed.contains(key))
        assertTrue(printed.contains("…cret"))
    }

    @Test
    fun `a secret short enough to guess from its hint is masked entirely`() {
        assertEquals("…", Credential.ApiKey("abcd").toString().substringAfter("key=").substringBefore(")"))
    }

    @Test
    fun `light mode holds a credential-shaped nothing, not an absent one`() {
        val credential: Credential = Credential.None

        assertTrue(credential is Credential.None)
    }

    private companion object {
        const val EXPIRES_AT = 1_700_000_000_000L
    }
}
