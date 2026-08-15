package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.network.generated.models.LocationResponse
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
 * The three things [DecimalWireFormat] refuses, none of which any other test reaches.
 *
 * Two of them are values an instance could send where a decimal belongs — a string that is not
 * a number, and a composite. Coercing either to `0` would be the worst available answer: a
 * zero area, volume or confidence reads as a measurement.
 *
 * The third is being used by a format that is not JSON. The serializer works by reading and
 * writing raw JSON tokens, which is the only way to keep a decimal exact, and that makes it
 * useless elsewhere; two earlier versions instead fell back to `decodeString`/`decodeDouble`,
 * both plausible-looking and both quietly wrong — the double-based one would have rounded every
 * value past a `Double`, reintroducing the defect this class exists to fix. No non-JSON format
 * is on this module's classpath, so a minimal encoder and decoder stand in for one, which is
 * what makes that promise testable rather than merely asserted in a comment.
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
     * A value the instance sends where a decimal belongs.
     *
     * Refused rather than coerced. A silent `0` would be the worst available answer: an area, a
     * volume or a confidence of zero reads as a real measurement, and nothing downstream could
     * tell it apart from one.
     *
     * Driven through a generated DTO rather than through the serializer directly, so the
     * registration is part of what is under test: handing `decodeFromString` an explicit
     * serializer bypasses the contextual lookup entirely, and a test that did so would keep
     * passing if [DecimalWireFormat] stopped being the serializer this app actually uses.
     */
    @Test
    fun `refuses a value that is not a decimal`() {
        val thrown = assertThrows(SerializationException::class.java) {
            json.decodeFromString<LocationResponse>(location("\"n/a\""))
        }

        assertMessageContains(thrown, "'n/a' is not a decimal")
    }

    /** Likewise for a shape that is not a scalar at all. */
    @Test
    fun `refuses a composite value where a decimal belongs`() {
        val thrown = assertThrows(SerializationException::class.java) {
            json.decodeFromString<LocationResponse>(location("{\"value\": 12.5}"))
        }

        assertMessageContains(thrown, "got a composite value")
    }

    private fun location(area: String) = """
        {
          "key": "loc-1",
          "name": "Living room",
          "site_key": "site-1",
          "area_m2": $area,
          "dimensions": [],
          "irrigation_system": "manual",
          "light_type": "natural",
          "orientation": null
        }
    """.trimIndent()

    /**
     * Asserted on the message rather than only on the type — measured per test, because it
     * carries a different amount of weight in each.
     *
     * For the two **format** tests it is the whole assertion: the stand-ins below throw
     * `SerializationException` of their own for any value they are handed, so `assertThrows`
     * passes even with the old fallbacks restored. Neutralise this helper and those two tests
     * cannot fail at all.
     *
     * For the two **value** tests it is narrower than it looks. Against a serializer that
     * coerced instead of refusing, `assertThrows` alone would catch it — nothing is thrown.
     * What the message adds is the case where *another* decimal serializer refuses on the same
     * field: drop the registration and the composite value still raises a
     * `SerializationException`, from the generated adapter, on `$.area_m2`. Only the wording
     * separates "this serializer declined" from "some other one did".
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

    /** Likewise, and never consulted — its `error(…)` would report an unrelated failure. */
    @OptIn(ExperimentalSerializationApi::class)
    private class NotJsonDecoder : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            error("the serializer must refuse before reading anything")
    }
}
