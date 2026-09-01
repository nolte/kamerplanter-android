package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.PayloadRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The typed-address rules of F-7 and F-11; the policy itself is tested where it lives. */
class InstanceAddressInputTest {

    @Test
    fun `a schemeless address is completed with https, never http`() {
        assertEquals("https://garden.example.org", InstanceAddressInput.normalize("garden.example.org"))
    }

    @Test
    fun `a spelled-out scheme is kept as typed`() {
        assertEquals("http://192.168.1.20:8000", InstanceAddressInput.normalize("http://192.168.1.20:8000"))
        assertEquals("https://garden.example.org", InstanceAddressInput.normalize("https://garden.example.org"))
    }

    @Test
    fun `surrounding whitespace is not part of an address`() {
        assertEquals("https://garden.example.org", InstanceAddressInput.normalize("  garden.example.org "))
    }

    @Test
    fun `an https address passes, wherever it points`() {
        assertNull(InstanceAddressInput.refusalFor("garden.example.org"))
        assertNull(InstanceAddressInput.refusalFor("https://192.168.1.20"))
    }

    @Test
    fun `plain http passes only inside the user's own network`() {
        assertNull(InstanceAddressInput.refusalFor("http://192.168.1.20:8000"))
        assertEquals(
            PayloadRefusal.ADDRESS_NOT_ENCRYPTED,
            InstanceAddressInput.refusalFor("http://garden.example.org"),
        )
    }

    @Test
    fun `an address that names nothing is unusable`() {
        assertEquals(PayloadRefusal.ADDRESS_UNUSABLE, InstanceAddressInput.refusalFor(""))
        assertEquals(PayloadRefusal.ADDRESS_UNUSABLE, InstanceAddressInput.refusalFor("   "))
        assertEquals(PayloadRefusal.ADDRESS_UNUSABLE, InstanceAddressInput.refusalFor("ftp://x"))
    }
}
