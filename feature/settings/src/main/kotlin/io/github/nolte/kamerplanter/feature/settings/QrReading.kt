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

    /** A QR code, but not a kamerplanter one at all — someone else's. */
    FOREIGN,

    /** A kamerplanter code newer than this build: the app is behind, not the code. */
    TOO_NEW,

    /** A kamerplanter code older than this build reads: the instance is behind. */
    TOO_OLD,

    /**
     * A kamerplanter code naming an instance reached without TLS on a routable address.
     *
     * Told it was "not a kamerplanter code", a self-hoster would look for the fault in their
     * instance rather than in this app's rule about unencrypted connections.
     */
    ADDRESS_NOT_ENCRYPTED,

    /** A kamerplanter code naming an address this app cannot use at all. */
    ADDRESS_UNUSABLE,

    /**
     * Decoded after the scan had already ended: the frame that carried the accepted code is
     * usually followed by more of the same before the camera unbinds. Not worth reporting.
     */
    STALE,
}
