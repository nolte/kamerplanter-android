package io.github.nolte.kamerplanter.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal

/**
 * Reads the decimals kamerplanter actually sends.
 *
 * The generator maps every unformatted `number` in the schema to [BigDecimal] and hands it an
 * adapter that calls `decodeString()` — so it only accepts a *quoted* decimal. The backend
 * types those fields as Python `float` and FastAPI writes them as bare JSON numbers, so the
 * generated adapter cannot read a single one of them. `confidence: 0.87` fails with
 * `Expected quotation mark '"', but had '0'`, and because kotlinx fails the whole document,
 * one such field costs the entire response.
 *
 * Twenty generated models carry such a field, and two of them are already on live paths:
 * `LocationResponse.area_m2` (required — so *every* location lookup fails, which the plant
 * list swallows into unresolved location names) and `PlantResponse.container_volume_liters`
 * (nullable, but a plant that has one sinks the whole plant list). Nobody saw it because the
 * fixtures quoted the numbers, which is the one spelling the generated adapter accepts.
 *
 * This reads either spelling and writes a bare number, which is what the backend's `float`
 * fields expect back.
 */
internal object DecimalWireFormat : KSerializer<BigDecimal> {

    // DOUBLE rather than STRING: this is a number, and the non-JSON fallbacks below read and
    // write it as one, so the descriptor and the behaviour say the same thing.
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.DOUBLE)

    /**
     * Accepts `0.87` and `"0.87"` alike.
     *
     * Both are read through [JsonPrimitive.content], which is the raw token either way — no
     * `Double` round trip, so a decimal the backend spelled exactly stays exact.
     */
    override fun deserialize(decoder: Decoder): BigDecimal {
        val json = decoder as? JsonDecoder ?: return decoder.decodeDouble().toBigDecimal()
        val primitive = json.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("expected a decimal, got a composite value")
        return primitive.content.toBigDecimalOrNull()
            ?: throw SerializationException("'${primitive.content}' is not a decimal")
    }

    /**
     * Writes the exact decimal as a bare JSON number.
     *
     * [JsonUnquotedLiteral] rather than `JsonPrimitive(Number)`, and the difference is not
     * cosmetic: a plain numeric primitive is written by parsing the literal back into a
     * `Double` first, which silently rounds anything past a Double's precision and throws
     * outright on a magnitude Double cannot hold. Reading keeps the value exact, so writing
     * has to as well — otherwise a value read from one endpoint and PUT back to another (a
     * care profile's multiplier, say) goes out quietly changed.
     */
    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val json = encoder as? JsonEncoder ?: return encoder.encodeDouble(value.toDouble())
        json.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
    }
}
