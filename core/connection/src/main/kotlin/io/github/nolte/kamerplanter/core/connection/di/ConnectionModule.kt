package io.github.nolte.kamerplanter.core.connection.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.connection.DataStoreConnectionStore
import io.github.nolte.kamerplanter.core.connection.KeystoreCredentialStore
import javax.inject.Singleton

/**
 * Binds the two persistence seams to their implementations.
 *
 * These bindings moved here with the classes they bind: they belong to whoever owns the
 * implementation, and every consumer — `:feature:settings` today, the feature modules that
 * read the connection tomorrow — gets them from the one module rather than each declaring
 * its own.
 *
 * There is no debug or in-memory alternative bound anywhere for the credential half: a
 * build that stores credentials at all stores them Keystore-encrypted (R17).
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface ConnectionModule {

    @Binds
    @Singleton
    fun bindConnectionStore(impl: DataStoreConnectionStore): ConnectionStore

    @Binds
    @Singleton
    fun bindCredentialStore(impl: KeystoreCredentialStore): CredentialStore
}
