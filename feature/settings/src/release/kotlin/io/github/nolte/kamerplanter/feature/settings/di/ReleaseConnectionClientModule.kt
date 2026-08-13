package io.github.nolte.kamerplanter.feature.settings.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.feature.settings.ConnectionClient
import io.github.nolte.kamerplanter.feature.settings.UnavailableConnectionClient
import javax.inject.Singleton

/**
 * **Placeholder — WP-6 / WP-9 replace this module.**
 *
 * The release counterpart of `DebugConnectionClientModule`. It exists so the release Hilt
 * graph resolves at all: `FakeConnectionClient` is not on this variant's classpath (R34),
 * and the generated `core/network/` client is not built yet, so the only binding available
 * is the refusing placeholder.
 *
 * See [UnavailableConnectionClient] for why it refuses instead of throwing, and for what
 * has to happen here when the real client lands.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface ReleaseConnectionClientModule {

    @Binds
    @Singleton
    fun bindConnectionClient(impl: UnavailableConnectionClient): ConnectionClient
}
