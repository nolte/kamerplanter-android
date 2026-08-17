package io.github.nolte.kamerplanter.core.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceAddressPolicyTest {

    @Test
    fun `https is allowed anywhere`() {
        assertTrue(InstanceAddressPolicy.permits("https://plants.example.org"))
        assertTrue(InstanceAddressPolicy.permits("https://192.168.178.21"))
    }

    @Test
    fun `http reaches an instance on the home network`() {
        // The case the exception exists for: a development instance served over plain http on
        // the network the phone is already on. Refusing it means the app cannot be connected
        // to the machine it is being written against.
        assertTrue(InstanceAddressPolicy.permits("http://192.168.178.21/connect?v=1"))
        assertTrue(InstanceAddressPolicy.permits("http://10.0.0.5:8080"))
        assertTrue(InstanceAddressPolicy.permits("http://172.16.4.1"))
        assertTrue(InstanceAddressPolicy.permits("http://kamerplanter.local"))
        assertTrue(InstanceAddressPolicy.permits("http://localhost:8000"))
    }

    @Test
    fun `http to a routable host is refused`() {
        // The pairing payload carries a one-time credential and every later request carries
        // the token it buys. On the path to a public host both are readable in transit.
        assertFalse(InstanceAddressPolicy.permits("http://plants.example.org"))
        assertFalse(InstanceAddressPolicy.permits("http://8.8.8.8"))
    }

    @Test
    fun `a near-miss of a private range stays routable`() {
        // 172.16.0.0/12 ends at 172.31, and 169.254 is link-local while 169.253 is not. An
        // off-by-one here hands the cleartext exception to an address on the internet.
        assertFalse(InstanceAddressPolicy.permits("http://172.32.0.1"))
        assertFalse(InstanceAddressPolicy.permits("http://172.15.0.1"))
        assertFalse(InstanceAddressPolicy.permits("http://169.253.0.1"))
        assertFalse(InstanceAddressPolicy.permits("http://100.128.0.1"))
        assertTrue(InstanceAddressPolicy.permits("http://172.31.255.254"))
        assertTrue(InstanceAddressPolicy.permits("http://169.254.1.1"))
    }

    @Test
    fun `a hostname is judged as written, never resolved`() {
        // A name under someone else's control could be pointed at a private address to pass
        // the check and then moved. Only the literal address earns the exception.
        assertFalse(InstanceAddressPolicy.permits("http://192-168-178-21.attacker.example"))
        assertFalse(InstanceAddressPolicy.permits("http://notlocal"))
    }

    /**
     * A hostname that merely starts like an IPv6 range is still a hostname.
     *
     * The IPv6 test is prefix matching, and it used to run against every host string: `fdroid`
     * and `fc-nas` begin with `fd` and `fc`, so both earned the cleartext exception and would
     * have carried a pairing credential unencrypted to a routable host. The test above already
     * claimed hostnames are judged as written; it just never asked one that looked like an
     * address.
     */
    @Test
    fun `a hostname that starts like an ipv6 range is not one`() {
        assertFalse(InstanceAddressPolicy.permits("http://fdroid.example.com"))
        assertFalse(InstanceAddressPolicy.permits("http://fc-nas.dyndns.example.org"))
        assertFalse(InstanceAddressPolicy.permits("http://fe80.attacker.example"))
        assertFalse(InstanceAddressPolicy.permits("http://fdisk"))
    }

    @Test
    fun `a suffix must be the whole label, not a substring`() {
        assertFalse(InstanceAddressPolicy.permits("http://evil-local"))
        assertFalse(InstanceAddressPolicy.permits("http://localhost.example.org"))
    }

    /**
     * A host name `java.net.URI` declines to parse is still a host name.
     *
     * `getHost()` enforces a stricter syntax than DNS, OkHttp or a home NAS: an underscore
     * makes it answer null, and the address was then refused and reported as a foreign QR
     * code — for something every other part of the stack handles.
     */
    @Test
    fun `an underscore in the host does not make the address foreign`() {
        assertTrue(InstanceAddressPolicy.permits("http://mein_nas.local:8000"))
        assertTrue(InstanceAddressPolicy.permits("https://mein_nas.example.org"))
        // The rule itself does not loosen: the host is still judged on its own.
        assertFalse(InstanceAddressPolicy.permits("http://mein_nas.example.org"))
    }

    @Test
    fun `only http and https are addresses at all`() {
        assertFalse(InstanceAddressPolicy.permits("ftp://192.168.178.21"))
        assertFalse(InstanceAddressPolicy.permits("file:///etc/passwd"))
        assertFalse(InstanceAddressPolicy.permits("not a url"))
        assertFalse(InstanceAddressPolicy.permits(""))
    }

    @Test
    fun `an octet that cannot be read exactly is not private`() {
        // "010" is octal to some parsers and decimal to others; an address two readers
        // disagree about must not be the one that earns a cleartext exception.
        assertFalse(InstanceAddressPolicy.permits("http://010.0.0.1"))
        assertFalse(InstanceAddressPolicy.permits("http://192.168.1"))
        assertFalse(InstanceAddressPolicy.permits("http://192.168.1.256"))
    }

    @Test
    fun `ipv6 loopback and unique-local are private`() {
        assertTrue(InstanceAddressPolicy.permits("http://[::1]:8000"))
        assertTrue(InstanceAddressPolicy.permits("http://[fd12:3456::1]"))
        assertTrue(InstanceAddressPolicy.permits("http://[fe80::1]"))
        assertFalse(InstanceAddressPolicy.permits("http://[2001:db8::1]"))
    }
}
