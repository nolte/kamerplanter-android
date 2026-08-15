package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.network.generated.models.CareDashboardEntryResponse
import io.github.nolte.kamerplanter.core.network.generated.models.CycleType
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.LocationCreate
import io.github.nolte.kamerplanter.core.network.generated.models.LocationResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ReminderType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Exercises the generated DTOs through the app's own [Json] instance.
 *
 * The generator annotates temporal, decimal and enum fields `@Contextual`, which routes
 * them through the serializers module rather than through their own serializer. Nothing
 * about that is visible at compile time — a missing adapter surfaces only as a runtime
 * `SerializationException` on the first response that carries the field. These tests are
 * what stands between that and a release build.
 */
class GeneratedClientSerializationTest {

    private val json: Json = NetworkModule.provideJson()

    @Test
    fun `decodes the contextual temporal, decimal and enum fields of a plant instance`() {
        val decoded = json.decodeFromString<PlantResponse>(
            """
            {
              "cultivar_key": null,
              "instance_id": "1f0b2c",
              "key": "plant-7",
              "plant_name": "Monstera",
              "planted_on": "2026-03-14",
              "removed_on": null,
              "slot_key": null,
              "species_key": "monstera-deliciosa",
              "substrate_batch_key": null,
              "container_volume_liters": 12.50,
              "created_at": "2026-03-14T09:15:00Z",
              "cultivation_cycle_type": "perennial"
            }
            """.trimIndent(),
        )

        assertEquals("plant-7", decoded.key)
        assertEquals(LocalDate.of(2026, 3, 14), decoded.plantedOn)
        assertEquals(BigDecimal("12.50"), decoded.containerVolumeLiters)
        assertEquals(OffsetDateTime.parse("2026-03-14T09:15:00Z"), decoded.createdAt)
        assertEquals(CycleType.perennial, decoded.cultivationCycleType)
        assertNull(decoded.removedOn)
    }

    /**
     * The spelling above is the one that matters, and it is not the one the generator built
     * for. Its `BigDecimalAdapter` calls `decodeString()`, so it accepts `"12.50"` and only
     * that — while the backend types these fields as Python `float` and FastAPI writes them
     * as bare JSON numbers. Every `number` in the schema is affected: twenty generated models
     * carry one, including `LocationResponse.area_m2`, which is required.
     *
     * The failure is total rather than partial — kotlinx fails the document, not the field —
     * so one decimal costs the whole response. Both spellings are pinned here: the number
     * because it is what the backend sends, the string because dropping support for it would
     * be a silent break the moment an endpoint answers with one.
     */
    @Test
    fun `decodes a decimal whether the backend quotes it or not`() {
        fun areaOf(spelling: String) = json.decodeFromString<LocationResponse>(
            """
            {
              "key": "loc-1",
              "name": "Living room",
              "site_key": "site-1",
              "area_m2": $spelling,
              "dimensions": [],
              "irrigation_system": "manual",
              "light_type": "natural",
              "orientation": null
            }
            """.trimIndent(),
        ).areaM2

        assertEquals(BigDecimal("12.50"), areaOf("12.50"))
        assertEquals(BigDecimal("12.50"), areaOf("\"12.50\""))
    }

    /**
     * The other direction, for the request bodies that carry a decimal.
     *
     * FastAPI's `float` fields accept a numeric string too, so the unquoted spelling is not
     * load-bearing today — but a JSON number is what the schema declares, and a client that
     * sends strings where the schema says number is one backend validation setting away from
     * being rejected.
     *
     * Exactness is the half that *is* load-bearing. Writing through a plain numeric primitive
     * parses the literal back into a `Double` on the way out, so a value this client read
     * exactly and sent straight back would go out quietly rounded.
     */
    @Test
    fun `encodes a decimal as a bare JSON number`() {
        assertTrue(encodedArea("12.50"), encodedArea("12.50").contains("\"area_m2\":12.50,"))
    }

    /** Past a Double's ~17 significant digits, where a numeric round trip would round it away. */
    @Test
    fun `encodes a decimal past a double's precision without rounding it`() {
        val precise = "1.234567890123456789012345"

        assertTrue(encodedArea(precise), encodedArea(precise).contains("\"area_m2\":$precise,"))
    }

    /**
     * A magnitude no `Double` can hold, written as an exponent rather than spelled out.
     *
     * Both halves matter. The old encoder threw outright on this — it parsed the literal into a
     * `Double` and got `Infinity` — so a value an instance sent could not be sent back. And
     * spelling it out in full would turn a nine-byte token into a four-hundred-digit one, which
     * for a large enough exponent is an `OutOfMemoryError` rather than a failed request.
     */
    @Test
    fun `encodes a magnitude beyond a double as a short exponent`() {
        val encoded = encodedArea("1E+400")

        assertTrue(encoded, encoded.contains("\"area_m2\":1E+400,"))
        // Still a JSON number, not a string and not a broken document — asserted on the
        // re-parsed token so the exponent form itself is pinned, not merely the value.
        assertEquals("1E+400", json.parseToJsonElement(encoded).areaText())
    }

    /**
     * The nullable half of the same field.
     *
     * `container_volume_liters` is `float | None` upstream, so an unset one arrives as JSON
     * `null` — and `JsonNull` *is* a `JsonPrimitive`, whose `content` reads `"null"`. It never
     * reaches [DecimalWireFormat] today because kotlinx short-circuits a nullable element
     * before consulting the serializer, but nothing in this module says so, and the field
     * became load-bearing the moment this PR started exercising it.
     */
    @Test
    fun `a null decimal stays null rather than reaching the decimal serializer`() {
        fun volumeOf(field: String) = json.decodeFromString<PlantResponse>(
            """
            {
              "cultivar_key": null,
              "instance_id": "1f0b2c",
              "key": "plant-7",
              "plant_name": "Monstera",
              "planted_on": "2026-03-14",
              "removed_on": null,
              "slot_key": null,
              "species_key": "monstera-deliciosa",
              "substrate_batch_key": null$field
            }
            """.trimIndent(),
        ).containerVolumeLiters

        assertNull("an explicit null", volumeOf(",\n  \"container_volume_liters\": null"))
        assertNull("an older backend omitting the field", volumeOf(""))
    }

    private fun encodedArea(area: String) = json.encodeToString(
        LocationCreate(areaM2 = BigDecimal(area), name = "Living room", siteKey = "site-1"),
    )

    private fun JsonElement.areaText(): String =
        jsonObject.getValue("area_m2").jsonPrimitive.content

    @Test
    fun `decodes a contextual enum that carries no adapter of its own`() {
        val decoded = json.decodeFromString<CareDashboardEntryResponse>(
            """
            {
              "care_profile_key": "cp-1",
              "plant_key": "plant-7",
              "plant_name": "Monstera",
              "reminder_type": "watering",
              "urgency": "due"
            }
            """.trimIndent(),
        )

        assertEquals(ReminderType.watering, decoded.reminderType)
        assertNull(decoded.dueDate)
    }

    @Test
    fun `decodes the free-form detail map of an error response`() {
        val decoded = json.decodeFromString<ErrorResponse>(
            """
            {
              "error_code": "validation_error",
              "error_id": "err-42",
              "message": "The input data is invalid.",
              "method": "POST",
              "path": "/api/v1/t/acme/plant-instances",
              "timestamp": "2026-03-14T09:15:00Z",
              "details": [{"field": "plant_name", "issue": "required"}]
            }
            """.trimIndent(),
        )

        assertEquals("validation_error", decoded.errorCode)
        assertEquals(1, decoded.details?.size)
    }

    /** R-COMPAT-1: a server newer than this client must not crash it. */
    @Test
    fun `ignores fields a newer backend adds`() {
        val decoded = json.decodeFromString<CareDashboardEntryResponse>(
            """
            {
              "care_profile_key": "cp-1",
              "plant_key": "plant-7",
              "plant_name": "Monstera",
              "reminder_type": "watering",
              "urgency": "due",
              "added_in_a_future_backend": {"nested": [1, 2, 3]}
            }
            """.trimIndent(),
        )

        assertEquals("plant-7", decoded.plantKey)
    }

    /**
     * Documents a real gap in R-COMPAT-1, deliberately asserted rather than fixed.
     *
     * `coerceInputValues` cannot reach an enum here. The generator annotates the enum-typed
     * properties `@Contextual`, and kotlinx.serialization only coerces an unknown
     * enum name when the element descriptor's kind is `ENUM` — for a contextual property it
     * is `CONTEXTUAL`, so the check is skipped and the enum's own serializer throws. On a
     * required property there is also nothing to coerce *to*: no null, no declared default.
     *
     * Consequence: a backend one release ahead that adds a `ReminderType` value fails the
     * whole care-dashboard response, not just the affected entry. Closing this needs either
     * a custom generator template that gives every enum an unknown fallback, or
     * entry-level error handling in the repositories — a decision that belongs with the
     * feature work, not with client generation. Kept as a test so it cannot be forgotten.
     */
    @Test
    fun `an unknown enum value from a newer backend still fails the whole response`() {
        val thrown = assertThrows(SerializationException::class.java) {
            json.decodeFromString<CareDashboardEntryResponse>(
                """
                {
                  "care_profile_key": "cp-1",
                  "plant_key": "plant-7",
                  "plant_name": "Monstera",
                  "reminder_type": "invented_in_a_future_release",
                  "urgency": "due"
                }
                """.trimIndent(),
            )
        }

        // Asserted on the message, not just the type, because `SerializationException` has
        // subclasses this payload could plausibly start throwing instead — a type-only
        // expectation would stay green while it had stopped pinning enum behaviour at all.
        //
        // Both halves are needed, and they fail differently. The value alone is not enough:
        // a `JsonDecodingException` appends the offending JSON to its message, so any
        // unrelated failure on this payload would contain the string too. The phrase is the
        // fragile half — it is kotlinx.serialization's wording, so a library bump that
        // rephrases it turns this red while nothing about the behaviour changed. Read a
        // failure here against the library version before reading it as a regression.
        val message = thrown.message.orEmpty()
        assertTrue(
            "expected an unknown-enum-element failure, but was: $message",
            message.contains("does not contain element with name") &&
                message.contains("invented_in_a_future_release"),
        )
    }

    /** R-COMPAT-2: an older server omitting newly added optional fields still decodes. */
    @Test
    fun `defaults the optional fields an older backend omits`() {
        val decoded = json.decodeFromString<PlantResponse>(
            """
            {
              "cultivar_key": null,
              "instance_id": "1f0b2c",
              "key": "plant-7",
              "plant_name": "Monstera",
              "planted_on": "2026-03-14",
              "removed_on": null,
              "slot_key": null,
              "species_key": "monstera-deliciosa",
              "substrate_batch_key": null
            }
            """.trimIndent(),
        )

        assertNull(decoded.createdAt)
        assertNull(decoded.containerVolumeLiters)
        assertNull(decoded.cultivationCycleType)
    }
}
