package io.github.nolte.kamerplanter.core.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.nolte.kamerplanter.core.network.generated.infrastructure.Serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.overwriteWith
import okhttp3.OkHttpClient
import java.math.BigDecimal
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
     * generator's template happens to set. The generator's [SerializersModule] [Serializer] is
     * adopted wholesale, with exactly one entry replaced below: its decimal adapter cannot read
     * what the backend sends, and dropping that override restores a defect that costs whole
     * responses rather than single fields.
     *
     * What the adopted module is actually load-bearing for is the temporal adapters — discard
     * it entirely and three tests fail, all of them on a `LocalDate` or an `OffsetDateTime`.
     * The `@Contextual` enums do *not* depend on it, which the test below named `decodes a
     * contextual enum that carries no adapter of its own` is there to say.
     *
     * `ignoreUnknownKeys` is the setting actually carrying R-COMPAT-1 today.
     * `coerceInputValues` is declared because R-COMPAT-1 names it, but it changes nothing
     * here — and the reason matters, because it is not the obvious one. The models *do*
     * have properties it would normally apply to (18 nullable enum properties defaulting to
     * `null`, e.g. `PlantResponse.cultivationCycleType`). It is inert because every enum
     * property coercion could apply to is `@Contextual`: coercion is keyed off the element
     * descriptor's kind, and a contextual descriptor is not `ENUM`, so the check is skipped.
     * (Two nested enums — `PlantPhotoAssessRequest.adapter` and the photo quality
     * `rating` — are plain `ENUM`, but both are required without a default, so coercion
     * never reaches them either. Give one a default and that stops being true.)
     *
     * That distinction is load-bearing. Removing `@Contextual` from the enums — the obvious
     * fix for the limitation asserted in `GeneratedClientSerializationTest` — would switch
     * this flag on for those 18 fields, silently coercing an unknown enum value to `null`
     * instead of failing. Decide that deliberately, not as a side effect.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The generator's adapters, with its decimal one replaced: that adapter reads only a
        // quoted decimal, and the backend sends bare JSON numbers, so every response carrying
        // one fails whole. See [DecimalWireFormat].
        serializersModule = Serializer.kotlinxSerializationAdapters
            .overwriteWith(SerializersModule { contextual(BigDecimal::class, DecimalWireFormat) })
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
