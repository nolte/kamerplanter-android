package io.github.nolte.kamerplanter.feature.pestdetection

import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.Finding
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three answers a detection can be, and why they are three rather than two.
 *
 * `is_confident` and an empty findings list are independent signals, and the obvious reading —
 * `isConfident && findings.isNotEmpty()` — folds "I could not tell" together with "I looked and
 * found nothing". Those are opposite statements about the same plant: one leaves it unexamined,
 * the other says it is fine. A user who reads the first as the second stops looking.
 */
class DetectionShapeTest {

    @Test
    fun `a confident detection with findings shows them`() {
        assertEquals(
            DetectionShape.FINDINGS,
            detection(isConfident = true, findings = listOf(finding())).outcome(),
        )
    }

    @Test
    fun `an unconfident detection abstains`() {
        assertEquals(
            DetectionShape.ABSTAINED,
            detection(isConfident = false, findings = emptyList()).outcome(),
        )
    }

    /**
     * The case the folded version gets wrong, and the reason this function exists: the
     * recognizer answered, and its answer was "nothing here".
     */
    @Test
    fun `a confident detection with no findings found nothing`() {
        assertEquals(
            DetectionShape.NOTHING_FOUND,
            detection(isConfident = true, findings = emptyList()).outcome(),
        )
    }

    /**
     * An abstention that still carries findings stays an abstention.
     *
     * The backend abstains on the confidence threshold, and nothing stops it from listing what
     * it saw below that threshold. Those are exactly the findings that must not be presented as
     * a result — listing them under "what your instance recognized" would promote a guess the
     * recognizer explicitly refused to stand behind.
     */
    @Test
    fun `an unconfident detection abstains even when it lists findings`() {
        assertEquals(
            DetectionShape.ABSTAINED,
            detection(isConfident = false, findings = listOf(finding())).outcome(),
        )
    }

    private fun detection(isConfident: Boolean, findings: List<Finding>) = Detection(
        key = "det-1",
        isConfident = isConfident,
        findings = findings,
        disclaimer = "Only an estimate.",
        suggestedNextStep = "Look again",
        tilesProcessed = 4,
    )

    private fun finding() = Finding(
        label = "tetranychus_urticae",
        commonName = "Spider mite",
        category = "pest",
        confidence = 0.3,
        mode = "direct",
        boundingBox = null,
        isBeneficial = false,
    )
}
