package io.github.nolte.kamerplanter.core.connection

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The behaviour every [CredentialStore] owes its callers, written against the in-memory
 * implementation because the Keystore-backed one cannot run on the JVM (R36).
 *
 * [store] is the single seam an on-device test would swap for [KeystoreCredentialStore] to
 * hold the encrypted implementation to the same contract.
 */
class CredentialStoreContractTest {

    private fun store(): CredentialStore = InMemoryCredentialStore()

    private val session = Credential.Session(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        accessTokenExpiresAtEpochMillis = 1_700_000_000_000L,
    )

    @Test
    fun `an empty store holds no credential rather than nothing`() = runTest {
        assertEquals(Credential.None, store().load())
    }

    @Test
    fun `a session round-trips whole`() = runTest {
        val store = store()

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun `an api key round-trips whole`() = runTest {
        val store = store()

        store.save(Credential.ApiKey("kp_sk_abcdef"))

        assertEquals(Credential.ApiKey("kp_sk_abcdef"), store.load())
    }

    @Test
    fun `a rotated session replaces the previous one instead of adding to it`() = runTest {
        val store = store()
        store.save(session)

        val rotated = session.copy(refreshToken = "rotated-refresh-token")
        store.save(rotated)

        assertEquals(rotated, store.load())
    }

    @Test
    fun `saving no credential erases the stored one`() = runTest {
        val store = store()
        store.save(Credential.ApiKey("kp_sk_abcdef"))

        store.save(Credential.None)

        assertEquals(Credential.None, store.load())
    }

    @Test
    fun `clearing removes the secret completely`() = runTest {
        val store = store()
        store.save(session)

        store.clear()

        assertEquals(Credential.None, store.load())
    }
}
