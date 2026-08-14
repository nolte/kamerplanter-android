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
    private val tokenRefresh: TokenRefreshAuthenticator,
) {

    /**
     * A Retrofit facade for [baseUrl], authenticated with [credential].
     *
     * [credential] is read per request rather than captured, so a session refreshed mid-flight
     * takes effect without rebuilding anything — which is exactly what the authenticator does
     * when a call comes back 401.
     */
    fun create(baseUrl: String, credential: () -> Credential = { Credential.None }): Retrofit {
        val client = httpClient.newBuilder()
            .addInterceptor(CredentialInterceptor(credential))
            // Attached unconditionally: deciding here would mean calling `credential()` at
            // build time, which is a snapshot of something the KDoc above promises is read
            // per request — and a blocking store read on whichever thread built the client.
            // Which requests may be refreshed is decided per request instead, from the tag
            // the interceptor attaches.
            .authenticator(tokenRefresh)
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
    }
}

/**
 * Retrofit rejects a base URL that does not end in `/`, and instance URLs are typed by hand
 * or scanned from a QR payload, so both spellings reach the builders. Shared rather than
 * copied, so a future refinement — trimming whitespace, say — cannot apply to one caller and
 * not the other.
 */
internal fun String.withTrailingSlash(): String = if (endsWith("/")) this else "$this/"

/**
 * Attaches the stored credential to every outgoing request (R21).
 *
 * A session token and a `kp_sk_…` API key travel in the *same* `Authorization: Bearer`
 * header (R9). The schema's `BearerAuth` says so in as many words — "JWT access token or
 * `kp_`-prefixed service-account API key" — and 744 of the vendored document's 783 operations
 * declare it, 738 of them exclusively. The `X-API-Key` header belongs to a second scheme,
 * `McpApiKey`, which six routes accept and all six are under `/api/v1/mcp`. Sending a key
 * there therefore authenticates against almost nothing: every ordinary call would go out
 * unauthenticated and come back 401.
 *
 * Light mode sends neither header — an instance without accounts has nothing to
 * authenticate (R11).
 */
internal class CredentialInterceptor(
    private val credential: () -> Credential,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val current = credential()
        val token = when (current) {
            is Credential.Session -> current.accessToken
            is Credential.ApiKey -> current.key
            Credential.None -> null
        }
        val authenticated = token?.let {
            request.newBuilder()
                .header("Authorization", "Bearer $it")
                // Records which *kind* of credential signed this request, so the
                // authenticator can tell an expired session from a rejected API key. Reading
                // the store instead would answer a different question: what is stored now,
                // not what went out — and during a method change those differ.
                .tag(SignedWith::class.java, SignedWith(current is Credential.Session))
                .build()
        } ?: request
        return chain.proceed(authenticated)
    }
}

/**
 * Marks a request as signed by a session rather than by an API key.
 *
 * Only a session can be renewed. Without this the authenticator has to ask the credential
 * store what it holds *now*, which is the wrong question the moment the two differ — and
 * they differ exactly when a user with a stored session types in an API key: the rejected
 * key's 401 would be answered by handing the request the stored session's token, and an
 * invalid key would verify as good.
 */
internal data class SignedWith(val session: Boolean)

/**
 * Encodes a scalar `@Part` as its bare `toString`, leaving everything else to the converter
 * behind it.
 *
 * `@Part` only, not `@Field`: Retrofit resolves `@Field` values through `stringConverter`,
 * which never reaches this method, and its built-in `ToStringConverter` already handles them
 * correctly.
 *
 * Only [requestBodyConverter] is implemented. Answering `responseBodyConverter` here would
 * hijack every `String`-typed response as a raw body instead of a JSON string.
 *
 * The type list is an allowlist, which is the conservative direction but not a free one: a
 * future `@Part` typed as an enum, `UUID` or `BigDecimal` would fall through to the JSON
 * converter and arrive quote-wrapped — the very bug this class exists to prevent, again
 * visible only at runtime. `ScalarPartConverterFactoryTest` walks every generated API for
 * `@Part` types that are neither a file part nor covered here, and separately asserts that
 * it knows about every generated API, so a widened tag filter cannot slip one past it.
 */
internal object ScalarPartConverterFactory : Converter.Factory() {

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? {
        if (parameterAnnotations.none { it is Part }) return null
        if (type !in SCALAR_TYPES) return null
        return Converter<Any, RequestBody> { value -> value.toString().toRequestBody() }
    }

    /** Both the Kotlin primitives and their boxed forms: Retrofit hands over the boxed type. */
    val SCALAR_TYPES: Set<Type> = setOf(
        String::class.java,
        Boolean::class.java, Boolean::class.javaObjectType,
        Int::class.java, Int::class.javaObjectType,
        Long::class.java, Long::class.javaObjectType,
        Double::class.java, Double::class.javaObjectType,
        Float::class.java, Float::class.javaObjectType,
    )
}
