package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.Tenant
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
            // A redeemed pairing code answers with a session, as the real endpoint does (R8).
            is ConnectionRequest.QrPairing -> verify(request.code, fakeSession())
            // An accepted key *is* the credential; the backend hands nothing else back (R9).
            is ConnectionRequest.ApiKey -> verify(request.key, Credential.ApiKey(request.key))
            // A light-mode instance has no accounts, so there is nothing to prove, nothing to
            // scope, and nothing to store (R10).
            is ConnectionRequest.LightMode -> ConnectionResult.Verified(
                identity = null,
                tenants = emptyList(),
                credential = Credential.None,
            )
        }
    }

    // The reason never echoes the secret it rejected (R19).
    private fun verify(secret: String, credential: Credential): ConnectionResult =
        if (secret.equals(FAIL_CODE, ignoreCase = true)) {
            ConnectionResult.Failure("backend rejected the credential")
        } else {
            ConnectionResult.Verified(
                identity = FAKE_IDENTITY,
                tenants = listOf(FAKE_TENANT),
                credential = credential,
            )
        }

    private fun fakeSession() = Credential.Session(
        accessToken = FAKE_ACCESS_TOKEN,
        refreshToken = FAKE_REFRESH_TOKEN,
        accessTokenExpiresAtEpochMillis = System.currentTimeMillis() + FAKE_ACCESS_TOKEN_LIFETIME_MS,
    )

    companion object {
        /** A scanned code or typed key of this value demonstrates the failure path. */
        const val FAIL_CODE = "fail"

        /** The identity the canned instance reports back (R26). */
        const val FAKE_IDENTITY = "demo@kamerplanter.local"

        /** The single tenant the canned instance offers, adopted without asking (R15). */
        val FAKE_TENANT = Tenant(slug = "demo", displayName = "Demo garden")

        /** Canned tokens — obviously fake, so a real one can never be confused with them. */
        const val FAKE_ACCESS_TOKEN = "fake-access-token"
        const val FAKE_REFRESH_TOKEN = "fake-refresh-token"

        private const val FAKE_LATENCY_MS = 1_200L

        /** The real access token lives 15 minutes (R8); the fake one pretends to as well. */
        private const val FAKE_ACCESS_TOKEN_LIFETIME_MS = 15L * 60L * 1_000L
    }
}
