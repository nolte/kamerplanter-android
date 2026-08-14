package io.github.nolte.kamerplanter.core.connection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts a single secret under an AES-256-GCM key that is generated inside the
 * Android Keystore and never leaves it (`spec/android/security/`: stored secrets MUST be
 * Keystore-backed, using AES-256-GCM, `SecureRandom`, and no provider other than
 * `AndroidKeyStore`).
 *
 * Deliberately **not** Jetpack Security / `EncryptedSharedPreferences`, which
 * [issue #8](https://github.com/nolte/kamerplanter-android/issues/8) §4 proposes by name: it
 * is deprecated as of 2025 with no drop-in successor. Targeting the Keystore directly needs
 * no dependency at all — the whole mechanism is platform API — which is why the version
 * catalog gains nothing for this.
 *
 * Wire format of [encrypt]: `base64(iv ‖ ciphertext‖tag)`. The IV is the 12 bytes the
 * Keystore's own `SecureRandom` generates per encryption; supplying one is forbidden here
 * anyway, because the key is created with `setRandomizedEncryptionRequired(true)` so that no
 * caller can ever reuse an IV under a GCM key.
 *
 * Device-only by nature: there is no Android Keystore on the JVM, so this class cannot run
 * under `./gradlew test`. That is precisely why it sits behind [CredentialStore] rather than
 * inside the state machine.
 */
internal class KeystoreSecretCipher {

    private val lock = Any()

    @Volatile
    private var cachedKey: SecretKey? = null

    /** Encrypts [plaintext] under the connection key, creating that key on first use. */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    /**
     * Reverses [encrypt]. Throws when the record is malformed, when the tag does not
     * authenticate, or when the key is gone — the caller turns any of those into "there is no
     * credential" rather than guessing at a repair.
     */
    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_LENGTH_BYTES) { "encrypted record is too short to carry an IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH_BYTES),
        )
        return String(cipher.doFinal(bytes, IV_LENGTH_BYTES, bytes.size - IV_LENGTH_BYTES), Charsets.UTF_8)
    }

    // Generating twice would silently orphan everything written under the first key, so the
    // load-or-create is done once behind a lock and then cached.
    private fun key(): SecretKey =
        cachedKey ?: synchronized(lock) {
            cachedKey ?: loadOrCreateKey().also { cachedKey = it }
        }

    /**
     * Loads the connection key, replacing it when what sits under the alias cannot be used.
     *
     * `getEntry` does not answer `null` for a damaged entry — it throws
     * `UnrecoverableKeyException` or `KeyStoreException` for a key the system invalidated, an
     * entry written under another user profile, or a Keymaster that lost it. Letting that
     * escape would be a dead end rather than an error: every [encrypt] fails, so the state
     * machine reports a storage failure on every attempt and the user can never connect again
     * by any credential-bearing method, with nothing in the app that repairs it.
     *
     * Dropping the alias and generating a fresh key costs exactly what is already lost —
     * records written under the old key stopped being decryptable the moment it became
     * unusable — and turns a permanent lockout into a single reconnect.
     */
    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = runCatching { keyStore.getEntry(KEY_ALIAS, null) }
            .getOrNull() as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        // Delete only when the alias is actually present: a first run has nothing to remove,
        // and attempting it would turn the normal path into an error.
        runCatching { if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS) }
        return createKey()
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // No IV may ever be supplied by a caller, and none may ever repeat.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"

        /**
         * Versioned so a future change of format or key parameters can introduce a new key
         * instead of reinterpreting records written under the old one.
         */
        const val KEY_ALIAS = "io.github.nolte.kamerplanter.connection.credentials.v1"

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
