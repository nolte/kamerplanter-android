package io.github.nolte.kamerplanter.feature.microscope

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision three review rounds each got wrong while it lived inside the platform
 * callbacks, where nothing could reach it.
 *
 * The failures it produced all looked like broken hardware rather than like a bug: a black
 * preview under a "streaming" state, a stream rendering into a surface the platform had taken
 * back, a screen insisting no microscope was attached while one was plugged in. None of them
 * are visible from the call site, and none reproduce without a device — which is exactly why
 * the decision belongs in a class that can be driven with two tokens and no Android at all.
 */
class StreamSurfacesTest {

    private val first = Any()
    private val second = Any()

    @Test
    fun `nothing owns a surface before anything opens`() {
        val surfaces = StreamSurfaces()

        assertFalse(surfaces.ownsStream(first))
        assertTrue("the platform may reclaim what we never held", surfaces.mayReclaim(first))
        assertNull(surfaces.heldElsewhereThan(first))
    }

    /**
     * The regression that made this class necessary.
     *
     * Opening a device takes hundreds of milliseconds on the camera thread, and a surface can
     * be destroyed inside that window — backgrounding, a lock screen, a fast navigation. A
     * decision that consults only the published session answers "not mine" there, the platform
     * reclaims the surface, and the open completes onto it moments later: a stream holding the
     * device and rendering into nothing, with the state stuck on Streaming.
     */
    @Test
    fun `a surface an open is still in flight for owns the stream`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)

        assertTrue(surfaces.ownsStream(first))
        assertFalse("the platform must not reclaim it mid-open", surfaces.mayReclaim(first))
    }

    @Test
    fun `a published session owns the surface it renders into`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published()

        assertTrue(surfaces.ownsStream(first))
        assertFalse(surfaces.ownsStream(second))
        assertTrue("another view's surface is not ours", surfaces.mayReclaim(second))
    }

    /** An open that was superseded or failed leaves nothing claimed. */
    @Test
    fun `an abandoned open releases its claim`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.abandoned()

        assertFalse(surfaces.ownsStream(first))
        assertTrue(surfaces.mayReclaim(first))
    }

    /**
     * The handover: a second screen's view arrives while the stream renders into the first.
     * Without an answer here the arriving view never gets a stream, because opening refuses
     * while a session is published — a black preview with the controls live over it.
     */
    @Test
    fun `an arriving surface is told which one holds the stream`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published()

        assertSame(first, surfaces.heldElsewhereThan(second))
        assertNull("the holder is not elsewhere from its own point of view", surfaces.heldElsewhereThan(first))
    }

    /** The same, before the first surface's open has finished. */
    @Test
    fun `an arriving surface takes over from an open still in flight`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)

        assertSame(first, surfaces.heldElsewhereThan(second))
    }

    /**
     * A torn-down surface stays out of the platform's hands until the engine has let go.
     *
     * The teardown runs on the camera thread while the outgoing view is disposed on the main
     * one, so there is a window in which the surface is no longer ours and not yet safe to hand
     * back — and the native preview thread may still be writing into it.
     */
    @Test
    fun `a surface being torn down may not be reclaimed yet`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published()

        assertSame(first, surfaces.closing())
        assertFalse("the stream is no longer ours…", surfaces.ownsStream(first))
        assertFalse("…but the engine has not let go of the surface", surfaces.mayReclaim(first))

        surfaces.released(first)
        assertTrue(surfaces.mayReclaim(first))
    }

    /** Closing mid-open has to hold the surface too — that open is about to render into it. */
    @Test
    fun `closing during an open still holds its surface`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)

        assertSame(first, surfaces.closing())
        assertFalse(surfaces.mayReclaim(first))
    }

    @Test
    fun `closing with nothing open holds nothing`() {
        val surfaces = StreamSurfaces()

        assertNull(surfaces.closing())
        assertTrue(surfaces.mayReclaim(first))
    }

    /**
     * A full handover, in the order Compose actually produces it: the arriving view's surface
     * appears first, the departing view is destroyed afterwards.
     */
    @Test
    fun `a handover leaves the arriving surface owning the stream and the departing one free`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published()

        // The arriving view sees the stream held elsewhere and closes it.
        assertSame(first, surfaces.heldElsewhereThan(second))
        surfaces.closing()
        surfaces.released(first)
        surfaces.opening(second)
        surfaces.published()

        assertTrue(surfaces.ownsStream(second))
        assertTrue("the departing view's surface may now go back", surfaces.mayReclaim(first))
        assertFalse(surfaces.mayReclaim(second))
    }
}
