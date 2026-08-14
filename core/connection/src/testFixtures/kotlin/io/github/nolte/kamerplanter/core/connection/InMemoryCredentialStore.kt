package io.github.nolte.kamerplanter.core.connection

/**
 * The JVM stand-in for `KeystoreCredentialStore`. The production store encrypts under an
 * Android Keystore key, which does not exist off-device, so everything that *reasons* about
 * credentials is tested against this instead — the same pattern the connection half already
 * uses, and the reason [CredentialStore] is an interface at all (R36).
 *
 * [failOnSave] drives the branch that matters most: a store that cannot write must not leave
 * half a connection behind.
 *
 * A test fixture rather than an ordinary test source because two modules need the same
 * fake: this module's `CredentialStoreContractTest` and `:feature:settings`'s
 * `SettingsViewModelTest`. Test source sets are not shared across modules, so the
 * alternative was a second copy — and two copies of a fake drift, which quietly leaves two
 * suites testing two different things while both stay green.
 */
class InMemoryCredentialStore(initial: Credential = Credential.None) : CredentialStore {

    var stored: Credential = initial
        private set

    var cleared: Boolean = false
        private set

    /** WHEN set, the next [save] fails the way a broken Keystore would. */
    var failOnSave: Boolean = false

    override suspend fun load(): Credential = stored

    override suspend fun save(credential: Credential) {
        if (failOnSave) error("the credential could not be encrypted")
        stored = credential
    }

    override suspend fun clear() {
        cleared = true
        stored = Credential.None
    }
}
