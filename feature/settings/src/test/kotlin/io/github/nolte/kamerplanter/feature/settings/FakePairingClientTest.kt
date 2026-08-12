package io.github.nolte.kamerplanter.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakePairingClientTest {

    private val client = FakePairingClient()

    @Test
    fun `any ordinary code pairs successfully`() = runTest {
        val result = client.pair(PairingPayload(baseUrl = "https://x", code = "ABC123"))

        assertEquals(PairingResult.Success, result)
    }

    @Test
    fun `the sentinel fail code drives the failure branch`() = runTest {
        val result = client.pair(PairingPayload(baseUrl = "https://x", code = FakePairingClient.FAIL_CODE))

        assertTrue(result is PairingResult.Failure)
    }

    @Test
    fun `the fail code is matched case-insensitively`() = runTest {
        val result = client.pair(PairingPayload(baseUrl = "https://x", code = "FAIL"))

        assertTrue(result is PairingResult.Failure)
    }
}
