package io.github.nolte.kamerplanter.feature.settings

/**
 * The JVM stand-in for [KeystoreCredentialStore]. The production store encrypts under an
 * Android Keystore key, which does not exist off-device, so everything that *reasons* about
 * credentials is tested against this instead — the same pattern the connection half already
 * uses, and the reason [CredentialStore] is an interface at all (R36).
 *
 * [failOnSave] drives the branch that matters most: a store that cannot write must not leave
 * half a connection behind.
 */
internal class InMemoryCredentialStore(initial: Credential = Credential.None) : CredentialStore {

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
