package io.github.nolte.kamerplanter.feature.settings.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.feature.settings.ConnectionClient
import io.github.nolte.kamerplanter.feature.settings.FakeConnectionClient
import javax.inject.Singleton

/**
 * The debug variant's [ConnectionClient]: the backend-free fake, so the whole connection
 * flow stays clickable without a reachable kamerplanter instance (R34).
 *
 * This module and [FakeConnectionClient] both live in `src/debug/`, so the release variant
 * does not merely leave the fake unbound — it never compiles it, and cannot bind what it
 * does not have.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface DebugConnectionClientModule {

    @Binds
    @Singleton
    fun bindConnectionClient(impl: FakeConnectionClient): ConnectionClient
}
