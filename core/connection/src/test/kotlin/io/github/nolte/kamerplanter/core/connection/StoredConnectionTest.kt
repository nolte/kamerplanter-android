package io.github.nolte.kamerplanter.core.connection

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The record mapping in isolation — pure Preferences in, [Connection] out — because the
 * store itself needs an Android context and the mapping is where the mistakes would live:
 * a legacy record from before a key existed has to read back as a connection, not as null,
 * and never as one claiming things it does not know.
 */
class StoredConnectionTest {

    @Test
    fun `every kind survives the round trip`() {
        val connections = listOf(
            Connection.QrPairing("https://a", "demo", identity = "me@example.org", belowVersionFloor = true),
            Connection.ApiKey("https://b", "demo", keyHint = "…cdef", identity = "svc@example.org"),
            Connection.LightMode("https://c", "demo", belowVersionFloor = true),
        )
        connections.forEach { connection ->
            val prefs = mutablePreferencesOf()
            prefs.writeConnection(connection)
            assertEquals(connection, prefs.readConnection())
        }
    }

    @Test
    fun `a record from before the new keys existed still reads back`() {
        // Exactly what a pre-#52 install has on disk: method, base URL, tenant — no
        // below_version_floor, and for an API key no identity.
        val legacy = preferencesOf(
            stringPreferencesKey("method") to ConnectionMethod.API_KEY.name,
            stringPreferencesKey("base_url") to "https://plants.example.org",
            stringPreferencesKey("tenant_slug") to "demo",
            stringPreferencesKey("key_hint") to "…cdef",
        )

        val read = read(legacy)

        assertEquals(
            Connection.ApiKey("https://plants.example.org", "demo", "…cdef", identity = null),
            read,
        )
        assertFalse(read.belowVersionFloor)
    }

    @Test
    fun `saving a false flag writes no key at all`() {
        val prefs = mutablePreferencesOf()
        prefs.writeConnection(Connection.LightMode("https://c", "demo", belowVersionFloor = false))

        assertNull(prefs[booleanPreferencesKey("below_version_floor")])
    }

    @Test
    fun `switching method leaves nothing of the previous record behind`() {
        val prefs = mutablePreferencesOf()
        prefs.writeConnection(Connection.ApiKey("https://b", "demo", "…cdef", identity = "svc@example.org"))
        prefs.writeConnection(Connection.LightMode("https://c", "other"))

        assertNull(prefs[stringPreferencesKey("key_hint")])
        assertNull(prefs[stringPreferencesKey("identity")])
        assertEquals(Connection.LightMode("https://c", "other"), prefs.readConnection())
    }

    @Test
    fun `an incomplete record is not a connection`() {
        assertNull(preferencesOf(stringPreferencesKey("base_url") to "https://a").readConnection())
        assertNull(
            preferencesOf(
                stringPreferencesKey("method") to "SOMETHING_NEWER",
                stringPreferencesKey("base_url") to "https://a",
                stringPreferencesKey("tenant_slug") to "demo",
            ).readConnection(),
        )
    }

    private fun read(prefs: androidx.datastore.preferences.core.Preferences) =
        prefs.readConnection() ?: error("legacy record must read back as a connection")
}
