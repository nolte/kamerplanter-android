package io.github.nolte.kamerplanter.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The instance's error envelope, in a sentence.
 *
 * kamerplanter wraps every failure in its own shape rather than returning FastAPI's
 * raw `detail`:
 *
 * ```
 * {"error_code": "VALIDATION_ERROR", "message": "The input data is invalid.",
 *  "details": [{"field": "body.text", "reason": "Field required"}]}
 * ```
 *
 * `message` alone is often the same sentence for every validation failure, so the fields are
 * what carry the information — and reading the wrong key is how a 422 that names the offending
 * field arrived on screen as "no reason given". A detail entry spells its explanation `reason`
 * when the framework rejected the request and `message` when a backend rule did; both are read,
 * because a field name with no explanation after it is worse than either half alone.
 *
 * The `detail` fallback stays for anything that reaches the client without passing
 * through the envelope, which is what a framework-level rejection looks like.
 */
internal fun String?.instanceErrorDetail(): String? {
    val body = runCatching { Json.parseToJsonElement(this.orEmpty()) }
        .getOrNull() as? JsonObject
        ?: return null
    val fields = (body["details"] as? JsonArray)
        ?.mapNotNull { entry ->
            val detail = entry as? JsonObject ?: return@mapNotNull null
            // `body.text` — the prefix names the part of the request, which the reader
            // already knows, and only makes the sentence longer.
            val field = detail.text("field")?.substringAfterLast('.')
            // Two keys for the same thing: the framework's own validation writes `reason`, a
            // rule the backend enforces itself writes `message`. Reading only the first put
            // "photo_refs" on screen with nothing after it — a field name and a silence.
            val why = detail.text("reason") ?: detail.text("message")
            // A field with no explanation is dropped, not reported alone. "photo_refs" by
            // itself is the failure mode this whole function was rewritten to end, and
            // keeping it here would suppress the envelope's own `message` below — trading a
            // sentence that says something for a word that does not.
            why?.let { listOfNotNull(field, it).joinToString(": ") }
        }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val message = body.text("message")
    return when {
        fields.isNotEmpty() -> fields.joinToString("; ")
        message != null -> message
        else -> body.rawDetail()
    }
}

/**
 * The envelope's `error_code`, or `null` when the body is not one.
 *
 * Parsed leniently on purpose: a reverse proxy in front of the instance can answer a 403
 * with HTML, and the call must fail as itself rather than as a parse error.
 */
internal fun String?.instanceErrorCode(): String? =
    (runCatching { Json.parseToJsonElement(this.orEmpty()) }.getOrNull() as? JsonObject)?.text("error_code")

/** FastAPI's own shape, for a failure that never reached the envelope. */
private fun JsonObject.rawDetail(): String? {
    val detail = this["detail"] ?: return null
    (detail as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    return (detail as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.text("msg") }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString("; ")
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
