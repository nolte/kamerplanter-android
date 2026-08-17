package io.github.nolte.kamerplanter.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bodies in these tests are what a running kamerplanter instance actually returned, copied
 * verbatim from `POST …/diary` and `POST …/photos`.
 *
 * Written after this parser read the wrong key twice over. It first looked for FastAPI's raw
 * `detail`, which this backend does not send — so a 422 that named the offending field reached
 * the user as "no reason given", and a one-line contract violation cost an afternoon. A parser
 * whose only job is to explain a failure is worth pinning to a real failure.
 */
class InstanceErrorTest {

    @Test
    fun `names the fields a validation failure objected to`() {
        val body = """
            {"error_id":"err_a2020b20","error_code":"VALIDATION_ERROR",
             "message":"The input data is invalid.",
             "details":[{"field":"body.entry_type","reason":"Field required","code":"missing"},
                        {"field":"body.text","reason":"Field required","code":"missing"}],
             "timestamp":"2026-08-16T11:15:37Z","path":"/api/v1/…/diary","method":"POST"}
        """.trimIndent()

        // The `body.` prefix is dropped: it names the part of the request the reader is
        // already holding, and only makes the sentence longer.
        assertEquals(
            "entry_type: Field required; text: Field required",
            body.instanceErrorDetail(),
        )
    }

    @Test
    fun `names the field a rejected upload objected to`() {
        val body = """
            {"error_id":"err_36de5696","error_code":"VALIDATION_ERROR",
             "message":"The input data is invalid.",
             "details":[{"field":"body.file","reason":"Field required","code":"missing"}],
             "timestamp":"2026-08-16T11:15:37Z","path":"/api/v1/…/photos","method":"POST"}
        """.trimIndent()

        assertEquals("file: Field required", body.instanceErrorDetail())
    }

    /**
     * A rule the backend enforces itself spells its explanation `message`, not `reason`.
     *
     * Reading only `reason` put "photo_refs" on screen with nothing after it — a field name
     * and a silence, which is less use than either half alone would have been.
     */
    @Test
    fun `reads a detail that explains itself under message`() {
        val body = """
            {"error_code":"VALIDATION_ERROR",
             "message":"'x' is not a diary photo of this tenant.",
             "details":[{"field":"photo_refs","message":"unknown diary attachment 'x'"}]}
        """.trimIndent()

        assertEquals("photo_refs: unknown diary attachment 'x'", body.instanceErrorDetail())
    }

    @Test
    fun `falls back to the message when no field is named`() {
        // Not every failure is about a field. "The input data is invalid" says little, and
        // less than nothing is what the alternative says.
        val body = """{"error_code":"CONFLICT","message":"This plant was already removed."}"""

        assertEquals("This plant was already removed.", body.instanceErrorDetail())
    }

    /** A failure rejected before it reached the envelope still has something to say. */
    @Test
    fun `reads FastAPI's own shape as a fallback`() {
        assertEquals(
            "String should have at least 1 character",
            """{"detail":[{"loc":["body","text"],"msg":"String should have at least 1 character"}]}"""
                .instanceErrorDetail(),
        )
        assertEquals("Not found", """{"detail":"Not found"}""".instanceErrorDetail())
    }

    @Test
    fun `an unreadable body yields nothing rather than throwing`() {
        // The caller is already handling a failure; a parser that throws while explaining one
        // replaces a message the user could act on with a crash.
        assertNull("<html>502 Bad Gateway</html>".instanceErrorDetail())
        assertNull("".instanceErrorDetail())
        assertNull(null.instanceErrorDetail())
        assertNull("""{"error_code":"X"}""".instanceErrorDetail())
    }

    @Test
    fun `an empty details list is not a reason`() {
        // Present but empty is what an envelope looks like when nothing field-specific went
        // wrong. Falling through to the message beats reporting an empty sentence.
        val body = """{"message":"Something went wrong.","details":[]}"""

        assertEquals("Something went wrong.", body.instanceErrorDetail())
    }
}
