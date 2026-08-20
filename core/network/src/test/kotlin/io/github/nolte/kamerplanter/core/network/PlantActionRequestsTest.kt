package io.github.nolte.kamerplanter.core.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
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

    /**
     * An update must not carry `capture_environment`.
     *
     * The field asks the server to *look* at its sensors, which only a new entry can be asked:
     * the readings on an existing entry describe the moment it was written, and re-sending the
     * flag on a typo fix would ask the instance to re-stamp it with today's weather.
     */
    @Test
    fun `an update omits the field only a new entry may carry`() {
        val body = json.encodeToString(
            NoteRequest(
                text = "Fixed a typo",
                entryType = "note",
                photoRefs = listOf("a1"),
                captureEnvironment = null,
            ),
        )

        assertFalse("capture_environment must not travel on an update: $body", body.contains("capture_environment"))
        assertTrue("the photos it already had must stay: $body", body.contains("a1"))
    }

    /** A title the writer left blank is absent, not an empty string the instance would store. */
    @Test
    fun `a blank title is not sent at all`() {
        val body = json.encodeToString(
            NoteRequest(
                text = "No title on this one",
                title = null,
                entryType = "note",
                photoRefs = emptyList(),
                captureEnvironment = true,
            ),
        )

        assertFalse("an absent title must not be encoded: $body", body.contains("\"title\""))
    }
}
