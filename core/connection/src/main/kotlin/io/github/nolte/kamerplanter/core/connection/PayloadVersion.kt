package io.github.nolte.kamerplanter.core.connection

/**
 * The payload version space the pairing QR and the `/connect` link share (R7).
 *
 * One declaration because there were two, in two modules and two types: the link parser
 * compared the version as a string while the pairing parser read it as a number, so
 * `?v=01` was refused by one and accepted by the other and the scanner reported a
 * supported link as somebody else's code. Two constants also meant a future bump could be
 * applied to one and forgotten in the other, which turns officially supported codes
 * foreign on exactly the release that introduces them.
 */
object PayloadVersion {

    /** The first version kamerplanter published; nothing below it is one of its payloads. */
    const val FIRST = 1

    /**
     * The version this build reads.
     *
     * A version this build has never heard of describes a shape it cannot read, and reading
     * one anyway is how a client acts on a field that has changed meaning — with a one-time
     * credential in it.
     */
    const val SUPPORTED = 1
}
