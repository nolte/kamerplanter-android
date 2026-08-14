import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preparer is the only hand-written transformation in the generation pipeline, and the
 * drift gate cannot cover it: `checkApiClientUpToDate` compares generator output against
 * generator output, so a preparer regression that quietly drops a schema shows up only as a
 * red diff — and the remedy it prints ("regenerate and commit") would bake the regression in.
 */
class OpenApiDocumentPreparerTest {

    private fun operation(vararg tags: String, parameters: List<Map<String, Any?>>? = null) =
        buildMap<String, Any?> {
            put("tags", tags.toList())
            put("responses", mapOf("200" to mapOf("content" to emptyMap<String, Any?>())))
            if (parameters != null) put("parameters", parameters)
        }

    private fun document(
        paths: Map<String, Any?>,
        schemas: Map<String, Any?> = emptyMap(),
        tags: List<Map<String, Any?>> = emptyList(),
    ) = mapOf(
        "openapi" to "3.1.0",
        "paths" to paths,
        "components" to mapOf("schemas" to schemas),
        "tags" to tags,
    )

    @Suppress("UNCHECKED_CAST")
    private fun paths(result: OpenApiDocumentPreparer.Result) =
        result.document["paths"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun schemas(result: OpenApiDocumentPreparer.Result) =
        (result.document["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>

    @Test
    fun `keeps only operations carrying a selected tag`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(
                mapOf(
                    "/kept" to mapOf("get" to operation("plant-instances")),
                    "/dropped" to mapOf("get" to operation("aquaponics")),
                ),
            ),
        )

        assertEquals(setOf("/kept"), paths(result).keys)
        assertEquals(1, result.summary.operations)
    }

    @Test
    fun `keeps a path when only one of its operations is selected`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(
                mapOf(
                    "/mixed" to mapOf(
                        "get" to operation("plant-instances"),
                        "delete" to operation("propagation"),
                    ),
                ),
            ),
        )

        val mixed = paths(result)["/mixed"] as Map<*, *>
        assertTrue(mixed.containsKey("get"))
        assertFalse(mixed.containsKey("delete"))
    }

    /** Otherwise 496 routes collapse into a single generated TenantScopedApi. */
    @Test
    fun `strips the cross-cutting tenant-scoped tag but keeps the functional one`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(mapOf("/p" to mapOf("get" to operation("plant-instances", "tenant-scoped")))),
        )

        val operation = (paths(result)["/p"] as Map<*, *>)["get"] as Map<*, *>
        assertEquals(listOf("plant-instances"), operation["tags"])
    }

    @Test
    fun `follows refs transitively into nested schemas`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(
                paths = mapOf(
                    "/p" to mapOf(
                        "get" to mapOf(
                            "tags" to listOf("plant-instances"),
                            "responses" to mapOf(
                                "200" to mapOf(
                                    "content" to mapOf(
                                        "application/json" to mapOf(
                                            "schema" to
                                                mapOf("\$ref" to "#/components/schemas/Root"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                schemas = mapOf(
                    "Root" to mapOf(
                        "properties" to mapOf(
                            "child" to mapOf("\$ref" to "#/components/schemas/Nested"),
                        ),
                    ),
                    "Nested" to mapOf("type" to "object"),
                    "Unreferenced" to mapOf("type" to "object"),
                ),
            ),
        )

        assertEquals(setOf("Root", "Nested"), schemas(result).keys)
    }

    /** Without this the four multipart uploads type their file parameter as a String. */
    @Test
    fun `rewrites the OpenAPI 3-1 binary spelling into format binary`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(
                paths = mapOf(
                    "/p" to mapOf(
                        "post" to mapOf(
                            "tags" to listOf("attachments"),
                            "requestBody" to mapOf(
                                "content" to mapOf(
                                    "multipart/form-data" to mapOf(
                                        "schema" to
                                            mapOf("\$ref" to "#/components/schemas/Body"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                schemas = mapOf(
                    "Body" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "file" to mapOf(
                                "type" to "string",
                                "contentMediaType" to "application/octet-stream",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val file = (
            (schemas(result)["Body"] as Map<*, *>)["properties"] as Map<*, *>
            )["file"] as Map<*, *>
        assertEquals("binary", file["format"])
        assertFalse(file.containsKey("contentMediaType"))
        assertEquals(1, result.summary.binaryFieldsNormalized)
    }

    /**
     * Guards the other half of the binary rewrite. Without a string field actually present
     * this asserts nothing: dropping the `contentMediaType` condition would turn every
     * string in the schema into `format: binary`, and a fixture with no strings stays green.
     */
    @Test
    fun `leaves a string field without contentMediaType alone`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(
                paths = mapOf(
                    "/p" to mapOf(
                        "get" to mapOf(
                            "tags" to listOf("health"),
                            "responses" to mapOf(
                                "200" to mapOf(
                                    "content" to mapOf(
                                        "application/json" to mapOf(
                                            "schema" to
                                                mapOf("\$ref" to "#/components/schemas/Body"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                schemas = mapOf(
                    "Body" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("name" to mapOf("type" to "string")),
                    ),
                ),
            ),
        )

        val name = (
            (schemas(result)["Body"] as Map<*, *>)["properties"] as Map<*, *>
            )["name"] as Map<*, *>
        assertEquals("string", name["type"])
        assertFalse(name.containsKey("format"))
        assertEquals(0, result.summary.binaryFieldsNormalized)
    }

    /** A discriminator without a mapping resolves through ordinary refs — must not fail. */
    @Test
    fun `accepts a discriminator that carries no mapping`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(
                paths = mapOf(
                    "/p" to mapOf(
                        "get" to mapOf(
                            "tags" to listOf("plant-instances"),
                            "responses" to mapOf(
                                "200" to mapOf(
                                    "content" to mapOf(
                                        "application/json" to mapOf(
                                            "schema" to mapOf(
                                                "oneOf" to listOf(
                                                    mapOf("\$ref" to "#/components/schemas/A"),
                                                ),
                                                "discriminator" to mapOf("propertyName" to "kind"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                schemas = mapOf("A" to mapOf("type" to "object")),
            ),
        )

        assertEquals(setOf("A"), schemas(result).keys)
    }

    /**
     * The `allOf` inheritance form: the discriminator sits on the parent and the subtypes
     * point at it, so nothing reachable from the parent names them. Narrowing the guard to
     * `mapping` alone made this case pass silently and prune `Cat`/`Dog` away.
     */
    @Test
    fun `fails loudly on a discriminator whose subtypes inherit via allOf`() {
        val failure = runCatching {
            OpenApiDocumentPreparer.prepare(
                document(
                    paths = mapOf(
                        "/p" to mapOf(
                            "get" to mapOf(
                                "tags" to listOf("plant-instances"),
                                "responses" to mapOf(
                                    "200" to mapOf(
                                        "content" to mapOf(
                                            "application/json" to mapOf(
                                                "schema" to
                                                    mapOf("\$ref" to "#/components/schemas/Pet"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    schemas = mapOf(
                        "Pet" to mapOf(
                            "type" to "object",
                            "discriminator" to mapOf("propertyName" to "petType"),
                        ),
                        "Cat" to mapOf("allOf" to listOf(mapOf("\$ref" to "#/components/schemas/Pet"))),
                        "Dog" to mapOf("allOf" to listOf(mapOf("\$ref" to "#/components/schemas/Pet"))),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            failure?.message.orEmpty()
                .contains("discriminator without a usable oneOf/anyOf on petType"),
        )
    }

    /** An empty union names no subtype, so it is worth no more than an absent one. */
    @Test
    fun `treats an empty union beside a discriminator as no union at all`() {
        val failure = runCatching {
            OpenApiDocumentPreparer.prepare(
                document(
                    paths = mapOf(
                        "/p" to mapOf(
                            "get" to mapOf(
                                "tags" to listOf("plant-instances"),
                                "responses" to mapOf(
                                    "200" to mapOf(
                                        "content" to mapOf(
                                            "application/json" to mapOf(
                                                "schema" to mapOf(
                                                    "oneOf" to emptyList<Any?>(),
                                                    "discriminator" to mapOf("propertyName" to "kind"),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            failure?.message.orEmpty()
                .contains("discriminator without a usable oneOf/anyOf on kind"),
        )
    }

    /**
     * The twin of the test above, pinning the damage rather than the guard. Without it that
     * test would pass identically on a fixture with no subtypes at all, so it would not
     * actually show what the guard is protecting.
     */
    @Test
    fun `subtypes attached only by allOf are unreachable without the guard`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(
                paths = mapOf(
                    "/p" to mapOf(
                        "get" to mapOf(
                            "tags" to listOf("plant-instances"),
                            "responses" to mapOf(
                                "200" to mapOf(
                                    "content" to mapOf(
                                        "application/json" to mapOf(
                                            "schema" to
                                                mapOf("\$ref" to "#/components/schemas/Pet"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                // Same shape as above minus the discriminator, so the guard stays silent and
                // the reachability walk speaks for itself.
                schemas = mapOf(
                    "Pet" to mapOf("type" to "object"),
                    "Cat" to mapOf("allOf" to listOf(mapOf("\$ref" to "#/components/schemas/Pet"))),
                    "Dog" to mapOf("allOf" to listOf(mapOf("\$ref" to "#/components/schemas/Pet"))),
                ),
            ),
        )

        assertEquals(setOf("Pet"), schemas(result).keys)
    }

    @Test
    fun `fails loudly on a ref into a component section it does not walk`() {
        val failure = runCatching {
            OpenApiDocumentPreparer.prepare(
                document(
                    paths = mapOf(
                        "/p" to mapOf(
                            "get" to mapOf(
                                "tags" to listOf("plant-instances"),
                                "responses" to mapOf(
                                    "200" to mapOf("\$ref" to "#/components/responses/Shared"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(
            failure?.message.orEmpty().contains("unsupported \$ref #/components/responses/Shared"),
        )
    }

    /** Retrofit has no cookie annotation; a leftover one emits `fun f(, @Body …)`. */
    @Test
    fun `removes cookie parameters at operation and path level`() {
        val cookie = mapOf<String, Any?>("in" to "cookie", "name" to "kp_refresh")
        val query = mapOf<String, Any?>("in" to "query", "name" to "limit")

        val result = OpenApiDocumentPreparer.prepare(
            document(
                mapOf(
                    "/p" to mapOf(
                        "parameters" to listOf(cookie, query),
                        "get" to operation("auth", parameters = listOf(cookie, query)),
                    ),
                ),
            ),
        )

        val path = paths(result)["/p"] as Map<*, *>
        assertEquals(listOf(query), path["parameters"])
        assertEquals(listOf(query), (path["get"] as Map<*, *>)["parameters"])
        assertEquals(2, result.summary.cookieParametersRemoved)
    }

    @Test
    fun `fails loudly on a discriminator mapping rather than pruning its subtypes away`() {
        val failure = runCatching {
            OpenApiDocumentPreparer.prepare(
                document(
                    paths = mapOf(
                        "/p" to mapOf(
                            "get" to mapOf(
                                "tags" to listOf("plant-instances"),
                                "responses" to mapOf(
                                    "200" to mapOf(
                                        "content" to mapOf(
                                            "application/json" to mapOf(
                                                "schema" to mapOf(
                                                    "discriminator" to mapOf(
                                                        "propertyName" to "kind",
                                                        // Names its target as a plain string,
                                                        // invisible to the $ref walk.
                                                        "mapping" to mapOf(
                                                            "leaf" to
                                                                "#/components/schemas/Leaf",
                                                        ),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("discriminator.mapping on kind"))
    }

    @Test
    fun `fails loudly on a ref it cannot resolve`() {
        val failure = runCatching {
            OpenApiDocumentPreparer.prepare(
                document(
                    paths = mapOf(
                        "/p" to mapOf(
                            "get" to mapOf(
                                "tags" to listOf("plant-instances"),
                                "responses" to mapOf(
                                    "200" to mapOf(
                                        "content" to mapOf(
                                            "application/json" to mapOf(
                                                "schema" to
                                                    mapOf("\$ref" to "#/components/schemas/Gone"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("unresolved schema Gone"))
    }

    @Test
    fun `narrows the top-level tag list to the selected tags`() {
        val result = OpenApiDocumentPreparer.prepare(
            document(
                paths = mapOf("/p" to mapOf("get" to operation("health"))),
                tags = listOf(mapOf("name" to "health"), mapOf("name" to "aquaponics")),
            ),
        )

        assertEquals(listOf(mapOf("name" to "health")), result.document["tags"])
    }
}
