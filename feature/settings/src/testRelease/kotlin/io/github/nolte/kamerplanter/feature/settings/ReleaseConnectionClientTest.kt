package io.github.nolte.kamerplanter.feature.settings

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Guards the half of R34 that a compiler cannot state: the release variant must not be
 * *able* to bind the fake. Absence is the assertion, so it is made against the classpath
 * this variant actually compiles and packages.
 *
 * This test only exists in the release variant; `src/testDebug/FakeConnectionClientTest`
 * covers the other side.
 */
class ReleaseConnectionClientTest {

    @Test
    fun `the fake connection client is not on the release classpath at all`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("io.github.nolte.kamerplanter.feature.settings.FakeConnectionClient")
        }
    }

    @Test
    fun `the release variant binds the placeholder instead`() {
        // Resolves only because the class is compiled into this variant; the assertion is
        // that *something* stands in, and that it is the marked placeholder (WP-6/WP-9).
        Class.forName("io.github.nolte.kamerplanter.feature.settings.UnavailableConnectionClient")
    }
}
