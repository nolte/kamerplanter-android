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
     * This builder — not the generated `Serializer.kotlinxSerializationJson` — is what the
     * app injects, so the app owns its parsing policy rather than inheriting whatever the
     * generator's template happens to set. Only the generator's [SerializersModule]
     * [Serializer] is adopted: the DTOs annotate temporal and decimal fields `@Contextual`,
     * and without those adapters every response carrying a date fails to deserialize.
     *
     * `ignoreUnknownKeys` is the setting actually carrying R-COMPAT-1 today.
     * `coerceInputValues` is declared because R-COMPAT-1 names it, but it changes nothing
     * here — and the reason matters, because it is not the obvious one. The models *do*
     * have properties it would normally apply to (18 nullable enum properties defaulting to
     * `null`, e.g. `PlantResponse.cultivationCycleType`). It is inert because every enum
     * property is `@Contextual`: coercion is keyed off the element descriptor's kind, and a
     * contextual descriptor is not `ENUM`, so the check is skipped.
     *
     * That distinction is load-bearing. Removing `@Contextual` from the enums — the obvious
     * fix for the limitation asserted in `GeneratedClientSerializationTest` — would switch
     * this flag on for those 18 fields, silently coercing an unknown enum value to `null`
     * instead of failing. Decide that deliberately, not as a side effect.
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
