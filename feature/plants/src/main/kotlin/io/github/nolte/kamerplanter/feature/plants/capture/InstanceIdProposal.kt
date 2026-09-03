package io.github.nolte.kamerplanter.feature.plants.capture

import java.text.Normalizer
import java.time.LocalDate

/**
 * The identifier the form proposes for a new plant (R19–R21).
 *
 * The same rule the web UI's `idGenerator.ts` applies when it creates a single plant, so a
 * plant added from the phone sits beside one added from the browser without looking like it
 * came from somewhere else: `{PREFIX}-{MMDD}-{SUFFIX}`, where the prefix is the first five
 * letters or digits of the species' scientific name — diacritics folded, everything else
 * dropped, upper-cased — or `PLANT` while no species is chosen; the date is today's month and
 * day; and the suffix is the clock in milliseconds modulo 36³, in three base-36 characters.
 * `MONST-0713-WG7`, `AGLAO-0617-RB5`. The location plays no part, there or here.
 *
 * One thing the web UI leaves to the clock and this form does not: the suffix is stepped past
 * any identifier already in use (R21). Every suffix taken — which no instance can reach through
 * the paged plant list this is checked against — falls back to the clock's own pick, as the
 * web UI would. The field stays editable throughout, and a proposal never overwrites what the
 * user typed.
 *
 * The digits are ASCII by construction: `String.format` would take the device locale's digit
 * set, and an identifier written in Arabic-Indic digits matches neither the web UI's shape nor
 * the identifiers already on the instance.
 */
internal fun proposeInstanceId(speciesName: String?, today: LocalDate, clockMillis: Long, taken: Set<String>): String {
    val prefix = speciesName.orEmpty().sanitized().take(PREFIX_LENGTH).ifEmpty { FALLBACK_PREFIX }
    val date = today.monthValue.twoDigits() + today.dayOfMonth.twoDigits()
    for (step in 0 until SUFFIX_SPACE) {
        val candidate = "$prefix-$date-${(clockMillis + step).suffix()}"
        if (candidate !in taken) return candidate
    }
    return "$prefix-$date-${clockMillis.suffix()}"
}

/** Diacritics folded away, anything but `A–Z` and `0–9` dropped, the rest upper-cased. */
private fun String.sanitized(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    .uppercase()

private fun Int.twoDigits(): String = toString().padStart(2, '0')

/** Floor modulo, so a clock before 1970 still yields three characters and no sign. */
private fun Long.suffix(): String =
    mod(SUFFIX_SPACE).toString(SUFFIX_RADIX).uppercase().padStart(SUFFIX_LENGTH, '0')

private const val PREFIX_LENGTH = 5
private const val FALLBACK_PREFIX = "PLANT"
private const val SUFFIX_RADIX = 36
private const val SUFFIX_LENGTH = 3

/** 36³ — every three-character base-36 suffix, as the web UI takes it. */
private const val SUFFIX_SPACE = 36L * 36 * 36
