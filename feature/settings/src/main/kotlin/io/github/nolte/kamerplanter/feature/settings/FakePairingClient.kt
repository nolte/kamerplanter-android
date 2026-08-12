package io.github.nolte.kamerplanter.feature.settings

import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * A deliberately fake [PairingClient]: no network at all. It simulates a backend
 * round-trip with an artificial delay and returns a canned result, so both UI paths of
 * the clickable dummy are reachable until the real endpoint lands in
 * [kamerplanter#1118](https://github.com/nolte/kamerplanter/issues/1118).
 *
 * A payload whose [PairingPayload.code] equals [FAIL_CODE] drives the failure branch;
 * anything else succeeds. Named `Fake*` and isolated behind [PairingClient] so the real
 * client replaces it via a single Hilt binding, with no change to the UI.
 */
class FakePairingClient @Inject constructor() : PairingClient {

    override suspend fun pair(payload: PairingPayload): PairingResult {
        delay(FAKE_LATENCY_MS)
        return if (payload.code.equals(FAIL_CODE, ignoreCase = true)) {
            PairingResult.Failure("backend rejected pairing code '${payload.code}'")
        } else {
            PairingResult.Success
        }
    }

    companion object {
        /** A scanned code of this value demonstrates the failure path in the dummy. */
        const val FAIL_CODE = "fail"
        private const val FAKE_LATENCY_MS = 1_200L
    }
}
