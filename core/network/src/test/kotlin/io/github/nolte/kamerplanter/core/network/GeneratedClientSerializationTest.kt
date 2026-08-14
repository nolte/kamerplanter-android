package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.network.generated.models.CareDashboardEntryResponse
import io.github.nolte.kamerplanter.core.network.generated.models.CycleType
import io.github.nolte.kamerplanter.core.network.generated.models.ErrorResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PlantResponse
import io.github.nolte.kamerplanter.core.network.generated.models.ReminderType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
              "container_volume_liters": "12.50",
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
     * `coerceInputValues` cannot reach an enum here. The generator annotates every
     * enum-typed property `@Contextual`, and kotlinx.serialization only coerces an unknown
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
    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun `an unknown enum value from a newer backend still fails the whole response`() {
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
