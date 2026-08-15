package io.github.nolte.kamerplanter.core.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a discovered instance is the one already connected.
 *
 * Getting this wrong is not cosmetic in either direction: too strict and someone who scans the
 * code on their own instance is warned that continuing replaces their connection, too loose and
 * someone about to replace a working connection is told they are already on it.
 */
class SameInstanceTest {

    @Test
    fun `a trailing slash does not make it a different instance`() {
        assertTrue("https://plants.example/".sameInstanceAs("https://plants.example"))
    }

    @Test
    fun `scheme and host are compared case-insensitively`() {
        assertTrue("HTTPS://Plants.Example".sameInstanceAs("https://plants.example"))
    }

    @Test
    fun `a different host is a different instance`() {
        assertFalse("https://plants.example".sameInstanceAs("https://other.example"))
    }

    @Test
    fun `a different port is a different instance`() {
        assertFalse("https://plants.example:8443".sameInstanceAs("https://plants.example"))
    }

    /** kamerplanter can be hosted under a prefix, and two prefixes on one host are two instances. */
    @Test
    fun `a different path prefix is a different instance`() {
        assertFalse(
            "https://example.org/kamerplanter".sameInstanceAs("https://example.org/plants"),
        )
        assertTrue(
            "https://example.org/kamerplanter/".sameInstanceAs("https://example.org/kamerplanter"),
        )
    }

    /** Unparseable input must compare, not throw — it reaches this from stored data. */
    @Test
    fun `input that is not a url still compares`() {
        assertTrue("not a url".sameInstanceAs("not a url"))
        assertFalse("not a url".sameInstanceAs("https://plants.example"))
    }
}
