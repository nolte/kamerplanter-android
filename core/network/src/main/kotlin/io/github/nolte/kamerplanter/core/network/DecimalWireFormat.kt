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

    // The value is a number, so the descriptor says so. It describes the value, not the
    // transport: both directions below work on the raw token, because that is the only way to
    // keep a decimal exact — see [serialize].
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.DOUBLE)

    /**
     * Accepts `0.87` and `"0.87"` alike.
     *
     * Both are read through [JsonPrimitive.content], which is the raw token either way — no
     * `Double` round trip, so a decimal the backend spelled exactly stays exact.
     */
    override fun deserialize(decoder: Decoder): BigDecimal {
        val json = decoder as? JsonDecoder ?: throw jsonOnly()
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
     *
     * `toString` rather than `toPlainString`: JSON allows an exponent, and the plain form of a
     * large negative scale is one digit per power of ten. `1E+2000000000` is a thirteen-byte
     * token whose plain form does not fit in a heap — measured, it is an `OutOfMemoryError`
     * rather than a failed request. kamerplanter's own `float` fields cannot produce that (such
     * a value would already be `inf` on their side), so this guards against a proxy, a future
     * field or a hostile answer rather than against the backend itself. Cheap enough not to
     * need a better excuse.
     *
     * The cost is a wire-format change for very small values: Java switches `toString` to
     * exponent form below `1e-6`, so an instance that sent `0.0000001` gets `1E-7` back. Same
     * number, valid JSON, and accepted by pydantic on both its parsing paths — verified rather
     * than assumed. Areas, volumes and confidences all sit well above that threshold.
     */
    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val json = encoder as? JsonEncoder ?: throw jsonOnly()
        json.encodeJsonElement(JsonUnquotedLiteral(value.toString()))
    }

    /**
     * This serializer exists to work around one JSON encoding, and it does that by reading and
     * writing raw JSON tokens — there is nothing left of it for another format.
     *
     * Refusing loudly rather than falling back through `decodeDouble`/`encodeDouble`: that
     * fallback would compile, look coherent against the descriptor, and quietly round every
     * value past a `Double` — reintroducing exactly the defect this class was written to fix,
     * in the one place no test would be watching. No non-JSON format is on this module's
     * classpath today, so this is unreachable; it is here so that adding one fails visibly.
     */
    private fun jsonOnly() = SerializationException(
        "DecimalWireFormat encodes raw JSON tokens and cannot be used with another format",
    )
}
