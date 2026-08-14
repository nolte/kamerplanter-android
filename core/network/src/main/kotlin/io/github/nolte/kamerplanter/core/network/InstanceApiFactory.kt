package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Credential
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Field
import retrofit2.http.Part
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Retrofit interfaces aimed at one specific kamerplanter instance.
 *
 * A factory rather than an injected Retrofit singleton, because this app has no single base
 * URL: every user points it at their own self-hosted instance, and the connection flow has
 * to talk to an instance *before* anything about it is stored. Retrofit fixes its base URL
 * at build time, so the address is a parameter here rather than a constant anywhere.
 *
 * The [OkHttpClient] is shared across instances — it carries the connection and thread pools,
 * and building one per call would discard both.
 */
@Singleton
class InstanceApiFactory @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {

    /**
     * A Retrofit facade for [baseUrl], authenticated with [credential].
     *
     * [credential] is read per request rather than captured, so a session refreshed mid-flight
     * takes effect without rebuilding anything.
     */
    fun create(baseUrl: String, credential: () -> Credential = { Credential.None }): Retrofit {
        val client = httpClient.newBuilder()
            .addInterceptor(CredentialInterceptor(credential))
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.withTrailingSlash())
            .client(client)
            // Order matters, and getting it wrong fails only at runtime. The generated
            // multipart endpoints declare plain `@Part("language") language: String`
            // alongside the file part; Retrofit routes those through the request-body
            // converter chain, where its built-in converters only handle RequestBody
            // subtypes. With the JSON converter first, such a part is encoded as a JSON
            // string — `"de"`, quotes included, Content-Type application/json — and
            // FastAPI's Form(...) reads the quotes as part of the value. The scalar
            // converter has to get first refusal.
            .addConverterFactory(ScalarPartConverterFactory)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE.toMediaType()))
            .build()
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"

        /**
         * Retrofit rejects a base URL that does not end in `/`, and instance URLs are typed
         * by hand or scanned from a QR payload, so both spellings reach here.
         */
        fun String.withTrailingSlash(): String = if (endsWith("/")) this else "$this/"
    }
}

/**
 * Attaches the stored credential to every outgoing request (R21).
 *
 * A session and an API key travel differently — `Authorization: Bearer` versus the
 * `X-API-Key` header the backend's `McpApiKey` scheme declares — and light mode sends
 * neither, because an instance without accounts has nothing to authenticate.
 */
internal class CredentialInterceptor(
    private val credential: () -> Credential,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authenticated = when (val current = credential()) {
            is Credential.Session ->
                request.newBuilder()
                    .header("Authorization", "Bearer ${current.accessToken}")
                    .build()
            is Credential.ApiKey ->
                request.newBuilder()
                    .header("X-API-Key", current.key)
                    .build()
            Credential.None -> request
        }
        return chain.proceed(authenticated)
    }
}

/**
 * Encodes `@Part`/`@Field` values that are plain scalars as their bare `toString`, leaving
 * everything else to the converter behind it.
 *
 * Only [requestBodyConverter] is implemented: response bodies always go through the JSON
 * converter, and answering [responseBodyConverter] here would hijack every `String`-typed
 * response as a raw body instead of a JSON string.
 */
internal object ScalarPartConverterFactory : Converter.Factory() {

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? {
        if (parameterAnnotations.none { it is Part || it is Field }) return null
        if (type !in SCALAR_TYPES) return null
        return Converter<Any, RequestBody> { value -> value.toString().toRequestBody() }
    }

    private val SCALAR_TYPES: Set<Type> = setOf(
        String::class.java,
        Boolean::class.java, java.lang.Boolean::class.java,
        Int::class.java, Integer::class.java,
        Long::class.java, java.lang.Long::class.java,
        Double::class.java, java.lang.Double::class.java,
        Float::class.java, java.lang.Float::class.java,
    )
}
