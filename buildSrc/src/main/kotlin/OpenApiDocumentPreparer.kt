/**
 * Reduces the vendored kamerplanter OpenAPI document to the surface this app actually
 * binds against, and normalizes the two spots where the backend's OpenAPI 3.1 output and
 * openapi-generator disagree.
 *
 * Kept free of Gradle types on purpose so it can be unit-tested directly.
 *
 * ## Why prune at all
 *
 * The full document carries 578 paths, 783 operations and 875 schemas. Generating all of
 * it yields 30–60k lines of Kotlin, which turns every schema bump into a diff no reviewer
 * can meaningfully read — the opposite of what R-GEN-6 asks for. Selecting by tag keeps
 * the client at a reviewable size and makes the app's API surface an explicit, auditable
 * decision rather than a side effect of whatever the backend happens to expose.
 */
object OpenApiDocumentPreparer {

    /**
     * The tags this app binds against. Adding one is a deliberate decision: it widens the
     * generated client and the diff every future schema bump has to be reviewed against.
     *
     * `plant-photos` is separate from `plant-instances` upstream even though the photo
     * routes live under the `/plant-instances/{key}/photos` path — the plant list needs it
     * for row thumbnails and the diary needs it for uploads.
     */
    val KEEP_TAGS: Set<String> = setOf(
        "attachments",
        "auth",
        "care-reminders",
        "health",
        "locations",
        "pest-detection",
        "phases",
        "plant-diary",
        "plant-instances",
        "plant-photos",
        "tenants",
        "users",
    )

    /**
     * A cross-cutting query tag the backend stamps on all 496 `/t/{tenant_slug}/…` routes.
     * It is not a functional grouping, and leaving it on an operation would make
     * openapi-generator collapse everything carrying it into one giant `TenantScopedApi`.
     */
    private val DROP_TAGS = setOf("tenant-scoped")

    private val METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    private const val SCHEMA_REF_PREFIX = "#/components/schemas/"

    /** What [prepare] changed, so the Gradle task can report it and CI logs record it. */
    data class Summary(
        val paths: Int,
        val operations: Int,
        val schemas: Int,
        val binaryFieldsNormalized: Int,
        val cookieParametersRemoved: Int,
    )

    data class Result(val document: Map<String, Any?>, val summary: Summary)

    @Suppress("UNCHECKED_CAST")
    fun prepare(document: Map<String, Any?>): Result {
        val schemas = ((document["components"] as? Map<String, Any?>)
            ?.get("schemas") as? Map<String, Any?>).orEmpty()

        var cookieParametersRemoved = 0
        val keptPaths = linkedMapOf<String, Any?>()

        for ((path, rawItem) in (document["paths"] as? Map<String, Any?>).orEmpty()) {
            val item = rawItem as? Map<String, Any?> ?: continue
            val keptOperations = linkedMapOf<String, Any?>()

            for ((method, rawOperation) in item) {
                if (method.lowercase() !in METHODS) continue
                val operation = rawOperation as? Map<String, Any?> ?: continue
                val tags = (operation["tags"] as? List<*>).orEmpty().filterIsInstance<String>()
                if (tags.none { it in KEEP_TAGS }) continue

                val rewritten = LinkedHashMap(operation)
                // Cannot empty the list: the operation is only here because it carries a
                // KEEP_TAGS tag, and no KEEP_TAGS tag is in DROP_TAGS.
                rewritten["tags"] = tags.filterNot { it in DROP_TAGS }.sorted()

                // Retrofit has no cookie-parameter annotation. openapi-generator drops such
                // parameters but leaves the separator behind, emitting `fun refresh(, @Body …)`
                // — a syntax error, so they cannot simply be kept.
                //
                // All three occurrences are the optional `kp_refresh` cookie. `/auth/refresh`
                // also accepts the token in its body, which is the transport a QR-paired
                // device uses. `/auth/logout` and `/users/me/sessions` have no body at all,
                // so for a client without a cookie jar the credential has nowhere to go —
                // sign-out cannot revoke the refresh token server-side. That is an upstream
                // gap, not one this filter creates; whoever wires auth will need an OkHttp
                // interceptor that sets the Cookie header, or a backend body transport.
                val parameters = rewritten["parameters"] as? List<*>
                if (parameters != null) {
                    val withoutCookies = parameters.filter {
                        (it as? Map<String, Any?>)?.get("in") != "cookie"
                    }
                    cookieParametersRemoved += parameters.size - withoutCookies.size
                    rewritten["parameters"] = withoutCookies
                }
                keptOperations[method] = rewritten
            }

            if (keptOperations.isEmpty()) continue

            // Path-level members (shared `parameters`, `summary`, …) travel with the path —
            // with the same cookie filter applied, since a path-level cookie parameter
            // reaches every operation under it and would produce the same syntax error.
            val merged = linkedMapOf<String, Any?>()
            for ((key, value) in item) {
                if (key.lowercase() in METHODS) continue
                if (key == "parameters" && value is List<*>) {
                    val withoutCookies = value.filter {
                        (it as? Map<String, Any?>)?.get("in") != "cookie"
                    }
                    cookieParametersRemoved += value.size - withoutCookies.size
                    merged[key] = withoutCookies
                } else {
                    merged[key] = value
                }
            }
            merged.putAll(keptOperations)
            keptPaths[path] = merged
        }

        val reachable = reachableSchemas(keptPaths, schemas)

        val binaryCounter = intArrayOf(0)
        val normalizedPaths = normalizeBinaryFields(keptPaths, binaryCounter) as Map<String, Any?>
        val normalizedSchemas =
            normalizeBinaryFields(schemas.filterKeys { it in reachable }, binaryCounter)
                as Map<String, Any?>

        val components = LinkedHashMap((document["components"] as? Map<String, Any?>).orEmpty())
        components["schemas"] = normalizedSchemas

        val prepared = LinkedHashMap(document)
        prepared["paths"] = normalizedPaths
        prepared["components"] = components
        prepared["tags"] = (document["tags"] as? List<*>).orEmpty().filter {
            (it as? Map<String, Any?>)?.get("name") in KEEP_TAGS
        }

        val operationCount = normalizedPaths.values.sumOf { pathItem ->
            (pathItem as Map<String, Any?>).keys.count { it.lowercase() in METHODS }
        }

        return Result(
            document = prepared,
            summary = Summary(
                paths = normalizedPaths.size,
                operations = operationCount,
                schemas = normalizedSchemas.size,
                binaryFieldsNormalized = binaryCounter[0],
                cookieParametersRemoved = cookieParametersRemoved,
            ),
        )
    }

    /**
     * Transitive closure of `$ref` targets reachable from the kept paths. A schema pulled
     * in only by another kept schema has to survive too, or the generated code stops
     * compiling on a dangling reference.
     */
    private fun reachableSchemas(paths: Map<String, Any?>, schemas: Map<String, Any?>): Set<String> {
        val reachable = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        val unsupported = sortedSetOf<String>()

        fun collect(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    (node["\$ref"] as? String)?.let { ref ->
                        if (ref.startsWith(SCHEMA_REF_PREFIX)) {
                            pending.addLast(ref.removePrefix(SCHEMA_REF_PREFIX))
                        } else {
                            // Anything else — `#/components/responses/…`, `#/definitions/…`,
                            // an external `common.yaml#/Foo` — is a target this walk never
                            // enters. Schemas reachable *only* through such a node are
                            // therefore invisible to it and would be pruned while still
                            // referenced. Flag rather than guess.
                            unsupported += "unsupported \$ref $ref"
                        }
                    }
                    // Polymorphism hides subtypes from a `$ref` walk in two ways, and only
                    // one of them is safe to wave through:
                    //
                    //   mapping present      targets are plain strings the walk cannot see.
                    //   no mapping, no sibling oneOf/anyOf
                    //                        the `allOf` inheritance form: the discriminator
                    //                        sits on the parent and the subtypes point *at*
                    //                        it, so nothing reachable from here names them.
                    //                        The implicit mapping is "schema name == value",
                    //                        i.e. every schema in the document.
                    //   no mapping, sibling oneOf/anyOf
                    //                        safe — the subtypes are ordinary refs right
                    //                        there, which the walk already follows.
                    //
                    // v0.2.0 contains no discriminator at all; this guards the next bump.
                    (node["discriminator"] as? Map<*, *>)?.let { discriminator ->
                        val name = discriminator["propertyName"] ?: "<unnamed>"
                        // An empty union names no subtype, so it is no better than none.
                        val union = listOfNotNull(node["oneOf"], node["anyOf"])
                            .filterIsInstance<List<*>>()
                            .firstOrNull { it.isNotEmpty() }
                        if (discriminator.containsKey("mapping")) {
                            unsupported += "discriminator.mapping on $name"
                        } else if (union == null) {
                            unsupported += "discriminator without a usable oneOf/anyOf on $name"
                        }
                    }
                    node.values.forEach(::collect)
                }
                is List<*> -> node.forEach(::collect)
            }
        }

        collect(paths)
        while (pending.isNotEmpty()) {
            val name = pending.removeLast()
            if (!reachable.add(name)) continue
            schemas[name]?.let(::collect)
        }

        (reachable - schemas.keys).sorted().forEach { unsupported += "unresolved schema $it" }

        if (unsupported.isNotEmpty()) {
            error(
                "The schema uses constructs this preparer cannot resolve, so pruning would " +
                    "silently drop live definitions:\n" +
                    unsupported.joinToString("\n") { "  $it" } +
                    "\nExtend OpenApiDocumentPreparer.reachableSchemas before regenerating.",
            )
        }
        return reachable
    }

    /**
     * OpenAPI 3.1 spells a binary payload as `contentMediaType`; openapi-generator only
     * understands the 3.0 `format: binary`. Without this the four multipart upload
     * endpoints type their file parameter as `kotlin.String`, which no caller can supply an
     * image to — silently breaking exactly the microscope-capture upload this app exists
     * for. Rewriting produces `@Part file: MultipartBody.Part` instead.
     *
     * Returns a rewritten copy rather than mutating in place: the caller still holds the
     * parsed document, and silently editing nodes it shares would be the kind of aliasing
     * bug that only shows up as a puzzling diff much later.
     */
    private fun normalizeBinaryFields(node: Any?, count: IntArray): Any? = when (node) {
        is Map<*, *> -> {
            val copy = linkedMapOf<String, Any?>()
            val isBinary = node["type"] == "string" && node.containsKey("contentMediaType")
            for ((key, value) in node) {
                val name = key as? String ?: continue
                if (isBinary && name == "contentMediaType") continue
                copy[name] = normalizeBinaryFields(value, count)
            }
            if (isBinary) {
                copy["format"] = "binary"
                count[0]++
            }
            copy
        }
        is List<*> -> node.map { normalizeBinaryFields(it, count) }
        else -> node
    }
}
