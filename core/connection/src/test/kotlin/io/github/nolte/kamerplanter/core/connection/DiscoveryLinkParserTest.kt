package io.github.nolte.kamerplanter.core.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The link the backend documents this app as recognising — and everything that merely looks
 * like it.
 *
 * The manifest claims a **wildcard host**: every `https://…/connect` on the internet is offered
 * to this app. So the parser is the only thing standing between a link someone else wrote and
 * an instance address the connection flow will act on, and "refuse anything that is not exactly
 * the documented shape" is its whole job.
 */
class DiscoveryLinkParserTest {

    @Test
    fun `reads the instance out of the documented link`() {
        val outcome = DiscoveryLinkParser.read("https://plants.example/connect?v=1")

        assertEquals(usable("https://plants.example"), outcome)
    }

    /** A self-hosted instance on a non-standard port is the ordinary case, not the exotic one. */
    @Test
    fun `keeps a non-standard port`() {
        assertEquals(
            usable("https://plants.example:8443"),
            DiscoveryLinkParser.read("https://plants.example:8443/connect?v=1"),
        )
    }

    /** The scheme's own default port is noise, and carrying it would break the comparison. */
    @Test
    fun `drops a port the scheme already implies`() {
        assertEquals(
            usable("https://plants.example"),
            DiscoveryLinkParser.read("https://plants.example:443/connect?v=1"),
        )
    }

    /**
     * kamerplanter can be hosted under a path prefix, and dropping it would send the app to the
     * wrong address on the right host. The manifest filter only matches `/connect` at the root,
     * so this cannot arrive today — but losing the information when it does would be a defect
     * introduced now and found much later.
     */
    @Test
    fun `keeps a path prefix the instance is hosted under`() {
        assertEquals(
            usable("https://example.org/kamerplanter"),
            DiscoveryLinkParser.read("https://example.org/kamerplanter/connect?v=1"),
        )
    }

    /**
     * A payload version this build has never heard of describes a shape it cannot read, and
     * reading it anyway is how a client acts on a field that changed meaning (R7). The version
     * space is shared with the pairing QR, so this is the same rule in a different transport.
     *
     * Refused *by name*, which is the point of #40: this link is recognisably kamerplanter's,
     * so both the scanner and the deep-link channel can say the app is the one that is behind.
     */
    @Test
    fun `names the version it cannot read rather than dropping the link`() {
        assertEquals(
            DiscoveryOutcome.Refused(PayloadRefusal.PAYLOAD_TOO_NEW),
            DiscoveryLinkParser.read("https://plants.example/connect?v=2"),
        )
    }

    /**
     * A version that is not a number is not a version, and a link without one is not this
     * contract: the documented link always carries a readable `v`. Both stay unclaimed —
     * anybody's web app may have a `/connect` page.
     */
    @Test
    fun `drops a link whose version it cannot read at all`() {
        assertNull(DiscoveryLinkParser.read("https://plants.example/connect?v="))
        assertNull(DiscoveryLinkParser.read("https://plants.example/connect"))
    }

    /**
     * The wildcard host is why this matters: every `https` link on the device is offered to
     * this app, and only the exact documented path is ours.
     */
    @Test
    fun `refuses a path that is not the connect endpoint`() {
        assertNull(DiscoveryLinkParser.read("https://plants.example/?v=1"))
        assertNull(DiscoveryLinkParser.read("https://plants.example/connected?v=1"))
        assertNull(DiscoveryLinkParser.read("https://plants.example/connect/more?v=1"))
    }

    /**
     * Plain HTTP to a routable host is refused rather than upgraded.
     *
     * An instance address arrived at over an unencrypted link could have been rewritten in
     * transit, and this app would then offer to pair with whatever it named. The exception for
     * a private address is [InstanceAddressPolicy]'s, and is covered by its own tests; what
     * matters here is that this parser asks rather than deciding for itself.
     */
    @Test
    fun `refuses plain http to a routable host, and any other scheme`() {
        // Ours, and refused: the instance is named, so the reason can be said out loud.
        assertEquals(
            DiscoveryOutcome.Refused(PayloadRefusal.ADDRESS_NOT_ENCRYPTED),
            DiscoveryLinkParser.read("http://plants.example/connect?v=1"),
        )
        // Not ours at all: a `/connect` link comes out of a browser's own origin, so a scheme
        // that is not http(s) is somebody else's — and silence is the right answer to it.
        assertNull(DiscoveryLinkParser.read("kamerplanter://connect?v=1"))
    }

    /**
     * The development case, and the reason the app exists on this network at all: an instance
     * served over plain http at a private address.
     */
    @Test
    fun `reads a private-network instance over plain http, keeping its scheme`() {
        assertEquals(
            // Not rewritten to https — that address does not answer on 443.
            usable("http://192.168.178.21:8000"),
            DiscoveryLinkParser.read("http://192.168.178.21:8000/connect?v=1"),
        )
    }

    /**
     * A host `java.net.URI` will not name is still a host, and its port still counts.
     *
     * The underscore fix landed in `InstanceAddressPolicy` first and not here, so the same
     * instance's pairing code was accepted while its `/connect` link was called foreign —
     * with both codes on one dialogue and the scanner taking whichever it decoded first.
     */
    @Test
    fun `reads a link whose host java-net-URI refuses to name`() {
        assertEquals(
            usable("http://mein_nas.local:8000"),
            DiscoveryLinkParser.read("http://mein_nas.local:8000/connect?v=1"),
        )
    }

    /** Junk must fail as "not for us", never as an exception on the way in. */
    @Test
    fun `refuses input that is not a link at all`() {
        assertNull(DiscoveryLinkParser.read(""))
        assertNull(DiscoveryLinkParser.read("   "))
        assertNull(DiscoveryLinkParser.read("not a url"))
        // Names no host, so there is no instance to claim it for.
        assertNull(DiscoveryLinkParser.read("https:///connect?v=1"))
    }

    /**
     * The pairing payload must never be readable as a discovery link.
     *
     * That separation is the security boundary the whole design rests on: the pairing code is a
     * one-time credential, and a credential inside a publicly openable URL could be routed to
     * whichever app the user picks from Android's chooser.
     */
    @Test
    fun `refuses a pairing payload`() {
        assertNull(DiscoveryLinkParser.read("kamerplanter://pair?url=https%3A%2F%2Fx&code=abc"))
        assertNull(DiscoveryLinkParser.read("""{"v":1,"url":"https://x","code":"abc"}"""))
    }
}

private fun usable(baseUrl: String) = DiscoveryOutcome.Usable(DiscoveryLink(baseUrl))
