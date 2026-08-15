package io.github.nolte.kamerplanter.feature.microscope

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts how many screens want the camera running.
 *
 * The camera is a singleton and its observers are screen-scoped, so more than one can hold it
 * at once — and Compose composes an incoming destination *before* disposing the outgoing one.
 * Without counting, navigating between two screens that both drive the camera runs `start()`
 * and then `stop()`, in that order, leaving the hardware off with nothing left to switch it
 * back on: the incoming screen's `DisposableEffect(Unit)` has already run and will not run
 * again. The symptom is a screen insisting no microscope is attached while one is plugged in.
 *
 * Its own class so the ordering can be tested without a device, a `Context` or a surface.
 */
internal class Holders {

    private val count = AtomicInteger(0)

    /** True when this is the first holder, i.e. the caller must actually start the hardware. */
    fun acquire(): Boolean = count.getAndIncrement() == 0

    /**
     * True when the last holder let go, i.e. the caller must actually stop the hardware.
     *
     * Clamped at zero, so an unpaired release — a screen disposed without ever having started,
     * or a `stop()` arriving twice — cannot push the count negative and turn the next genuine
     * release into a no-op.
     */
    fun release(): Boolean {
        if (count.get() == 0) return false
        return count.updateAndGet { (it - 1).coerceAtLeast(0) } == 0
    }
}
