package io.github.nolte.kamerplanter.feature.settings

import kotlinx.coroutines.flow.Flow

/**
 * Persistence seam for the non-secret half of a [Connection] (R18). Kept as an interface
 * so the connection state machine ([SettingsViewModel]) can be unit-tested on the JVM
 * against an in-memory fake, without DataStore or a device. The production implementation
 * is [DataStoreConnectionStore].
 *
 * Secrets do **not** travel through here: the refresh token, access token and API key are
 * stored encrypted under an Android Keystore-backed key (R17), which is a separate seam of
 * [issue #8](https://github.com/nolte/kamerplanter-android/issues/8). What this store
 * holds is exactly what Settings may display (R26).
 */
interface ConnectionStore {

    /** Emits the persisted connection, or `null` when the app is disconnected. */
    val connection: Flow<Connection?>

    /** Called only after verification succeeded — the app never stores an unproven connection (R13). */
    suspend fun save(connection: Connection)

    suspend fun clear()
}
