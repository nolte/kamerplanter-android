package io.github.nolte.kamerplanter.feature.settings.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.feature.settings.ConnectionClient
import io.github.nolte.kamerplanter.feature.settings.ConnectionStore
import io.github.nolte.kamerplanter.feature.settings.DataStoreConnectionStore
import io.github.nolte.kamerplanter.feature.settings.FakeConnectionClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface SettingsModule {

    // Swap this single binding for the `core/network/`-backed client to make connecting
    // real; the fake then stays behind the debug build variant only (R34).
    @Binds
    @Singleton
    fun bindConnectionClient(impl: FakeConnectionClient): ConnectionClient

    @Binds
    @Singleton
    fun bindConnectionStore(impl: DataStoreConnectionStore): ConnectionStore
}
