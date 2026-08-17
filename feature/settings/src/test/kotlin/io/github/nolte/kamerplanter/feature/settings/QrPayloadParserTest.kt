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
            QrPayload.Pairing(
                ConnectionRequest.QrPairing(
                    baseUrl = "https://garten.example.org",
                    code = "Qm5kR2xoY0dWeUlH",
                ),
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
     *
     * Refused **as ours**. It used to come back `null`, like a stranger's QR code, so the
     * scanner told the user they were holding the wrong code when they were holding the right
     * one and running the wrong app.
     */
    @Test
    fun `refuses a payload version it does not know, as ours, and says which way`() {
        // Which side is behind decides the advice, so the two are not one reason. Told simply
        // "version mismatch", the owner of an older instance would update the wrong side.
        assertEquals(
            QrPayload.Refused(RefusedReason.PAYLOAD_TOO_NEW),
            QrPayloadParser.parse(payload.replace("\"v\":1", "\"v\":2")),
        )
        assertEquals(
            QrPayload.Refused(RefusedReason.PAYLOAD_TOO_OLD),
            QrPayloadParser.parse(payload.replace("\"v\":1", "\"v\":0")),
        )
    }

    /**
     * A newer payload is refused as ours even when its fields have moved.
     *
     * Renaming a field is what a version bump is *for*, so demanding this build's fields
     * before comparing the version defeats the mechanism: the payload would fall through as a
     * stranger's code, which is the outcome the version exists to prevent.
     */
    @Test
    fun `a newer payload is claimed as ours even when its fields have changed`() {
        assertEquals(
            QrPayload.Refused(RefusedReason.PAYLOAD_TOO_NEW),
            QrPayloadParser.parse("""{"v":2,"origin":"https://garten.example.org","token":"x"}"""),
        )
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
     * It names an instance and nothing else — not which mode that instance runs in. Read as a
     * light-mode connection request it would have started an attempt that a full instance
     * refuses, and would have replaced a working connection without the warning the same link
     * gets when it arrives as a deep link. It is an address, and the caller decides.
     */
    @Test
    fun `reads a discovery link as the address it is`() {
        val payload = QrPayloadParser.parse("https://garten.example.org/connect?v=1")

        assertEquals(QrPayload.Discovery("https://garten.example.org"), payload)
    }

    /**
     * An address the app may not talk to is refused as ours too.
     *
     * A self-hoster running an instance on a routable address without TLS scans their own
     * valid pairing code. Reported as foreign, they would look for the fault in their
     * instance rather than in this app's rule about unencrypted connections.
     */
    @Test
    fun `a refused address is refused as ours, not as foreign`() {
        val onPlainHttp = """{"v":1,"url":"http://garten.example.org","code":"Qm5kR2xo"}"""

        assertEquals(
            QrPayload.Refused(RefusedReason.ADDRESS_NOT_ENCRYPTED),
            QrPayloadParser.parse(onPlainHttp),
        )
    }

    /**
     * An address the app cannot use at all is a different refusal from an unencrypted one.
     *
     * "Your instance has no TLS" is help exactly once — and misleading for an address with no
     * scheme, where TLS is not what went wrong.
     */
    @Test
    fun `an address with no usable scheme is refused as unusable`() {
        assertEquals(
            QrPayload.Refused(RefusedReason.ADDRESS_UNUSABLE),
            QrPayloadParser.parse("""{"v":1,"url":"garten.example.org","code":"Qm5kR2xo"}"""),
        )
    }

    /** A payload missing a field is indistinguishable from a foreign JSON code, and stays so. */
    @Test
    fun `an incomplete payload is not claimed as ours`() {
        assertNull(QrPayloadParser.parse("""{"v":1,"code":"Qm5kR2xo"}"""))
        assertNull(QrPayloadParser.parse("""{"v":1,"url":"https://garten.example.org"}"""))
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
