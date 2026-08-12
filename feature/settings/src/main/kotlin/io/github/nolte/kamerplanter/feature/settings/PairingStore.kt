package io.github.nolte.kamerplanter.feature.settings

import kotlinx.coroutines.flow.Flow

/**
 * Persistence seam for the paired [PairingPayload]. Kept as an interface so the pairing
 * state machine ([SettingsViewModel]) can be unit-tested on the JVM against an in-memory
 * fake, without DataStore or a device. The production implementation is
 * [DataStorePairingStore].
 */
interface PairingStore {

    /** Emits the persisted pairing, or `null` when the app is not paired. */
    val pairing: Flow<PairingPayload?>

    suspend fun save(payload: PairingPayload)

    suspend fun clear()
}
