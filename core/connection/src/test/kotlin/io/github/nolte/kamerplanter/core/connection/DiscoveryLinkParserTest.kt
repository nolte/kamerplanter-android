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
        val link = DiscoveryLinkParser.parse("https://plants.example/connect?v=1")

        assertEquals(DiscoveryLink("https://plants.example"), link)
    }

    /** A self-hosted instance on a non-standard port is the ordinary case, not the exotic one. */
    @Test
    fun `keeps a non-standard port`() {
        assertEquals(
            DiscoveryLink("https://plants.example:8443"),
            DiscoveryLinkParser.parse("https://plants.example:8443/connect?v=1"),
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
            DiscoveryLink("https://example.org/kamerplanter"),
            DiscoveryLinkParser.parse("https://example.org/kamerplanter/connect?v=1"),
        )
    }

    /**
     * A payload version this build has never heard of describes a shape it cannot read, and
     * reading it anyway is how a client acts on a field that changed meaning (R7). The version
     * space is shared with the pairing QR, so this is the same rule in a different transport.
     */
    @Test
    fun `refuses a payload version it does not know`() {
        assertNull(DiscoveryLinkParser.parse("https://plants.example/connect?v=2"))
        assertNull(DiscoveryLinkParser.parse("https://plants.example/connect?v="))
    }

    /** No version at all is not this contract either — the documented link always carries one. */
    @Test
    fun `refuses a link without a version`() {
        assertNull(DiscoveryLinkParser.parse("https://plants.example/connect"))
    }

    /**
     * The wildcard host is why this matters: every `https` link on the device is offered to
     * this app, and only the exact documented path is ours.
     */
    @Test
    fun `refuses a path that is not the connect endpoint`() {
        assertNull(DiscoveryLinkParser.parse("https://plants.example/?v=1"))
        assertNull(DiscoveryLinkParser.parse("https://plants.example/connected?v=1"))
        assertNull(DiscoveryLinkParser.parse("https://plants.example/connect/more?v=1"))
    }

    /**
     * Plain HTTP is refused outright rather than upgraded.
     *
     * An instance address arrived at over an unencrypted link could have been rewritten in
     * transit, and this app would then offer to pair with whatever it named.
     */
    @Test
    fun `refuses anything that is not https`() {
        assertNull(DiscoveryLinkParser.parse("http://plants.example/connect?v=1"))
        assertNull(DiscoveryLinkParser.parse("kamerplanter://connect?v=1"))
    }

    /** Junk must fail as "not for us", never as an exception on the way in. */
    @Test
    fun `refuses input that is not a link at all`() {
        assertNull(DiscoveryLinkParser.parse(""))
        assertNull(DiscoveryLinkParser.parse("   "))
        assertNull(DiscoveryLinkParser.parse("not a url"))
        assertNull(DiscoveryLinkParser.parse("https:///connect?v=1"))
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
        assertNull(DiscoveryLinkParser.parse("kamerplanter://pair?url=https%3A%2F%2Fx&code=abc"))
        assertNull(DiscoveryLinkParser.parse("""{"v":1,"url":"https://x","code":"abc"}"""))
    }
}
