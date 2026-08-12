package io.github.nolte.kamerplanter.feature.settings.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.feature.settings.ConnectionClient
import io.github.nolte.kamerplanter.feature.settings.ConnectionStore
import io.github.nolte.kamerplanter.feature.settings.CredentialStore
import io.github.nolte.kamerplanter.feature.settings.DataStoreConnectionStore
import io.github.nolte.kamerplanter.feature.settings.KeystoreCredentialStore
import javax.inject.Singleton

/**
 * The variant-independent half of the settings graph.
 *
 * [ConnectionClient] is deliberately **not** bound here: it is the one binding that differs
 * per build variant (R34), so it lives in `src/debug/` and `src/release/` next to the
 * implementation each variant is allowed to see —
 * `di/DebugConnectionClientModule` and `di/ReleaseConnectionClientModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface SettingsModule {

    @Binds
    @Singleton
    fun bindConnectionStore(impl: DataStoreConnectionStore): ConnectionStore

    // The secret half. There is no debug or in-memory alternative bound anywhere: a build
    // that stores credentials at all stores them Keystore-encrypted (R17).
    @Binds
    @Singleton
    fun bindCredentialStore(impl: KeystoreCredentialStore): CredentialStore
}
