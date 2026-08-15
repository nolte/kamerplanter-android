package io.github.nolte.kamerplanter.feature.microscope

/**
 * Which preview surface the stream belongs to, and what that means for the two callbacks the
 * platform delivers.
 *
 * Two screens can each hold a preview view of the same singleton camera, so "is this the
 * current view" is not the question — "does the stream render into *this* surface" is. And the
 * stream can belong to a surface before any session exists: opening a UVC device takes a few
 * hundred milliseconds on the camera thread, and a surface can be destroyed inside that
 * window. An answer that consults only the published session says "not mine" there and lets
 * the platform reclaim a surface the open is about to render into — the orphaned stream the
 * generation counter was introduced to prevent.
 *
 * Kept as a pure class over identity tokens because three review rounds found three defects in
 * this decision while it was inlined in the callbacks, where no test could reach it. Nothing
 * here touches a surface, a device or a thread.
 *
 * Every method is called from the main thread (the two `SurfaceTextureListener` callbacks) or
 * under the camera's session lock, so the plain fields need no synchronisation of their own.
 */
internal class StreamSurfaces {

    /** The surface an open is queued or running for; `null` when nothing is opening. */
    private var opening: Any? = null

    /** The surface the published session renders into. */
    private var live: Any? = null

    /**
     * The surface of a stream whose teardown is still running.
     *
     * Held because the platform must not reclaim a surface while the native preview thread may
     * still be writing into it, and the teardown is asynchronous — so a handover leaves a
     * window in which the outgoing surface is no longer ours but not yet safe to hand back.
     */
    private var closing: Any? = null

    fun opening(surface: Any) {
        opening = surface
    }

    /** The queued open became the live session. */
    fun published() {
        live = opening
        opening = null
    }

    /** The queued open was superseded or failed; nothing was published. */
    fun abandoned() {
        opening = null
    }

    /**
     * Everything the stream held is being torn down. Returns the surface that was involved, so
     * the caller can keep it out of the platform's hands until the teardown finishes.
     */
    fun closing(): Any? {
        closing = live ?: opening
        live = null
        opening = null
        return closing
    }

    /** The teardown finished and [surface] may go back to the platform. */
    fun released(surface: Any) {
        if (closing === surface) closing = null
    }

    /**
     * Whether a destroyed [surface] must take the stream down with it.
     *
     * True for the published session's surface *and* for one an open is in flight for: the
     * open would otherwise complete onto a surface that no longer exists.
     */
    fun ownsStream(surface: Any): Boolean = live === surface || opening === surface

    /**
     * Whether the platform may reclaim [surface] now.
     *
     * False while it is ours, and false while its teardown is still running — those are the two
     * states in which something may still be writing into it.
     */
    fun mayReclaim(surface: Any): Boolean = !ownsStream(surface) && closing !== surface

    /**
     * The surface the stream currently belongs to when that is *not* [surface], meaning an
     * arriving view has to take it over. `null` when nothing holds it, or when [surface]
     * already does.
     */
    fun heldElsewhereThan(surface: Any): Any? =
        (live ?: opening)?.takeIf { it !== surface }
}
