package io.github.nolte.kamerplanter.feature.plants.capture

/**
 * The identifier the form proposes for a new plant (R19–R21).
 *
 * Follows the instance's own planting-run convention `{LOCATION_KEY}_{PREFIX}_{SEQ:02d}`, the
 * only generation rule the backend has: the prefix is the first three `A`–`Z` letters of the
 * species key, at least two, and the sequence is the lowest one not already in use. The
 * location segment is left out when no location is chosen — a deliberate deviation, because
 * this form makes the location optional where the planting run does not (R20).
 *
 * `null` while there is no species to derive a prefix from; the field stays editable either
 * way, and a proposal never overwrites what the user typed. [speciesKey] may also be a
 * scientific name, for a species the recogniser named and the instance has not created yet
 * (R25): the instance derives its keys from that name, so the leading letters agree.
 */
internal fun proposeInstanceId(speciesKey: String?, locationKey: String?, taken: Set<String>): String? {
    val letters = speciesKey.orEmpty().filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase()
    val prefix = letters.take(PREFIX_LENGTH).takeIf { it.length >= PREFIX_MINIMUM } ?: return null
    val head = listOfNotNull(locationKey?.takeIf { it.isNotBlank() }, prefix).joinToString("_")
    var sequence = 1
    while ("${head}_${sequence.toString().padStart(SEQUENCE_DIGITS, '0')}" in taken) sequence++
    return "${head}_${sequence.toString().padStart(SEQUENCE_DIGITS, '0')}"
}

private const val PREFIX_LENGTH = 3
private const val PREFIX_MINIMUM = 2
private const val SEQUENCE_DIGITS = 2
