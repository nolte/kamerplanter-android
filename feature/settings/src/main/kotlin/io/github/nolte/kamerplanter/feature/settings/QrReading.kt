package io.github.nolte.kamerplanter.feature.settings

/**
 * What the app made of a QR code the camera decoded.
 *
 * Exists so the scanner can say something back. A scan that finds a foreign code and one that
 * finds nothing at all look identical from the outside — the screen keeps scanning either way —
 * which makes a parser that reads the wrong payload format indistinguishable from a camera
 * that never focused. Returning the verdict rather than logging it also puts it where a JVM
 * test can assert on it.
 */
enum class QrReading {

    /** A kamerplanter payload. The connection attempt has started. */
    ACCEPTED,

    /** A QR code, but not one this app can act on — someone else's code, or a newer payload. */
    FOREIGN,

    /**
     * Decoded after the scan had already ended: the frame that carried the accepted code is
     * usually followed by more of the same before the camera unbinds. Not worth reporting.
     */
    STALE,
}
