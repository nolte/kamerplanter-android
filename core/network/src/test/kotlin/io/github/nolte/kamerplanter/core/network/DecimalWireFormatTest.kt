package io.github.nolte.kamerplanter.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.junit.Assert.assertThrows
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
     * Asserted on the message, not only the type: a `SerializationException` is also what a
     * malformed *JSON* value produces, so a type-only expectation would stay green if the
     * branch stopped refusing and started failing for some unrelated reason.
     */
    private fun assertContainsFormatHint(thrown: SerializationException) {
        val message = thrown.message.orEmpty()
        require(message.contains("cannot be used with another format")) {
            "expected the format refusal, but was: $message"
        }
    }

    /** Accepts anything and records nothing — it exists only to not be a `JsonEncoder`. */
    @OptIn(ExperimentalSerializationApi::class)
    private class NotJsonEncoder : AbstractEncoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
    }

    /** Likewise, and never actually consulted: the refusal comes before any read. */
    @OptIn(ExperimentalSerializationApi::class)
    private class NotJsonDecoder : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            error("the serializer must refuse before reading anything")
    }
}
