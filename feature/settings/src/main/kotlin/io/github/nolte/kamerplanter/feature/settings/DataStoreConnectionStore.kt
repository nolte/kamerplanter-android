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

// A new store, not a migration of the dummy's `pairing` file: the old `{base_url, code}`
// shape has no counterpart in the three-kind model, and a dummy pairing is worth nothing.
private val Context.connectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "connection")

/**
 * [ConnectionStore] backed by Preferences DataStore, so a connection survives an app
 * restart and the user is never asked to reconnect (R20). Only the non-secret half is
 * written here (R18); "disconnect" clears every key (R25).
 *
 * The method is the discriminator: it decides which of the three [Connection] kinds a
 * stored record reads back as. A record whose method is missing, unknown or whose
 * credential-bearing kind has no tenant slug reads back as `null` — an incomplete record
 * is not a connection.
 */
@Singleton
class DataStoreConnectionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectionStore {

    override val connection: Flow<Connection?> = context.connectionDataStore.data.map(::read)

    override suspend fun save(connection: Connection) {
        context.connectionDataStore.edit { prefs ->
            // Wipe first: switching method must not leave the previous method's keys behind (R27).
            prefs.clear()
            prefs[KEY_METHOD] = connection.method.name
            prefs[KEY_BASE_URL] = connection.baseUrl
            when (connection) {
                is Connection.QrPairing -> {
                    prefs[KEY_TENANT_SLUG] = connection.tenantSlug
                    connection.identity?.let { prefs[KEY_IDENTITY] = it }
                }
                is Connection.ApiKey -> {
                    prefs[KEY_TENANT_SLUG] = connection.tenantSlug
                    prefs[KEY_KEY_HINT] = connection.keyHint
                }
                is Connection.LightMode -> Unit
            }
        }
    }

    override suspend fun clear() {
        context.connectionDataStore.edit { prefs -> prefs.clear() }
    }

    private fun read(prefs: Preferences): Connection? {
        val baseUrl = prefs[KEY_BASE_URL] ?: return null
        val tenantSlug = prefs[KEY_TENANT_SLUG]
        return when (prefs[KEY_METHOD]) {
            ConnectionMethod.QR_PAIRING.name ->
                tenantSlug?.let { Connection.QrPairing(baseUrl, it, prefs[KEY_IDENTITY]) }
            ConnectionMethod.API_KEY.name ->
                tenantSlug?.let { Connection.ApiKey(baseUrl, it, prefs[KEY_KEY_HINT].orEmpty()) }
            ConnectionMethod.LIGHT_MODE.name -> Connection.LightMode(baseUrl)
            else -> null
        }
    }

    private companion object {
        val KEY_METHOD = stringPreferencesKey("method")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_TENANT_SLUG = stringPreferencesKey("tenant_slug")
        val KEY_IDENTITY = stringPreferencesKey("identity")

        /** Already masked when it is written — never the key itself (R19). */
        val KEY_KEY_HINT = stringPreferencesKey("key_hint")
    }
}
