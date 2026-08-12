package io.github.nolte.kamerplanter.feature.settings

import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * A deliberately fake [ConnectionClient]: no network at all. It simulates a round trip
 * with an artificial delay and answers from a canned instance, so every branch of the
 * connection flow stays clickable without a reachable kamerplanter backend
 * ([issue #8](https://github.com/nolte/kamerplanter-android/issues/8), R34).
 *
 * A pairing code or API key equal to [FAIL_CODE] drives the failure branch; anything else
 * verifies against the single tenant [FAKE_TENANT], which the state machine then adopts
 * automatically (R15). A light-mode request always verifies and resolves no tenant at all.
 *
 * Named `Fake*` and isolated behind [ConnectionClient] so the real client replaces it via a
 * single Hilt binding, with no change to the state machine or the UI.
 */
class FakeConnectionClient @Inject constructor() : ConnectionClient {

    override suspend fun connect(request: ConnectionRequest): ConnectionResult {
        delay(FAKE_LATENCY_MS)
        return when (request) {
            is ConnectionRequest.QrPairing -> verify(request.code)
            is ConnectionRequest.ApiKey -> verify(request.key)
            // A light-mode instance has no accounts, so there is nothing to prove and
            // nothing to scope (R10).
            is ConnectionRequest.LightMode -> ConnectionResult.Verified(identity = null, tenants = emptyList())
        }
    }

    // The reason never echoes the secret it rejected (R19).
    private fun verify(secret: String): ConnectionResult =
        if (secret.equals(FAIL_CODE, ignoreCase = true)) {
            ConnectionResult.Failure("backend rejected the credential")
        } else {
            ConnectionResult.Verified(identity = FAKE_IDENTITY, tenants = listOf(FAKE_TENANT))
        }

    companion object {
        /** A scanned code or typed key of this value demonstrates the failure path. */
        const val FAIL_CODE = "fail"

        /** The identity the canned instance reports back (R26). */
        const val FAKE_IDENTITY = "demo@kamerplanter.local"

        /** The single tenant the canned instance offers, adopted without asking (R15). */
        val FAKE_TENANT = Tenant(slug = "demo", displayName = "Demo garden")

        private const val FAKE_LATENCY_MS = 1_200L
    }
}
