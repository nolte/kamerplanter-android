package io.github.nolte.kamerplanter.feature.pestdetection

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.nolte.kamerplanter.core.network.DetectionOutcome
import io.github.nolte.kamerplanter.core.network.PlantDataChanges
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Long enough for an upload round trip against a fake, short enough to fail fast. */
private const val WAIT_FOR_RESULT_MILLIS = 5_000L

/**
 * What issue #10 requires the *result* to say, asserted on the rendered screen.
 *
 * The view-model suite already covers which call goes out, in what order, and what the app
 * does with each outcome. None of that answers the question these three criteria ask, which is
 * about what a person ends up looking at:
 *
 * - a beneficial must never be presented as something to act against,
 * - `is_confident: false` is a first-class answer, not an error and not a blank screen,
 * - the instance's disclaimer is always shown, verbatim.
 *
 * The first is the one mistake this feature must not make: a beneficial rendered as a pest is
 * how a predatory mite gets sprayed.
 *
 * These drive the phone path, so the microscope fake stays unattached throughout.
 */
@RunWith(JUnit4::class)
class DetectionResultRenderingTest {

    /**
     * The screen asks for CAMERA as soon as a capture could follow, and a system dialogue over
     * the app makes its Compose hierarchy invisible to the test — the same trap the app
     * module's launch test fell into. Outermost, so it wraps the composition.
     */
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val detections = FakeDetectionClient()

    /**
     * Renders the screen and drives it to a result the short way: readiness, phone source,
     * shutter, capture.
     *
     * Waits on the rendered text rather than on a scheduler — there is no test dispatcher
     * here, because Compose needs the real main looper.
     */
    private fun showResult(outcome: DetectionOutcome, awaitText: String) {
        detections.outcome = outcome
        val model = PestDetectionViewModel(
            detections,
            FakeCamera(),
            PlantDataChanges(),
            SavedStateHandle(emptyMap()),
        )

        composeRule.setContent {
            PestDetectionScreen(onOpenSettings = {}, viewModel = model)
        }

        composeRule.runOnIdle {
            model.chooseSource(CaptureSource.PHONE)
            model.phoneShutter = FakeShutter()
            model.capture("en")
        }

        composeRule.waitUntil(timeoutMillis = WAIT_FOR_RESULT_MILLIS) {
            composeRule.onAllNodesWithText(awaitText).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * A beneficial is named as one and paired with "do not treat".
     *
     * Asserted on the note rather than only on the badge: a badge alone leaves the reader to
     * infer what it means for what they should do next, and the inference this feature cannot
     * afford is "found something — treat it".
     */
    @Test
    fun aBeneficialIsShownAsABeneficialWithADoNotTreatNote() {
        val note = resources.getString(R.string.pest_beneficial_note)
        showResult(
            outcome = DetectionOutcome.Completed(
                detection(
                    findings = listOf(
                        finding(label = "ladybird", commonName = "Ladybird", isBeneficial = true),
                    ),
                ),
            ),
            awaitText = note,
        )

        composeRule.onNodeWithText(note).assertIsDisplayed()
        composeRule
            .onNodeWithText(resources.getString(R.string.pest_beneficial_badge))
            .assertIsDisplayed()
    }

    /** An unsure instance says so. Not an error, not an empty findings list. */
    @Test
    fun anInstanceThatIsNotConfidentSaysSoInsteadOfShowingNothing() {
        val abstained = resources.getString(R.string.pest_abstained_title)
        showResult(
            outcome = DetectionOutcome.Completed(detection(isConfident = false)),
            awaitText = abstained,
        )

        composeRule.onNodeWithText(abstained).assertIsDisplayed()
    }

    /**
     * The disclaimer travels verbatim.
     *
     * It is the instance's wording, not the app's, and the app must not paraphrase it into
     * something that reads like a diagnosis. Asserted against a string this test invents, so a
     * screen that quietly substituted its own text would fail here.
     */
    @Test
    fun theInstancesDisclaimerIsShownWordForWord() {
        val disclaimer = "This instance guesses; a guess is not a diagnosis."
        showResult(
            outcome = DetectionOutcome.Completed(
                detection(findings = listOf(finding()), disclaimer = disclaimer),
            ),
            awaitText = disclaimer,
        )

        composeRule.onNodeWithText(disclaimer).assertIsDisplayed()
    }
}
