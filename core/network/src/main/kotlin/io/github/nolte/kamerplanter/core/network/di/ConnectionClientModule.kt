package io.github.nolte.kamerplanter.core.network.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.core.connection.ConnectionClient
import io.github.nolte.kamerplanter.core.network.NetworkConnectionClient
import io.github.nolte.kamerplanter.core.network.NetworkPlantsClient
import io.github.nolte.kamerplanter.core.network.PlantsClient
import javax.inject.Singleton

/**
 * Binds this module's app-facing clients to their networking implementations.
 *
 * One binding for every build variant, replacing the debug/release split in
 * `:feature:settings` (R34). That split existed only because no implementation existed: the
 * fake kept the flow clickable in debug while release bound something that refused every
 * attempt. Both are gone, and with them the reason `:feature:settings` had to enable a
 * release unit-test variant to keep the two halves honest.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface ConnectionClientModule {

    @Binds
    @Singleton
    fun bindConnectionClient(impl: NetworkConnectionClient): ConnectionClient

    @Binds
    @Singleton
    fun bindPlantsClient(impl: NetworkPlantsClient): PlantsClient
}
