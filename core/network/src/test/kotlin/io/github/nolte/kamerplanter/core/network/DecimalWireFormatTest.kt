package io.github.nolte.kamerplanter.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Pins the one promise [DecimalWireFormat] makes that no other test can reach.
 *
 * The serializer works by reading and writing raw JSON tokens, which is the only way to keep a
 * decimal exact — and that makes it useless to any other format. It says so by throwing. Two
 * earlier versions instead fell back to `decodeString`/`decodeDouble`, and both looked
 * plausible while quietly doing the wrong thing; the double-based one would have rounded every
 * value past a `Double`, reintroducing the very defect this class exists to fix.
 *
 * No non-JSON format is on this module's classpath, so nothing else exercises that branch. A
 * minimal encoder and decoder stand in for one, which is what makes the promise testable at
 * all rather than merely asserted in a comment.
 */
class DecimalWireFormatTest {

    private val json = NetworkModule.provideJson()

    @Test
    fun `refuses to encode through a format that is not JSON`() {
        val thrown = assertThrows(SerializationException::class.java) {
            DecimalWireFormat.serialize(NotJsonEncoder(), BigDecimal("12.50"))
        }

        assertContainsFormatHint(thrown)
    }

    @Test
    fun `refuses to decode through a format that is not JSON`() {
        val thrown = assertThrows(SerializationException::class.java) {
            DecimalWireFormat.deserialize(NotJsonDecoder())
        }

        assertContainsFormatHint(thrown)
    }

    /**
     * A value the instance sends that is not a decimal at all.
     *
     * Refused rather than coerced. A silent `0` here would be the worst available answer: an
     * area, a volume or a confidence of zero reads as a real measurement, and nothing
     * downstream could tell it apart from one.
     */
    @Test
    fun `refuses a JSON value that is not a decimal`() {
        val thrown = assertThrows(SerializationException::class.java) {
            json.decodeFromString(DecimalWireFormat, "\"n/a\"")
        }

        assertMessageContains(thrown, "'n/a' is not a decimal")
    }

    /** Likewise for a shape that is not a scalar at all. */
    @Test
    fun `refuses a composite JSON value`() {
        val thrown = assertThrows(SerializationException::class.java) {
            json.decodeFromString(DecimalWireFormat, "{\"value\": 12.5}")
        }

        assertMessageContains(thrown, "got a composite value")
    }

    /**
     * Asserted on the message rather than only on the type, and that is load-bearing here: the
     * stand-in encoder and decoder below throw `SerializationException` of their own for
     * anything they are asked to handle, so `assertThrows` alone passes even when the
     * serializer stops refusing — measured, not assumed. The message is what tells "the
     * serializer declined" apart from "the stand-in declined".
     */
    private fun assertMessageContains(thrown: SerializationException, expected: String) {
        val message = thrown.message.orEmpty()
        assertTrue("expected a message containing '$expected', but was: $message", expected in message)
    }

    private fun assertContainsFormatHint(thrown: SerializationException) =
        assertMessageContains(thrown, "cannot be used with another format")

    /**
     * Not a `JsonEncoder`, which is the only property that matters here.
     *
     * It handles nothing — `AbstractEncoder` refuses every value it is actually asked to
     * write — so reaching it at all is already a failure; see [assertMessageContains].
     */
    @OptIn(ExperimentalSerializationApi::class)
    private class NotJsonEncoder : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
    }

    /** Likewise, and never consulted: the refusal comes before any read. */
    @OptIn(ExperimentalSerializationApi::class)
    private class NotJsonDecoder : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            error("the serializer must refuse before reading anything")
    }
}
