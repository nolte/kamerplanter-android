package io.github.nolte.kamerplanter.feature.microscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The decision four review rounds each got wrong, driven through the sequences that produced
 * the failures rather than through its methods one at a time.
 *
 * Every one of those failures looked like broken hardware and none reproduced without a
 * device: a black preview under a "streaming" state, a stream rendering into a surface the
 * platform had reclaimed, a screen insisting no microscope was attached while one was plugged
 * in, a second screen stuck on "connecting" forever. What they had in common is that no single
 * call was wrong — the *order* was. So the tests below are orderings, and the per-method ones
 * exist only to pin the pieces those orderings rest on.
 */
class StreamSurfacesTest {

    private val first = Any()
    private val second = Any()
    private val third = Any()

    // ── The orderings ────────────────────────────────────────────────────────────────

    /**
     * A handover in the order Compose actually produces it: the arriving view's surface
     * appears, the camera hands over, the arriving open is published — and only *then* is the
     * departing view disposed, while the outgoing teardown may still be running.
     *
     * That last step is the one that has broken twice. Answering it with "tear down" closes
     * the stream the arriving screen just opened, leaving it on `Connecting` with no preview
     * and no way back short of unplugging the device.
     */
    @Test
    fun `a departing view does not tear down the stream the arriving one just opened`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published(first)

        // The arriving view finds the stream held elsewhere and hands it over.
        assertSame(first, surfaces.handoverFrom(second))
        surfaces.closing()
        surfaces.opening(second)
        surfaces.published(second)

        // The departing view is disposed while the first teardown is still running.
        assertEquals(DestroyOutcome.AWAIT_TEARDOWN, surfaces.onDestroyed(first))
        assertEquals(
            "the arriving screen's stream must survive its predecessor's disposal",
            DestroyOutcome.TEAR_DOWN,
            surfaces.onDestroyed(second),
        )
    }

    /** The same handover once the outgoing teardown has finished: the surface simply goes back. */
    @Test
    fun `a departing view whose teardown finished is handed back to the platform`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published(first)
        surfaces.closing()
        surfaces.opening(second)
        surfaces.published(second)
        surfaces.released(first)

        assertEquals(DestroyOutcome.RECLAIM, surfaces.onDestroyed(first))
    }

    /**
     * An open that never happened must not leave a claim behind.
     *
     * `openStream` gives up when a session is already published, and an unscoped clean-up there
     * left the slot pointing at a surface nothing ever opened — after which destroying *any*
     * other surface tore down a perfectly live stream.
     */
    @Test
    fun `an open that gave up leaves no claim on its surface`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published(first)
        surfaces.opening(second)
        surfaces.abandoned(second, stillCurrent = true)

        assertEquals(DestroyOutcome.RECLAIM, surfaces.onDestroyed(second))
        assertEquals(
            "and the live stream is untouched by it",
            DestroyOutcome.TEAR_DOWN,
            surfaces.onDestroyed(first),
        )
    }

    /** A stale abandon must not clear a newer open's claim. */
    @Test
    fun `abandoning an older open leaves a newer one claimed`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.opening(second)
        surfaces.abandoned(first, stillCurrent = true)

        assertEquals(DestroyOutcome.TEAR_DOWN, surfaces.onDestroyed(second))
    }

    /**
     * Two closes overlapping — a surface destroyed by backgrounding, immediately followed by
     * the screen's own `stop()`. A single closing slot let the second drop the first one's
     * hold, handing a surface back while the engine was still writing into it.
     */
    @Test
    fun `an overlapping close does not drop the previous surface's hold`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published(first)
        surfaces.closing()
        surfaces.opening(second)
        surfaces.published(second)
        surfaces.closing()

        assertEquals(DestroyOutcome.AWAIT_TEARDOWN, surfaces.onDestroyed(first))
        assertEquals(DestroyOutcome.AWAIT_TEARDOWN, surfaces.onDestroyed(second))

        surfaces.released(first)
        assertEquals(DestroyOutcome.RECLAIM, surfaces.onDestroyed(first))
        assertEquals("released is identity-scoped", DestroyOutcome.AWAIT_TEARDOWN, surfaces.onDestroyed(second))
    }

    /**
     * The regression that made this class necessary in the first place.
     *
     * Opening a UVC device takes hundreds of milliseconds on the camera thread, and a surface
     * can be destroyed inside that window — backgrounding, a lock screen, a fast navigation. A
     * decision that consults only the published session answers "not mine" there, the platform
     * reclaims the surface, and the open completes onto it moments later: a stream holding the
     * device and rendering into nothing, with the state stuck on Streaming.
     */
    @Test
    fun `a surface destroyed mid-open takes the open down with it`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)

        assertEquals(DestroyOutcome.TEAR_DOWN, surfaces.onDestroyed(first))
    }

    // ── The pieces those orderings rest on ───────────────────────────────────────────

    @Test
    fun `nothing is claimed before anything opens`() {
        val surfaces = StreamSurfaces()

        assertEquals(DestroyOutcome.RECLAIM, surfaces.onDestroyed(first))
        assertNull(surfaces.handoverFrom(first))
        assertNull("nothing to hand over, so nothing to hold", surfaces.closing())
    }

    @Test
    fun `a published session claims the surface it renders into, and only that one`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published(first)

        assertEquals(DestroyOutcome.TEAR_DOWN, surfaces.onDestroyed(first))
        assertEquals(DestroyOutcome.RECLAIM, surfaces.onDestroyed(second))
    }

    /**
     * Publishing consumes the opening claim, so a later abandon finds nothing to clear.
     *
     * Asserted through `handoverFrom`, because `onDestroyed` cannot see the difference —
     * `live` alone already answers it. Leaving `opening` set would make the *next* open on the
     * same surface look like it was already in flight.
     */
    @Test
    fun `publishing consumes the opening claim`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published(first)
        surfaces.closing()

        assertNull("nothing may be left claiming the stream", surfaces.handoverFrom(second))
    }

    /**
     * A surface can be in both slots at once, and which one wins decides correctly.
     *
     * `retry()` is stop plus start: the close puts the surface into `closing`, and the restart
     * immediately claims the same surface for a new open — the same `SurfaceTexture`, because
     * the view never went away. Answering "its teardown is running" there would refuse to tear
     * down an open that is very much live.
     */
    @Test
    fun `a surface reopened while its teardown runs belongs to the new open`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published(first)
        surfaces.closing()
        surfaces.opening(first)

        assertEquals(DestroyOutcome.TEAR_DOWN, surfaces.onDestroyed(first))
    }

    /**
     * Two opens on one view carry the *same* surface, so identity cannot tell them apart — the
     * generation can, and without it a superseded open erases its successor's claim. For the
     * several hundred milliseconds that successor needs, nothing would be claimed at all.
     */
    @Test
    fun `a superseded open does not erase its successor's claim on the same surface`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.opening(first)

        surfaces.abandoned(first, stillCurrent = false)

        assertEquals(DestroyOutcome.TEAR_DOWN, surfaces.onDestroyed(first))
    }

    @Test
    fun `an arriving surface is told which one holds the stream`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)

        assertSame("even before the open is published", first, surfaces.handoverFrom(second))
        assertNull("the holder is not elsewhere from its own point of view", surfaces.handoverFrom(first))
    }

    @Test
    fun `closing hands back the surface it took, and claims nothing further`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)
        surfaces.published(first)

        assertSame(first, surfaces.closing())
        assertNull("nothing holds the stream afterwards", surfaces.handoverFrom(third))
    }

    /** Closing mid-open holds that surface too — the open is still about to render into it. */
    @Test
    fun `closing during an open holds and releases its surface`() {
        val surfaces = StreamSurfaces()
        surfaces.opening(first)

        assertSame(first, surfaces.closing())
        assertEquals(DestroyOutcome.AWAIT_TEARDOWN, surfaces.onDestroyed(first))
        assertNull("the open no longer holds anything", surfaces.handoverFrom(second))
    }
}
