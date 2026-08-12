package io.github.nolte.kamerplanter.feature.settings.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.feature.settings.DataStorePairingStore
import io.github.nolte.kamerplanter.feature.settings.FakePairingClient
import io.github.nolte.kamerplanter.feature.settings.PairingClient
import io.github.nolte.kamerplanter.feature.settings.PairingStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface SettingsModule {

    // Swap this single binding for the #1118-backed client to make pairing real (R19).
    @Binds
    @Singleton
    fun bindPairingClient(impl: FakePairingClient): PairingClient

    @Binds
    @Singleton
    fun bindPairingStore(impl: DataStorePairingStore): PairingStore
}
