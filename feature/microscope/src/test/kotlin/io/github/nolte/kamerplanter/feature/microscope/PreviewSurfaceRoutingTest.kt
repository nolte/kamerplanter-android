package io.github.nolte.kamerplanter.feature.microscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequences four review rounds broke, driven end to end.
 *
 * Every one of those defects was in the callbacks rather than in the state they consult, and
 * every one survived because nothing could reach them: they need a `Context`, a `TextureView`
 * and an attached device, so the whole file was invisible to the gate while its decision was
 * rewritten four times. With the effects injected the orderings are ordinary Kotlin, and the
 * assertions are on *what the camera did* — handed over, tore down, released, claimed — which
 * is what actually went wrong each time.
 */
class PreviewSurfaceRoutingTest {

    private val first = Any()
    private val second = Any()

    private val surfaces = StreamSurfaces()
    private val effects = mutableListOf<String>()

    private val routing = PreviewSurfaceRouting(
        lock = Any(),
        surfaces = surfaces,
        handOver = { effects += "handOver" },
        tearDown = { effects += "tearDown($it)" },
        releaseWhenIdle = { effects += "releaseWhenIdle($it)" },
        claim = { effects += "claim" },
    )

    private fun label(surface: Any) = if (surface === first) "first" else "second"

    private fun effectsWith(surface: Any) = effects.map { it.replace(surface.toString(), label(surface)) }

    /** The stream is open on [surface], as it is after any successful start. */
    private fun streaming(surface: Any) {
        surfaces.opening(surface)
        surfaces.published(surface)
    }

    @Test
    fun `the first surface simply claims the device`() {
        routing.available(first)

        assertEquals(listOf("claim"), effects)
    }

    /**
     * Navigating from one camera screen to another, in the order Compose produces it: the
     * arriving view's surface appears while the departing one still exists, and the departing
     * view is disposed afterwards — while the outgoing teardown may still be running.
     *
     * The failure this pins put the second screen on "connecting" forever with a black preview
     * and no way back short of unplugging the microscope, because the departing view's
     * disposal tore down the stream the arriving one had just opened.
     */
    @Test
    fun `a departing view does not tear down the stream the arriving one just opened`() {
        streaming(first)

        routing.available(second)
        assertEquals("the arriving surface takes the stream over", listOf("handOver", "claim"), effects)
        // The handover's close, then the arriving open publishing.
        surfaces.closing()
        streaming(second)
        effects.clear()

        assertFalse("the platform must not reclaim it mid-teardown", routing.destroyed(first))

        assertEquals(
            "nothing may be torn down: what is live now belongs to the arriving screen",
            listOf("releaseWhenIdle(first)"),
            effectsWith(first),
        )
    }

    /** Once the outgoing teardown has finished, the surface simply goes back to the platform. */
    @Test
    fun `a departing view whose teardown finished is handed straight back`() {
        streaming(first)
        routing.available(second)
        surfaces.closing()
        streaming(second)
        surfaces.released(first)
        effects.clear()

        assertTrue("the platform may reclaim it", routing.destroyed(first))
        assertEquals("and nothing is done about it", emptyList<String>(), effects)
    }

    /** The ordinary case: the only surface goes away, so the stream goes with it. */
    @Test
    fun `destroying the surface the stream renders into tears it down`() {
        streaming(first)
        effects.clear()

        assertFalse(routing.destroyed(first))
        assertEquals(listOf("tearDown(first)"), effectsWith(first))
    }

    /**
     * A surface destroyed while its open is still in flight.
     *
     * Opening a UVC device takes hundreds of milliseconds, and backgrounding, a lock screen or
     * a fast navigation lands inside that window. Answering "reclaim" there — which a decision
     * reading only the published session does — lets the platform take the surface and the open
     * then publishes onto nothing: the device stays claimed, the state says Streaming, and the
     * preview is black until the cable is pulled.
     */
    @Test
    fun `a surface destroyed mid-open takes the open down with it`() {
        surfaces.opening(first)
        effects.clear()

        assertFalse(routing.destroyed(first))
        assertEquals(listOf("tearDown(first)"), effectsWith(first))
    }

    /** A surface nothing ever rendered into is not this camera's to hold. */
    @Test
    fun `an unrelated surface is handed back untouched`() {
        streaming(first)
        effects.clear()

        assertTrue(routing.destroyed(second))
        assertEquals(emptyList<String>(), effects)
    }

    /** An arriving surface that already holds the stream must not hand it over to itself. */
    @Test
    fun `a surface reappearing while it holds the stream does not hand over`() {
        streaming(first)
        effects.clear()

        routing.available(first)

        assertEquals(listOf("claim"), effects)
    }

    /**
     * The handover has to happen even before the outgoing open has published: it is the open
     * itself that would otherwise refuse, and the arriving view would wait forever.
     */
    @Test
    fun `an arriving surface takes over from an open still in flight`() {
        surfaces.opening(first)

        routing.available(second)

        assertEquals(listOf("handOver", "claim"), effects)
    }
}
