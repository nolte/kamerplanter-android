package io.github.nolte.kamerplanter.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeConnectionClientTest {

    private val client = FakeConnectionClient()

    @Test
    fun `any ordinary pairing code verifies against the single canned tenant`() = runTest {
        val result = client.connect(ConnectionRequest.QrPairing(baseUrl = "https://x", code = "ABC123"))

        assertEquals(
            ConnectionResult.Verified(
                identity = FakeConnectionClient.FAKE_IDENTITY,
                tenants = listOf(FakeConnectionClient.FAKE_TENANT),
            ),
            result,
        )
    }

    @Test
    fun `an api key verifies the same way`() = runTest {
        val result = client.connect(ConnectionRequest.ApiKey(baseUrl = "https://x", key = "kp_sk_abcdef"))

        assertTrue(result is ConnectionResult.Verified)
    }

    @Test
    fun `a light-mode request verifies with no tenant at all`() = runTest {
        val result = client.connect(ConnectionRequest.LightMode(baseUrl = "https://x"))

        assertEquals(ConnectionResult.Verified(identity = null, tenants = emptyList()), result)
    }

    @Test
    fun `the sentinel fail code drives the failure branch`() = runTest {
        val result = client.connect(
            ConnectionRequest.QrPairing(baseUrl = "https://x", code = FakeConnectionClient.FAIL_CODE),
        )

        assertTrue(result is ConnectionResult.Failure)
    }

    @Test
    fun `the fail code is matched case-insensitively`() = runTest {
        val result = client.connect(ConnectionRequest.QrPairing(baseUrl = "https://x", code = "FAIL"))

        assertTrue(result is ConnectionResult.Failure)
    }

    @Test
    fun `a rejected api key is refused just like a pairing code`() = runTest {
        val result = client.connect(
            ConnectionRequest.ApiKey(baseUrl = "https://x", key = FakeConnectionClient.FAIL_CODE),
        )

        assertTrue(result is ConnectionResult.Failure)
    }

    @Test
    fun `a rejection never echoes the credential it refused`() = runTest {
        val secret = "kp_sk_supersecret"
        val request = ConnectionRequest.ApiKey(baseUrl = "https://x", key = secret)

        val result = client.connect(request.copy(key = FakeConnectionClient.FAIL_CODE))

        assertTrue((result as ConnectionResult.Failure).reason.contains(secret).not())
        // The request itself must not leak the key either, however it is logged (R19).
        assertTrue(request.toString().contains(secret).not())
    }
}
