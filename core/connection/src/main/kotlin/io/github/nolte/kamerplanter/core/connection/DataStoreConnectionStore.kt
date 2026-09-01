package io.github.nolte.kamerplanter.core.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        context.connectionDataStore.edit { prefs -> prefs.writeConnection(connection) }
    }

    override suspend fun clear() {
        context.connectionDataStore.edit { prefs -> prefs.clear() }
    }

    private fun read(prefs: Preferences): Connection? = prefs.readConnection()
}

/**
 * The record's shape, kept beside its reader below and separate from the store: the mapping
 * is pure and the round trip — including a legacy record written before a key existed — is
 * pinned by `StoredConnectionTest` without an Android context.
 */
private val KEY_METHOD = stringPreferencesKey("method")
private val KEY_BASE_URL = stringPreferencesKey("base_url")
private val KEY_TENANT_SLUG = stringPreferencesKey("tenant_slug")
private val KEY_IDENTITY = stringPreferencesKey("identity")
private val KEY_BELOW_VERSION_FLOOR = booleanPreferencesKey("below_version_floor")

/** Already masked when it is written — never the key itself (R19). */
private val KEY_KEY_HINT = stringPreferencesKey("key_hint")

internal fun MutablePreferences.writeConnection(connection: Connection) {
    // Wipe first: switching method must not leave the previous method's keys behind (R27).
    clear()
    this[KEY_METHOD] = connection.method.name
    this[KEY_BASE_URL] = connection.baseUrl
    // Written only when set: absent and false read back the same, and a record from
    // before the flag existed has no key to migrate.
    if (connection.belowVersionFloor) this[KEY_BELOW_VERSION_FLOOR] = true
    when (connection) {
        is Connection.QrPairing -> {
            this[KEY_TENANT_SLUG] = connection.tenantSlug
            connection.identity?.let { this[KEY_IDENTITY] = it }
        }
        is Connection.ApiKey -> {
            this[KEY_TENANT_SLUG] = connection.tenantSlug
            this[KEY_KEY_HINT] = connection.keyHint
            connection.identity?.let { this[KEY_IDENTITY] = it }
        }
        is Connection.LightMode -> this[KEY_TENANT_SLUG] = connection.tenantSlug
    }
}

internal fun Preferences.readConnection(): Connection? {
    val baseUrl = this[KEY_BASE_URL] ?: return null
    val tenantSlug = this[KEY_TENANT_SLUG]
    val belowFloor = this[KEY_BELOW_VERSION_FLOOR] ?: false
    return when (this[KEY_METHOD]) {
        ConnectionMethod.QR_PAIRING.name ->
            tenantSlug?.let { Connection.QrPairing(baseUrl, it, this[KEY_IDENTITY], belowFloor) }
        ConnectionMethod.API_KEY.name -> tenantSlug?.let {
            Connection.ApiKey(baseUrl, it, this[KEY_KEY_HINT].orEmpty(), this[KEY_IDENTITY], belowFloor)
        }
        // Read like the others: a stored light-mode connection from before the slug
        // existed has none, and is dropped rather than restored as one that can address
        // nothing.
        ConnectionMethod.LIGHT_MODE.name ->
            tenantSlug?.let { Connection.LightMode(baseUrl, it, belowFloor) }
        else -> null
    }
}
