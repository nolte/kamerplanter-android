package io.github.nolte.kamerplanter.feature.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pairingDataStore: DataStore<Preferences> by preferencesDataStore(name = "pairing")

/**
 * [PairingStore] backed by Preferences DataStore, so a paired dummy survives an app
 * restart and starts up already paired (requirement R12). "Unpair" clears the keys.
 */
@Singleton
class DataStorePairingStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : PairingStore {

    override val pairing: Flow<PairingPayload?> = context.pairingDataStore.data.map { prefs ->
        val baseUrl = prefs[KEY_BASE_URL]
        val code = prefs[KEY_CODE]
        if (baseUrl != null && code != null) PairingPayload(baseUrl, code) else null
    }

    override suspend fun save(payload: PairingPayload) {
        context.pairingDataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = payload.baseUrl
            prefs[KEY_CODE] = payload.code
        }
    }

    override suspend fun clear() {
        context.pairingDataStore.edit { prefs ->
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_CODE)
        }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_CODE = stringPreferencesKey("code")
    }
}
