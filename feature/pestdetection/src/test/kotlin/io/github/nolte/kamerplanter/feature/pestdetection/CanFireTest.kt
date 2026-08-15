package io.github.nolte.kamerplanter.feature.pestdetection

import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import io.github.nolte.kamerplanter.feature.microscope.UnavailableReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the shutter is offered at all.
 *
 * The failure it prevents is not a no-op: pressing a shutter whose camera is not ready ends the
 * flow on an error screen and loses the chosen source, so the user is back at the picker
 * wondering what they did wrong. Neither source is ready the moment it is chosen — binding the
 * phone camera takes a beat, and the microscope passes through Connecting on every handover and
 * through AwaitingPermission while the USB dialogue is open.
 */
class CanFireTest {

    private val streaming = MicroscopeState.Streaming
    private val connecting = MicroscopeState.Connecting

    @Test
    fun `no source means no shutter`() {
        assertFalse(PestDetectionState.Ready().canFire(streaming))
    }

    @Test
    fun `the phone fires only once its camera has bound`() {
        val chosen = PestDetectionState.Ready(source = CaptureSource.PHONE)

        assertFalse("binding is asynchronous", chosen.canFire(streaming))
        assertTrue(chosen.copy(phoneReady = true).canFire(streaming))
    }

    @Test
    fun `the microscope fires only while it is streaming`() {
        val chosen = PestDetectionState.Ready(source = CaptureSource.MICROSCOPE)

        assertTrue(chosen.canFire(streaming))
        assertFalse("a handover passes through here", chosen.canFire(connecting))
        assertFalse(
            chosen.canFire(MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED)),
        )
        assertFalse(chosen.canFire(MicroscopeState.AwaitingPermission))
    }

    /** The two readiness signals belong to their own source and must not stand in for each other. */
    @Test
    fun `a bound phone camera does not make the microscope fire`() {
        val microscope = PestDetectionState.Ready(source = CaptureSource.MICROSCOPE, phoneReady = true)

        assertFalse(microscope.canFire(connecting))
    }
}
