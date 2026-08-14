package io.github.nolte.kamerplanter.core.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// A file of its own, never the `connection` file: the two halves have different storage
// rules, and keeping them apart makes "no secret was written next to the display data"
// something you can see rather than something you have to trust (R17, R18).
private val Context.credentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "credentials")

/**
 * [CredentialStore] whose every secret is encrypted with [KeystoreSecretCipher] — AES-256-GCM
 * under a key that is generated in, and never leaves, the Android Keystore (R17).
 *
 * What reaches DataStore is ciphertext, so R17's "no secret in plain DataStore" holds: the
 * record is unreadable without the Keystore key, which cannot be extracted from the device
 * and is not part of any backup. The two plaintext fields are deliberate and are not secrets
 * — the record's kind, which says *which* variant to read back, and the access token's
 * expiry instant, which the refresh cycle needs to consult without decrypting anything.
 *
 * Every write starts by wiping the record, so switching method (R27) or disconnecting (R25)
 * cannot leave the previous method's secret behind.
 */
@Singleton
class KeystoreCredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : CredentialStore {

    private val cipher = KeystoreSecretCipher()

    override suspend fun load(): Credential = read(context.credentialDataStore.data.first())

    override suspend fun save(credential: Credential) {
        context.credentialDataStore.edit { prefs ->
            prefs.clear()
            when (credential) {
                is Credential.Session -> {
                    prefs[KEY_KIND] = KIND_SESSION
                    prefs[KEY_ACCESS_TOKEN] = cipher.encrypt(credential.accessToken)
                    prefs[KEY_REFRESH_TOKEN] = cipher.encrypt(credential.refreshToken)
                    prefs[KEY_ACCESS_TOKEN_EXPIRES_AT] = credential.accessTokenExpiresAtEpochMillis
                }

                is Credential.ApiKey -> {
                    prefs[KEY_KIND] = KIND_API_KEY
                    prefs[KEY_API_KEY] = cipher.encrypt(credential.key)
                }

                // Light mode holds no secret, so the wipe above is the entire write (R10).
                Credential.None -> Unit
            }
        }
    }

    override suspend fun clear() {
        context.credentialDataStore.edit { prefs -> prefs.clear() }
    }

    private fun read(prefs: Preferences): Credential = when (prefs[KEY_KIND]) {
        KIND_SESSION -> readSession(prefs)
        KIND_API_KEY -> prefs[KEY_API_KEY]?.let(::decryptOrNull)?.let(Credential::ApiKey) ?: Credential.None
        else -> Credential.None
    }

    private fun readSession(prefs: Preferences): Credential {
        val accessToken = prefs[KEY_ACCESS_TOKEN]?.let(::decryptOrNull)
        val refreshToken = prefs[KEY_REFRESH_TOKEN]?.let(::decryptOrNull)
        return if (accessToken != null && refreshToken != null) {
            Credential.Session(
                accessToken = accessToken,
                refreshToken = refreshToken,
                accessTokenExpiresAtEpochMillis = prefs[KEY_ACCESS_TOKEN_EXPIRES_AT] ?: 0L,
            )
        } else {
            Credential.None
        }
    }

    // A record that will not decrypt — a restored backup, a key the system dropped, a
    // truncated write — is no credential at all. Reporting it as absent sends the user back
    // through Settings, which is the only real repair anyway (R23).
    private fun decryptOrNull(encoded: String): String? = runCatching { cipher.decrypt(encoded) }.getOrNull()

    private companion object {
        const val KIND_SESSION = "session"
        const val KIND_API_KEY = "api_key"

        /** Which variant the record holds — a discriminator, not a secret. */
        val KEY_KIND = stringPreferencesKey("kind")

        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_API_KEY = stringPreferencesKey("api_key")

        /** Wall-clock expiry of the access token — needed before any decryption, and not a secret. */
        val KEY_ACCESS_TOKEN_EXPIRES_AT = longPreferencesKey("access_token_expires_at")
    }
}
