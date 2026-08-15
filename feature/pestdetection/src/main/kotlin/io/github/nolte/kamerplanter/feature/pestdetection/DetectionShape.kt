package io.github.nolte.kamerplanter.feature.pestdetection

import io.github.nolte.kamerplanter.core.network.Detection

/**
 * What a detection amounts to, once its two independent signals are read together.
 *
 * `is_confident` and an empty findings list are not the same statement, and the difference is
 * the one the user acts on: "I could not tell" leaves the plant unexamined, while "I looked
 * and found nothing" says it is fine. Collapsing them — the obvious `isConfident && findings
 * .isNotEmpty()` — would tell someone their plant is healthy on the strength of a recognizer
 * that declined to answer.
 */
internal enum class DetectionShape {
    FINDINGS,

    /** The recognizer declined: nothing cleared its threshold. */
    ABSTAINED,

    /** The recognizer answered confidently, and there was nothing to report. */
    NOTHING_FOUND,
}

internal fun Detection.outcome(): DetectionShape = when {
    !isConfident -> DetectionShape.ABSTAINED
    findings.isEmpty() -> DetectionShape.NOTHING_FOUND
    else -> DetectionShape.FINDINGS
}
