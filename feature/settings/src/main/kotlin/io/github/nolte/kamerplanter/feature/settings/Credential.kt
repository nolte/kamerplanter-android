package io.github.nolte.kamerplanter.feature.settings

/**
 * The **secret** half of a connection — what the app must prove itself with on every later
 * call, and the only part of a connection that is encrypted under an Android Keystore-backed
 * key (R17). Its non-secret counterpart is [Connection], which stays in plain DataStore for
 * display (R18); the two are stored and cleared together by [SettingsViewModel].
 *
 * One variant per [ConnectionMethod], because what a verified connection holds differs per
 * method — and light mode holds nothing at all. That "nothing" is [None]: a real member of
 * the domain, not an absent value. A light-mode instance has no accounts, so there is no
 * secret to keep, and modelling it as `null` would force every consumer to decide whether a
 * missing credential means "light mode" or "something went wrong".
 *
 * No variant is ever rendered in the UI (R19); [toString] masks every secret it carries so a
 * log line, a crash report or a test failure cannot leak one.
 */
sealed interface Credential {

    /**
     * A paired device's session (R8): a short-lived access token plus the rotating refresh
     * token that renews it. The backend issues the access token for 15 minutes and the
     * refresh token for 30 days, and rotates the refresh token on every renewal — which is
     * why renewing means writing this whole record back (R21, R22), not patching a field.
     *
     * [accessTokenExpiresAtEpochMillis] is an absolute wall-clock instant, not the `expires_in`
     * offset the backend answers with: an offset is meaningless after an app restart. It is
     * derived once, where the response is read, and is not itself a secret.
     */
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val accessTokenExpiresAtEpochMillis: Long,
    ) : Credential {

        override fun toString(): String = "Session(accessToken=${maskSecret(accessToken)}, " +
            "refreshToken=${maskSecret(refreshToken)}, " +
            "accessTokenExpiresAtEpochMillis=$accessTokenExpiresAtEpochMillis)"
    }

    /**
     * A long-lived `kp_sk_…` key (R9). It carries its own `tenant_scope`, never expires on a
     * schedule the app can see, and is therefore never refreshed — it is stored exactly as
     * the user typed it and replaced only by connecting again.
     */
    data class ApiKey(val key: String) : Credential {

        override fun toString(): String = "ApiKey(key=${maskSecret(key)})"
    }

    /**
     * No secret exists. This is what a light-mode connection holds (R10, R11) — and equally
     * what [CredentialStore] answers when nothing is stored, because both statements are the
     * same one: there is no secret to attach to a request. Which of the two it is follows
     * from the stored [Connection], not from here.
     */
    data object None : Credential
}
