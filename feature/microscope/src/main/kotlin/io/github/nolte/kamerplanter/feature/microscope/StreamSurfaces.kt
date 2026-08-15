package io.github.nolte.kamerplanter.feature.microscope

/**
 * What the camera must do when a preview surface arrives or goes away.
 *
 * Four review rounds each fixed the previous round's version of this decision and introduced
 * the next one, so it is not a predicate any more — it decides the *action*, and the callbacks
 * only carry it out. What kept going wrong was never the comparison itself but the states
 * around it: an open still in flight owns a surface no session points at yet, and a surface
 * whose teardown is still running is neither ours to render into nor the platform's to
 * reclaim. A boolean cannot say either, and both were answered by falling into the wrong
 * branch of one.
 *
 * All state is identity-scoped: every method names the surface it concerns, so a stale
 * callback arriving after a newer one cannot clear the newer one's claim.
 *
 * Every method is called under the camera's session lock. That is what makes the plain fields
 * safe — not the thread they run on, which is the main thread for the callbacks and the camera
 * thread for the open.
 */
internal class StreamSurfaces {

    /** The surface an open is queued or running for; `null` when nothing is opening. */
    private var opening: Any? = null

    /** The surface the published session renders into. */
    private var live: Any? = null

    /**
     * Surfaces whose teardown has been started but not finished.
     *
     * A set rather than a single slot: two closes can overlap — a surface destroyed by
     * backgrounding, immediately followed by the screen's own `stop()` — and a single slot
     * lets the second silently drop the first one's hold, handing a surface back while the
     * engine is still writing into it.
     */
    private val closing = mutableSetOf<Any>()

    fun opening(surface: Any) {
        opening = surface
    }

    /** The open for [surface] became the live session. */
    fun published(surface: Any) {
        if (opening === surface) opening = null
        live = surface
    }

    /** The open for [surface] was superseded or failed; nothing was published. */
    fun abandoned(surface: Any) {
        if (opening === surface) opening = null
    }

    /**
     * Everything the stream holds is being torn down. Returns the surface involved, which the
     * caller must hand back to [released] once the engine has let go of it.
     */
    fun closing(): Any? {
        val held = live ?: opening
        live = null
        opening = null
        return held?.also(closing::add)
    }

    /** The teardown finished and [surface] may go back to the platform. */
    fun released(surface: Any) {
        closing.remove(surface)
    }

    /**
     * The surface currently holding the stream when that is not [surface] — an arriving view
     * has to take it over. `null` when nothing holds it, or when [surface] already does.
     *
     * Without this the arriving view never gets a stream at all: opening refuses while a
     * session is published, so it would render nothing while the controls sat live over it.
     */
    fun handoverFrom(surface: Any): Any? = (live ?: opening)?.takeIf { it !== surface }

    /** What to do with a surface the platform is destroying. */
    fun onDestroyed(surface: Any): DestroyOutcome = when {
        live === surface || opening === surface -> DestroyOutcome.TEAR_DOWN
        surface in closing -> DestroyOutcome.AWAIT_TEARDOWN
        else -> DestroyOutcome.RECLAIM
    }
}

/**
 * The three answers a destroyed surface can need, which is one more than a `Boolean` has.
 *
 * Collapsing [AWAIT_TEARDOWN] into [TEAR_DOWN] is what made a departing view kill the stream
 * the arriving one had just opened — the close it triggered tore down whatever was live, which
 * by then was the new screen's. Collapsing it into [RECLAIM] hands a surface back while the
 * native preview thread may still be writing into it.
 */
internal enum class DestroyOutcome {

    /** Nothing of ours is in that surface; the platform may take it back. */
    RECLAIM,

    /** The stream renders into it: tear the stream down, then release it. */
    TEAR_DOWN,

    /** Its teardown is already under way: keep it from the platform, but start nothing. */
    AWAIT_TEARDOWN,
}
