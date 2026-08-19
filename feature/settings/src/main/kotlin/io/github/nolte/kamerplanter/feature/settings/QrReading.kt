package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.PayloadRefusal

/**
 * What the app made of a QR code the camera decoded.
 *
 * Exists so the scanner can say something back. A scan that finds a foreign code and one that
 * finds nothing at all look identical from the outside — the screen keeps scanning either way —
 * which makes a parser that reads the wrong payload format indistinguishable from a camera
 * that never focused. Returning the verdict rather than logging it also puts it where a JVM
 * test can assert on it.
 *
 * The refusals are carried as [PayloadRefusal] rather than restated as cases of their own.
 * They were restated, and that is one half of what #40 was about: the reason a code is refused
 * belongs to the payload contract, and every layer that re-enumerated it was a layer a new
 * reason could be forgotten in.
 */
sealed interface QrReading {

    /** A kamerplanter payload. The connection attempt has started. */
    data object Accepted : QrReading

    /** A QR code, but not a kamerplanter one at all — someone else's. */
    data object Foreign : QrReading

    /**
     * A kamerplanter code this build will not act on, and why.
     *
     * The reason is the same value the deep-link channel carries, worded from the same place,
     * so the identical URL cannot explain itself through one entry point and stay silent in
     * the other.
     */
    data class Refused(val reason: PayloadRefusal) : QrReading

    /**
     * Decoded after the scan had already ended: the frame that carried the accepted code is
     * usually followed by more of the same before the camera unbinds. Not worth reporting.
     */
    data object Stale : QrReading
}
