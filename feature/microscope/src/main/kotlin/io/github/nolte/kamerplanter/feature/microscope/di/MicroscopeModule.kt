package io.github.nolte.kamerplanter.feature.microscope.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.feature.microscope.AusbcMicroscopeCamera
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeCamera
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface MicroscopeModule {

    @Binds
    @Singleton
    fun bindMicroscopeCamera(impl: AusbcMicroscopeCamera): MicroscopeCamera
}
