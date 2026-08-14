package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.network.generated.apis.AttachmentsApi
import io.github.nolte.kamerplanter.core.network.generated.apis.AuthApi
import io.github.nolte.kamerplanter.core.network.generated.apis.CareRemindersApi
import io.github.nolte.kamerplanter.core.network.generated.apis.HealthApi
import io.github.nolte.kamerplanter.core.network.generated.apis.LocationsApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PestDetectionApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PhasesApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantDiaryApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantInstancesApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PlantPhotosApi
import io.github.nolte.kamerplanter.core.network.generated.apis.TenantsApi
import io.github.nolte.kamerplanter.core.network.generated.apis.UsersApi
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Part
import java.io.File
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

    /**
     * Every generated API, not only the three that declare multipart today. Naming just the
     * multipart ones would let a widened tag filter introduce an uncovered `@Part` that this
     * test never looks at — `POST /api/v1/import/upload`, for instance, declares enum-typed
     * parts and sits one tag away from being generated.
     *
     * The list is hand-maintained because the classes cannot be enumerated without a
     * classpath scanner; `every generated api is listed above` is what keeps it honest.
     */
    private val generatedApis = listOf(
        AttachmentsApi::class.java,
        AuthApi::class.java,
        CareRemindersApi::class.java,
        HealthApi::class.java,
        LocationsApi::class.java,
        PestDetectionApi::class.java,
        PhasesApi::class.java,
        PlantDiaryApi::class.java,
        PlantInstancesApi::class.java,
        PlantPhotosApi::class.java,
        TenantsApi::class.java,
        UsersApi::class.java,
    )

    private fun partTypes(method: Method): List<Type> =
        method.genericParameterTypes.filterIndexed { index, _ ->
            method.parameterAnnotations[index].any { it is Part }
        }

    @Test
    fun `every part the generated client declares is either a file part or a covered scalar`() {
        val uncovered = generatedApis
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

    /**
     * Fails when the generated client grows an API this test does not know about, which is
     * the only way the list above can go stale. The count comes from the tag filter in
     * `OpenApiDocumentPreparer`, so widening that filter fails here first.
     */
    @Test
    fun `every generated api is listed above`() {
        val directory = File(GENERATED_APIS_PATH)
        // Asserted rather than tolerated: an empty read would make the comparison below pass
        // for the wrong reason, which is the failure mode this test exists to prevent.
        assertTrue("cannot read $GENERATED_APIS_PATH from ${File(".").absolutePath}", directory.isDirectory)

        val onDisk = directory.listFiles { file -> file.name.endsWith("Api.kt") }
            .orEmpty()
            .map { it.name.removeSuffix(".kt") }
            .sorted()
        assertTrue("no generated APIs found on disk", onDisk.isNotEmpty())

        assertEquals(
            "the generated client declares APIs this test does not cover; add them to " +
                "generatedApis so their @Part types are checked too",
            onDisk,
            generatedApis.map { it.simpleName }.sorted(),
        )
    }

    private companion object {
        const val GENERATED_APIS_PATH =
            "src/generated/kotlin/io/github/nolte/kamerplanter/core/network/generated/apis"
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
