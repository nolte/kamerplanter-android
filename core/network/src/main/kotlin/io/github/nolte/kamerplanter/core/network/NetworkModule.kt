package io.github.nolte.kamerplanter.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.core.network.generated.infrastructure.Serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Lenient by design: the app must not crash when the self-hosted kamerplanter
     * backend is newer than the generated client and returns additional fields.
     *
     * This builder — not the generated `Serializer.kotlinxSerializationJson` — is the one
     * the app uses, because the generated one omits `coerceInputValues`, which R-COMPAT-1
     * makes binding. Only the generator's [SerializersModule][Serializer] is adopted: the
     * DTOs annotate temporal and decimal fields `@Contextual`, and without those adapters
     * every response carrying a date fails to deserialize.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        serializersModule = Serializer.kotlinxSerializationAdapters
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}
