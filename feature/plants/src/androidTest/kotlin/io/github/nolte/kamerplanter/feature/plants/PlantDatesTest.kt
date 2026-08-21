package io.github.nolte.kamerplanter.feature.plants

import android.Manifest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import androidx.test.rule.GrantPermissionRule
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.FakeConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient
import io.github.nolte.kamerplanter.core.network.PlantDataChanges
import io.github.nolte.kamerplanter.core.network.PlantPhase
import io.github.nolte.kamerplanter.core.network.PlantRemoval
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Long enough for the page's sections to load off fakes, short enough to fail fast. */
private const val WAIT_FOR_PAGE_MILLIS = 5_000L

/**
 * No raw backend timestamp reaches the screen.
 *
 * Found on the physical device, not here: a plant's page showed
 * `active_growth, seit 2026-08-14T13:22:45.710215Z` and a phase history reading
 * `· 2026-07-10T14:33:33.917380Z`. The formatter that prevents exactly this already existed in
 * the same file — `asLocalDate`, written for the diary with the reasoning spelled out in its
 * doc comment — it simply was not applied to the master data or the phase sections.
 *
 * This asserts the property rather than a particular formatting: no node anywhere on the page
 * may contain an ISO date-time marker. That survives a locale change, which an assertion on
 * "14.08.2026" would not, and it catches the next field that forgets the formatter rather than
 * only the three that did.
 */
@RunWith(JUnit4::class)
class PlantDatesTest {

    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun noRawIsoTimestampIsShownOnAPlantsPage() {
        val phase = PlantPhase(
            name = "active_growth",
            startedAt = "2026-08-14T13:22:45.710215Z",
            endedAt = null,
        )
        val past = PlantPhase(
            name = "germination",
            startedAt = "2026-07-10T14:33:33.917380Z",
            endedAt = "2026-08-14T13:22:45.710215Z",
        )
        val model = PlantDetailViewModel(
            imageClient = AuthenticatedImageClient(
                OkHttpClient(),
                InMemoryCredentialStore(),
                FakeConnectionStore(
                    Connection.ApiKey(baseUrl = "https://x", tenantSlug = "demo", keyHint = "…x"),
                ),
            ),
            sources = PlantPageSources(
                page = FakePageClient(
                    detail = plantDetail(
                        plantedOn = "2026-07-10",
                        phase = phase,
                        removal = PlantRemoval(
                            removedOn = "2026-08-20",
                            type = "loss",
                            cause = null,
                        ),
                    ),
                    phases = listOf(past),
                ),
                actions = FakeActionsClient(listOf(diaryEntry("d1", "Repotted."))),
                // Every dated field the page can render, in one pass: master data, both phase
                // lines, the removal notice and a past check. Each of the last two printed a
                // raw value until pre-merge review caught them.
                detections = QuietDetectionClient(
                    pastChecks = listOf(pastCheck(recordedAt = "2026-08-18T09:15:00Z")),
                ),
            ),
            camera = FakeCamera(),
            changes = PlantDataChanges(),
            savedStateHandle = SavedStateHandle(
                mapOf(PlantDetailViewModel.PLANT_KEY_ARG to "p1"),
            ),
        )

        composeRule.setContent {
            PlantDetailScreen(onBack = {}, onDetectPests = {}, viewModel = model)
        }
        composeRule.waitUntil(timeoutMillis = WAIT_FOR_PAGE_MILLIS) {
            composeRule.onAllNodesWithText("Repotted.").fetchSemanticsNodes().isNotEmpty()
        }

        // Every text the page renders, matched against the shape of a wire timestamp rather
        // than against the two the fixture happens to use. A hardcoded pair would pass
        // silently the moment a fourth date field arrives with a different clock time — the
        // exact blind spot this test exists to close.
        val offenders = composeRule.onRoot().fetchSemanticsNode()
            .everyText()
            .filter { ISO_CLOCK.containsMatchIn(it) }

        assertTrue(
            "raw backend timestamps reached the screen: $offenders",
            offenders.isEmpty(),
        )
    }
}

/**
 * The two shapes a wire date takes: a full ISO date-time, and a bare ISO calendar day.
 *
 * The clock component catches `2026-08-14T13:22:45.710215Z`. The bare day catches
 * `2026-08-20`, which a `LocalDate` field arrives as and which carries no clock at all — the
 * gap that let the removal notice through the first version of this test.
 *
 * The bare-day pattern assumes the device does not itself format dates as ISO. That holds for
 * the managed device (en-US, "Aug 20, 2026") and for de-DE; a locale like sv-SE would need
 * this narrowed, and the failure would be loud rather than silent.
 */
private val ISO_CLOCK = Regex("""T\d{2}:\d{2}|\b\d{4}-\d{2}-\d{2}\b""")

/**
 * Every string the whole subtree can put in front of a reader.
 *
 * Walks the children rather than querying a matcher, so a field nobody thought to look for is
 * still covered — which is the point of asserting the property instead of naming the offenders.
 * Both channels count: what is drawn, and what a screen reader announces.
 */
private fun SemanticsNode.everyText(): List<String> =
    config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        children.flatMap { it.everyText() }
