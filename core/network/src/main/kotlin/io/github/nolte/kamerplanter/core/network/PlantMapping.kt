package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.network.generated.models.PlantResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * What both plant clients have to agree about.
 *
 * The list and a single plant's page read the same fields off the same endpoints — the name
 * that falls back to an instance id, the species with its cultivar, an attachment URI made
 * absolute, a care dashboard entry, and which of several open reminders is the pressing one.
 * A second copy of any of these is a place for the row and the page to start disagreeing
 * about the plant the user has just tapped.
 */

/**
 * One dashboard entry, or `null` where it cannot be read.
 *
 * Hand-parsed rather than deserialized so a `reminder_type` this build does not know
 * costs one badge instead of every badge in the tenant. `plant_key`, `reminder_type`
 * and `urgency` are the fields a row needs; an entry missing any of them is not
 * usable and is dropped.
 */
internal fun JsonElement.asCareAction(): Pair<String, CareAction>? {
    val fields = this as? JsonObject ?: return null
    val plantKey = fields.text("plant_key") ?: return null
    val kind = fields.text("reminder_type") ?: return null
    val urgency = fields.text("urgency") ?: return null
    return plantKey to CareAction(kind = kind, urgency = urgency, dueDate = fields.text("due_date"))
}

/**
 * Overdue before upcoming, then the earliest due date.
 *
 * Ordered on the urgency the backend assigns rather than on the date alone: an entry
 * without a date must still rank, and "overdue" is the backend's judgement, not
 * something to re-derive from a date this app cannot see the schedule behind.
 */
internal fun List<CareAction>.mostPressing(): CareAction =
    minWithOrNull(
        compareBy<CareAction> { if (it.urgency == URGENCY_OVERDUE) 0 else 1 }
            .thenBy { it.dueDate ?: "9999-99-99" },
    ) ?: first()

/**
 * A string field, or `null` when it is absent or is not a string.
 *
 * File-private rather than shared: `InstanceError` and the diary reader each have their own,
 * and one visible helper called `text` would collide with both.
 */
private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `plant_name` is nullable, and a blank one is as unusable as a missing one. */
internal fun PlantResponse.displayName(): String =
    plantName?.takeIf { it.isNotBlank() } ?: instanceId

/** Common name first — that is what the owner calls it — with the cultivar appended. */
internal fun PlantResponse.speciesLabel(): String? {
    val base = species?.commonNames?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: species?.scientificName
        ?: return cultivar?.name
    return cultivar?.name?.let { "$base '$it'" } ?: base
}

/**
 * Thumbnail URIs may come back relative to the instance. Coil needs an absolute URL,
 * and prefixing one that is already absolute would produce a broken address.
 */
internal fun absoluteAgainst(baseUrl: String, uri: String): String = when {
    uri.startsWith("http://") || uri.startsWith("https://") -> uri
    else -> baseUrl.trimEnd('/') + "/" + uri.trimStart('/')
}
