package io.github.nolte.kamerplanter.feature.microscope

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation ordering this exists for, replayed without a device.
 *
 * The failure it prevents is not obvious from the call sites: each screen looks correct on its
 * own, holding the camera for exactly as long as it is composed. It is the *overlap* that
 * breaks — Compose composes the incoming destination before disposing the outgoing one, so the
 * pair runs `start()` then `stop()`, and an uncounted camera ends up off while a screen that
 * has already finished its one-shot `DisposableEffect(Unit)` waits for frames that never come.
 */
class HoldersTest {

    @Test
    fun `the first holder starts the hardware and the last one stops it`() {
        val holders = Holders()

        assertTrue("the first acquire must start it", holders.acquire())
        assertTrue("the last release must stop it", holders.release())
    }

    /** Navigating from one camera screen to another: compose the new, then dispose the old. */
    @Test
    fun `an overlapping handover never stops the hardware`() {
        val holders = Holders()
        holders.acquire()

        assertFalse("the arriving screen must not restart what is running", holders.acquire())
        assertFalse("and the departing one must not stop it", holders.release())
    }

    /** Leaving both screens really does stop it — the counting must not pin the device on. */
    @Test
    fun `letting go of both stops the hardware`() {
        val holders = Holders()
        holders.acquire()
        holders.acquire()
        holders.release()

        assertTrue(holders.release())
    }

    /**
     * `retry()` is `stop()` followed by `start()`, and it has to reach the hardware both ways.
     * A count that treated the restart as "already running" would make the button do nothing.
     */
    @Test
    fun `a stop and start pair from a single holder reaches the hardware`() {
        val holders = Holders()
        holders.acquire()

        assertTrue(holders.release())
        assertTrue(holders.acquire())
    }

    /**
     * An unpaired release must not go negative.
     *
     * Otherwise a screen disposed without ever having started — or a `stop()` that arrives
     * twice — leaves the count below zero, and the next genuine release reports "still held"
     * while nothing holds it: the device stays claimed with no screen on screen.
     */
    @Test
    fun `releasing more often than acquiring cannot leave the count negative`() {
        val holders = Holders()

        assertFalse("nothing was held, so nothing is stopped", holders.release())
        assertFalse(holders.release())

        assertTrue("a later acquire must still be the first", holders.acquire())
        assertTrue("and its release must still stop the hardware", holders.release())
    }
}
