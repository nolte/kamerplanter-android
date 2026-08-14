package io.github.nolte.kamerplanter.core.connection

/**
 * Persistence seam for the secret half of a connection (R17) — the twin of [ConnectionStore],
 * deliberately a second seam rather than a widened one: the two halves have different
 * storage rules (encrypted vs. plain), different lifetimes (a rotating refresh token is
 * rewritten without the connection changing, R22) and different audiences (Settings displays
 * one, the network layer consumes the other).
 *
 * Kept as an interface for the same reason [ConnectionStore] is: the Android Keystore does
 * not exist on the JVM, so the production implementation ([KeystoreCredentialStore]) can only
 * run on a device. Everything that reasons *about* credentials — the state machine here,
 * later the refresh cycle (R21–R23) and the credential provider (R30) — talks to this
 * interface and is unit-tested against an in-memory fake (R36).
 *
 * Reading is a one-shot `suspend` call, not a `Flow`: a secret should be pulled at the moment
 * it is needed and dropped again, not held live in a hot stream for observers to collect.
 *
 * Implementations may throw on storage or crypto failure. Callers must treat a failed
 * [save] as "nothing was stored" and repair the other half accordingly — a connection
 * without its credential is a broken state.
 */
interface CredentialStore {

    /** The stored credential, or [Credential.None] when there is no secret to attach. */
    suspend fun load(): Credential

    /**
     * Replaces the stored credential wholesale. Saving [Credential.None] is a full erase,
     * which is exactly what connecting to a light-mode instance means (R10).
     */
    suspend fun save(credential: Credential)

    /** Removes the secret from device storage completely (R25). */
    suspend fun clear()
}
