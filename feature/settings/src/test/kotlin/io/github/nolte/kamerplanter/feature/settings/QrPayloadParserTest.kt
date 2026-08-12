package io.github.nolte.kamerplanter.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadParserTest {

    @Test
    fun `parses a well-formed pairing uri into base url and code`() {
        val payload = QrPayloadParser.parse(
            "kamerplanter://pair?url=https%3A%2F%2Fplants.example.org&code=ABC123",
        )

        assertEquals(PairingPayload(baseUrl = "https://plants.example.org", code = "ABC123"), payload)
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        val payload = QrPayloadParser.parse("  kamerplanter://pair?url=https%3A%2F%2Fx&code=c  ")

        assertEquals(PairingPayload(baseUrl = "https://x", code = "c"), payload)
    }

    @Test
    fun `preserves a literal plus in the code instead of turning it into a space`() {
        val payload = QrPayloadParser.parse("kamerplanter://pair?url=https%3A%2F%2Fx&code=ab+cd")

        assertEquals(PairingPayload(baseUrl = "https://x", code = "ab+cd"), payload)
    }

    @Test
    fun `rejects a foreign scheme`() {
        assertNull(QrPayloadParser.parse("https://pair?url=https%3A%2F%2Fx&code=c"))
    }

    @Test
    fun `rejects the wrong host`() {
        assertNull(QrPayloadParser.parse("kamerplanter://login?url=https%3A%2F%2Fx&code=c"))
    }

    @Test
    fun `rejects a payload missing the code`() {
        assertNull(QrPayloadParser.parse("kamerplanter://pair?url=https%3A%2F%2Fx"))
    }

    @Test
    fun `rejects a payload missing the url`() {
        assertNull(QrPayloadParser.parse("kamerplanter://pair?code=c"))
    }

    @Test
    fun `rejects a blank field`() {
        assertNull(QrPayloadParser.parse("kamerplanter://pair?url=&code=c"))
    }

    @Test
    fun `rejects an arbitrary non-uri string`() {
        assertNull(QrPayloadParser.parse("just some scanned text"))
    }
}
