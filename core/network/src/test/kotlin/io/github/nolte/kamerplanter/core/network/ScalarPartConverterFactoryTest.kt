package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.network.generated.apis.AttachmentsApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PestDetectionApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantPhotosApi
import okhttp3.MultipartBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Part
import java.lang.reflect.Method
import java.lang.reflect.Type

/**
 * Guards the converter's allowlist against the client it actually serves.
 *
 * The scalar converter exists because a plain `@Part` string routed through the JSON
 * converter arrives at FastAPI's `Form(...)` with its quotes attached. The allowlist covers
 * the types the generated client declares today; a schema bump introducing a `@Part` of some
 * other type would silently fall through to JSON and reintroduce that bug at runtime only.
 *
 * So rather than asserting the list against itself, this walks the generated APIs and fails
 * when a declared part type is neither a file part nor covered.
 */
class ScalarPartConverterFactoryTest {

    private val multipartApis = listOf(
        AttachmentsApi::class.java,
        PestDetectionApi::class.java,
        PlantPhotosApi::class.java,
    )

    private fun partTypes(method: Method): List<Type> =
        method.genericParameterTypes.filterIndexed { index, _ ->
            method.parameterAnnotations[index].any { it is Part }
        }

    @Test
    fun `every part the generated client declares is either a file part or a covered scalar`() {
        val uncovered = multipartApis
            .flatMap { it.methods.toList() }
            .flatMap(::partTypes)
            .filterNot { it == MultipartBody.Part::class.java }
            .filterNot { it in ScalarPartConverterFactory.SCALAR_TYPES }
            .distinct()

        assertTrue(
            "These @Part types are neither MultipartBody.Part nor in SCALAR_TYPES, so they " +
                "would be encoded as JSON and reach Form(...) with quotes: $uncovered",
            uncovered.isEmpty(),
        )
    }

    /** The converter must not claim body parameters — those belong to the JSON converter. */
    @Test
    fun `declines a parameter that is not a part`() {
        val converter = ScalarPartConverterFactory.requestBodyConverter(
            type = String::class.java,
            parameterAnnotations = emptyArray(),
            methodAnnotations = emptyArray(),
            retrofit = retrofit2.Retrofit.Builder().baseUrl("https://example.org/").build(),
        )

        assertTrue("a non-@Part parameter must fall through", converter == null)
    }
}
