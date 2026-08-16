package io.github.nolte.kamerplanter.core.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What actually goes on the wire.
 *
 * Written because it did not. `entry_type` carried a default value, this app's `Json` leaves
 * `encodeDefaults` off, and kotlinx therefore omitted the one field the endpoint requires —
 * so every diary entry the app wrote came back 422 from code that reads as correct. A default
 * value on a request body is invisible in review and invisible at compile time; it is only
 * visible here.
 */
class PlantActionRequestsTest {

    /** The app's own encoder settings, not a permissive one built for the test. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a diary write sends every field the endpoint requires`() {
        val body = json.encodeToString(
            NoteRequest(
                text = "Leaves look better",
                entryType = "note",
                photoRefs = emptyList(),
                captureEnvironment = true,
            ),
        )

        assertTrue("entry_type is required by the endpoint: $body", body.contains("\"entry_type\""))
        assertTrue("photo_refs must be stated: $body", body.contains("\"photo_refs\""))
        assertTrue(
            "capture_environment must be stated: $body",
            body.contains("\"capture_environment\""),
        )
        assertTrue(body.contains("\"text\""))
    }

    /** Empty is a value here, not an absence: it has to survive encoding as one. */
    @Test
    fun `an entry without photos still names the field`() {
        val body = json.encodeToString(
            NoteRequest("x", entryType = "note", photoRefs = emptyList(), captureEnvironment = false),
        )

        assertTrue(body.contains("\"photo_refs\":[]"))
        assertTrue(body.contains("\"capture_environment\":false"))
    }

    @Test
    fun `a care confirmation names its reminder type`() {
        val body = json.encodeToString(ConfirmRequest(reminderType = "watering"))

        assertTrue(body.contains("\"reminder_type\":\"watering\""))
    }
}
