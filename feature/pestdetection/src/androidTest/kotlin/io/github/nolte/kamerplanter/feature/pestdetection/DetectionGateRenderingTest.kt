package io.github.nolte.kamerplanter.feature.pestdetection

import android.Manifest
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.nolte.kamerplanter.core.network.ConsentTerms
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.PlantDataChanges
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Locale

/** Long enough for the readiness probe against a fake, short enough to fail fast. */
private const val WAIT_FOR_GATE_MILLIS = 5_000L

/**
 * What the availability gate (F-4) says *before* a picture is ever taken, asserted on the
 * rendered screen.
 *
 * The view-model suite pins that `NotOffered` and `ConsentRequired` are states of their own
 * and that the consent terms cross the module boundary untouched. Neither answers what a
 * person reads: whether an absent entry point comes with a reason (acceptance-2), and whether
 * the consent they are asked for is worded by their instance rather than by this app
 * (acceptance-5) — an Art. 6(1)(a) GDPR consent to text the app invented would be a consent
 * to nothing.
 */
@RunWith(JUnit4::class)
class DetectionGateRenderingTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val detections = FakeDetectionClient()

    /** Renders the screen against [readiness] and waits for [awaitText] to appear. */
    private fun showGate(readiness: DetectionReadiness, awaitText: String) {
        detections.readiness = readiness
        val model = PestDetectionViewModel(
            detections,
            FakeCamera(),
            PlantDataChanges(),
            FakePlantActions(),
            SavedStateHandle(emptyMap()),
        )

        composeRule.setContent {
            PestDetectionScreen(onOpenSettings = {}, viewModel = model)
        }

        composeRule.waitUntil(timeoutMillis = WAIT_FOR_GATE_MILLIS) {
            composeRule.onAllNodesWithText(awaitText).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** F-4 acceptance-2: an entry point that is not offered comes with the reason. */
    @Test
    fun anInstanceThatDoesNotOfferDetectionSaysWhy() {
        val why = resources.getString(R.string.pest_not_offered_body)

        showGate(DetectionReadiness.NotOffered, awaitText = why)

        composeRule.onNodeWithText(why).assertIsDisplayed()
    }

    /**
     * F-4 acceptance-5: purpose, description and legal basis are the instance's own words.
     *
     * Asserted against strings this test invents, so a screen that quietly substituted its
     * own wording for any of the three would fail here.
     */
    @Test
    fun theConsentIsWordedByTheInstanceNotByTheApp() {
        val terms = ConsentTerms(
            label = "Cloud pest recognition",
            description = "Your photo is sent to a recognition service outside this instance.",
            legalBasis = "Art. 6(1)(a) GDPR — consent",
        )

        showGate(
            DetectionReadiness.ConsentRequired("pest_detection_cloud", terms),
            awaitText = terms.label,
        )

        composeRule.onNodeWithText(terms.label).assertIsDisplayed()
        composeRule.onNodeWithText(terms.description).assertIsDisplayed()
        composeRule
            .onNodeWithText(resources.getString(R.string.pest_consent_basis, terms.legalBasis))
            .assertIsDisplayed()
    }

    /**
     * F-2 acceptance-4's data half: the language the detection asks for is a per-locale
     * resource, and this pins each shipped locale to its own code — a copied folder that kept
     * the neighbour's value would fail here, where no lint looks.
     */
    @Test
    fun theResultLanguageFollowsTheLocaleTheStringsResolvedIn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun languageIn(locale: Locale): String {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
            return context.createConfigurationContext(configuration).getString(R.string.pest_result_language)
        }

        assertEquals("en", languageIn(Locale.ENGLISH))
        assertEquals("de", languageIn(Locale.GERMAN))
        // A locale the app does not ship falls back to the default strings — and to their language.
        assertEquals("en", languageIn(Locale("nl")))
    }

    /** Only where the instance supplied no wording at all does the app fall back to its own. */
    @Test
    fun anInstanceWithoutConsentWordingGetsTheAppsFallback() {
        val fallback = resources.getString(R.string.pest_consent_title)

        showGate(
            DetectionReadiness.ConsentRequired("pest_detection_cloud", terms = null),
            awaitText = fallback,
        )

        composeRule.onNodeWithText(fallback).assertIsDisplayed()
    }
}
