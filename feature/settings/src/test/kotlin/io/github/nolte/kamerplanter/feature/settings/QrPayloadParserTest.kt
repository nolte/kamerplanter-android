package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The payload a real instance encodes, and everything that merely resembles it.
 *
 * The shape is not this app's invention: the web UI writes `JSON.stringify({ v, url, code })`
 * and the backend documents that contract. A parser that accepts anything else accepts nothing
 * at all — which is exactly what shipped. The version this replaced read a placeholder URI no
 * instance has ever produced, so scanning a genuine pairing code did nothing and said nothing,
 * and the app could not be connected to a real instance by any route.
 *
 * The scanner is also the only thing between a QR someone else printed and a connection
 * attempt against whatever it names, which is why most of these are refusals.
 */
class QrPayloadParserTest {

    private val payload =
        """{"v":1,"url":"https://garten.example.org","code":"Qm5kR2xoY0dWeUlH"}"""

    @Test
    fun `reads the pairing payload the instance encodes`() {
        val request = QrPayloadParser.parse(payload)

        assertEquals(
            ConnectionRequest.QrPairing(
                baseUrl = "https://garten.example.org",
                code = "Qm5kR2xoY0dWeUlH",
            ),
            request,
        )
    }

    /** Field order is not part of JSON, and a producer is free to change it. */
    @Test
    fun `field order does not matter`() {
        val reordered = """{"code":"Qm5kR2xoY0dWeUlH","v":1,"url":"https://garten.example.org"}"""

        assertEquals(QrPayloadParser.parse(payload), QrPayloadParser.parse(reordered))
    }

    /** A newer instance may add fields; that alone is not a reason to refuse the code. */
    @Test
    fun `an unknown extra field is ignored`() {
        val extended =
            """{"v":1,"url":"https://garten.example.org","code":"Qm5kR2xoY0dWeUlH","tenant":"demo"}"""

        assertEquals(QrPayloadParser.parse(payload), QrPayloadParser.parse(extended))
    }

    /**
     * A version this build has never heard of describes a shape it cannot read (R7).
     *
     * Refusing matters more here than anywhere else in the app: the payload carries a one-time
     * credential, and guessing at the meaning of its fields is guessing with that credential.
     */
    @Test
    fun `refuses a payload version it does not know`() {
        assertNull(QrPayloadParser.parse(payload.replace("\"v\":1", "\"v\":2")))
        assertNull(QrPayloadParser.parse(payload.replace("\"v\":1", "\"v\":0")))
    }

    /** No version at all is not the documented contract either. */
    @Test
    fun `refuses a payload without a version`() {
        assertNull(
            QrPayloadParser.parse("""{"url":"https://garten.example.org","code":"Qm5kR2xo"}"""),
        )
    }

    /**
     * A quoted version is not the documented type.
     *
     * Accepting it would mean assuming that a producer which got the type wrong got the rest
     * right — about a payload holding a credential.
     */
    @Test
    fun `refuses a version that is not a number`() {
        assertNull(QrPayloadParser.parse(payload.replace("\"v\":1", "\"v\":\"1\"")))
    }

    @Test
    fun `refuses a payload missing the url or the code`() {
        assertNull(QrPayloadParser.parse("""{"v":1,"code":"Qm5kR2xo"}"""))
        assertNull(QrPayloadParser.parse("""{"v":1,"url":"https://garten.example.org"}"""))
        assertNull(QrPayloadParser.parse("""{"v":1,"url":"","code":"Qm5kR2xo"}"""))
        assertNull(QrPayloadParser.parse("""{"v":1,"url":"https://x","code":"  "}"""))
    }

    /**
     * The credential-free discovery link, which the same dialogue offers for light mode.
     *
     * It names an instance and nothing else, so it can only mean a light-mode connection —
     * reading it as a pairing would invent a credential the payload does not carry.
     */
    @Test
    fun `reads a discovery link as a light-mode connection`() {
        val request = QrPayloadParser.parse("https://garten.example.org/connect?v=1")

        assertEquals(ConnectionRequest.LightMode("https://garten.example.org"), request)
    }

    /** The discovery link's own rules still apply — an unknown version is refused there too. */
    @Test
    fun `refuses a discovery link it cannot read`() {
        assertNull(QrPayloadParser.parse("https://garten.example.org/connect?v=2"))
        assertNull(QrPayloadParser.parse("http://garten.example.org/connect?v=1"))
    }

    /** Anything else fails as "keep scanning", never as an exception (R44). */
    @Test
    fun `refuses anything that is not one of the two shapes`() {
        assertNull(QrPayloadParser.parse(""))
        assertNull(QrPayloadParser.parse("   "))
        assertNull(QrPayloadParser.parse("just some scanned text"))
        assertNull(QrPayloadParser.parse("{"))
        assertNull(QrPayloadParser.parse("[1,2,3]"))
        assertNull(QrPayloadParser.parse("https://example.org/some/other/page"))
        // The placeholder shape this parser used to accept, which no instance produces.
        assertNull(QrPayloadParser.parse("kamerplanter://pair?url=https%3A%2F%2Fx&code=abc"))
    }
}
