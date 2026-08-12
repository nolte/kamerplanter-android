package io.github.nolte.kamerplanter.feature.settings

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses the (fingierte) kamerplanter pairing QR code into a [PairingPayload].
 *
 * The dummy's canonical QR text is a custom-scheme URI:
 *
 * ```
 * kamerplanter://pair?url=<percent-encoded base URL>&code=<pairing code>
 * ```
 *
 * Deliberately pure Kotlin (no `android.net.Uri`) so it is unit-testable on the JVM.
 * Any input that is not this exact shape — a foreign QR, a bare string, a missing field —
 * yields `null`, which the caller treats as "invalid, keep scanning" (requirement R15).
 * The real wire format is owned by kamerplanter#1118; this parser is intentionally minimal.
 */
object QrPayloadParser {

    private const val SCHEME = "kamerplanter"
    private const val HOST = "pair"
    private const val PARAM_URL = "url"
    private const val PARAM_CODE = "code"

    fun parse(raw: String): PairingPayload? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
        // For `scheme://pair?...`, the authority carries the "pair" host.
        val host = uri?.host ?: uri?.authority
        val params = parseQuery(uri?.rawQuery)
        val baseUrl = params[PARAM_URL]?.takeIf { it.isNotBlank() }
        val code = params[PARAM_CODE]?.takeIf { it.isNotBlank() }

        val isPairingUri = uri != null &&
            SCHEME.equals(uri.scheme, ignoreCase = true) &&
            HOST.equals(host, ignoreCase = true)

        return if (isPairingUri && baseUrl != null && code != null) {
            PairingPayload(baseUrl = baseUrl, code = code)
        } else {
            null
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = pair.substring(0, separator)
            val value = decode(pair.substring(separator + 1))
            key to value
        }.toMap()
    }

    // Protect a literal '+' before decoding: URLDecoder treats '+' as a space (form-encoding),
    // which would silently corrupt a base64-ish pairing code or a URL that contains one.
    // A real space is transmitted as %20 and still decodes correctly.
    private fun decode(value: String): String =
        runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
}
