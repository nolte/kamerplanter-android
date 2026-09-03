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
 * day; and the suffix is the current time in milliseconds modulo 36³, in three base-36
 * characters. `MONST-0713-WG7`, `AGLAO-0617-RB5`.
 *
 * Two things the web UI does not do and this form does: the location plays no part in the
 * identifier there either, so the planting-run convention's location segment is gone; and
 * the suffix is stepped past any identifier already in use (R21) — the web UI trusts the
 * clock alone. The field stays editable throughout, and a proposal never overwrites what
 * the user typed.
 */
internal fun proposeInstanceId(speciesName: String?, today: LocalDate, nowMillis: Long, taken: Set<String>): String {
    val prefix = speciesName.orEmpty().sanitized().take(PREFIX_LENGTH).ifEmpty { FALLBACK_PREFIX }
    val date = "%02d%02d".format(today.monthValue, today.dayOfMonth)
    var tick = nowMillis
    while (true) {
        val candidate = "$prefix-$date-${tick.suffix()}"
        if (candidate !in taken) return candidate
        tick++
    }
}

/** Diacritics folded away, anything but `A–Z` and `0–9` dropped, the rest upper-cased. */
private fun String.sanitized(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    .uppercase()

private fun Long.suffix(): String =
    (this % SUFFIX_SPACE).toString(SUFFIX_RADIX).uppercase().padStart(SUFFIX_LENGTH, '0')

private const val PREFIX_LENGTH = 5
private const val FALLBACK_PREFIX = "PLANT"
private const val SUFFIX_RADIX = 36
private const val SUFFIX_LENGTH = 3

/** 36³ — every three-character base-36 suffix, as the web UI takes it. */
private const val SUFFIX_SPACE = 46656L
