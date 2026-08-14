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

    // NOTE for whoever wires the Retrofit instance next: the generated multipart endpoints
    // declare plain `@Part("language") language: String` alongside the file part. Retrofit
    // routes those through the request-body converter chain, and its built-in converters
    // only handle RequestBody subtypes there — so with the kotlinx.serialization converter
    // as the only one registered, the part is encoded as a JSON string: `"de"` with quotes,
    // Content-Type application/json, which FastAPI's Form(...) then reads including the
    // quotes. A scalar converter factory has to sit ahead of the JSON one. This bites the
    // pest-detection upload (#10) and attachment upload, i.e. the driving use case (#1).
}
