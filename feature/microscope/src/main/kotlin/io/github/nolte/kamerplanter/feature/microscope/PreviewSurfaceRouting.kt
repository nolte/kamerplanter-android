package io.github.nolte.kamerplanter.feature.microscope

/**
 * What the camera does when the platform hands it a preview surface or takes one away.
 *
 * Separate from [StreamSurfaces] because the two failed differently and only one of them was
 * ever testable. [StreamSurfaces] answers *what is true* about a surface; this decides *what to
 * do about it*, and every defect of the last four review rounds lived here — in the callbacks,
 * where a test could not reach because they need a `Context`, a `TextureView` and a device.
 *
 * The effects are injected for exactly that reason. Handing over, tearing down and claiming a
 * device are the three things these callbacks cause, and with them as functions the whole
 * sequence — a handover in the order Compose produces it, a departing view arriving late, a
 * surface destroyed mid-open — can be driven on the JVM and asserted on.
 *
 * Every entry point takes the lock, so callers do not have to remember to.
 */
internal class PreviewSurfaceRouting(
    private val lock: Any,
    private val surfaces: StreamSurfaces,
    /** Closes the stream so an arriving surface can take it over; no surface is released. */
    private val handOver: () -> Unit,
    /** Closes the stream that renders into this surface, and releases it afterwards. */
    private val tearDown: (Any) -> Unit,
    /** Releases a surface once the teardown already under way has finished. */
    private val releaseWhenIdle: (Any) -> Unit,
    /** Opens onto whatever surface is current, if a device is attached. */
    private val claim: () -> Unit,
) {

    /**
     * A preview surface became available.
     *
     * The handover is explicit rather than left to the order the outgoing view is destroyed in:
     * opening refuses while a session is published, so an arriving view that did not take the
     * stream over would render nothing while the controls sat live above it.
     */
    fun available(surface: Any) {
        val heldElsewhere = synchronized(lock) { surfaces.handoverFrom(surface) }
        if (heldElsewhere != null) handOver()
        claim()
    }

    /**
     * A preview surface is being destroyed. Returns what the platform asked for: `true` to
     * reclaim the surface, `false` to leave it to this camera.
     */
    fun destroyed(surface: Any): Boolean =
        when (synchronized(lock) { surfaces.onDestroyed(surface) }) {
            DestroyOutcome.RECLAIM -> true
            DestroyOutcome.TEAR_DOWN -> {
                tearDown(surface)
                false
            }
            // Answering `false` promises the platform this camera will release it, and
            // something has to — the teardown that holds it was started without a surface to
            // release, because at the time it started the view was still alive.
            DestroyOutcome.AWAIT_TEARDOWN -> {
                releaseWhenIdle(surface)
                false
            }
        }
}
