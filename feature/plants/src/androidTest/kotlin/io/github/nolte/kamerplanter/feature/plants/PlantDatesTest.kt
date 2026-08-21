package io.github.nolte.kamerplanter.feature.plants

import android.Manifest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.rule.GrantPermissionRule
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.FakeConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient
import io.github.nolte.kamerplanter.core.network.PlantDataChanges
import io.github.nolte.kamerplanter.core.network.PlantPhase
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
                    detail = plantDetail(plantedOn = "2026-07-10", phase = phase),
                    phases = listOf(past),
                ),
                actions = FakeActionsClient(listOf(diaryEntry("d1", "Repotted."))),
                detections = QuietDetectionClient(),
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

        // The clock portion of each timestamp the fakes fed in. A formatted date never
        // carries it; a string printed as the instance wrote it always does.
        val offenders = ISO_CLOCK_MARKERS.filter { marker ->
            composeRule.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes()
                .isNotEmpty()
        }

        assertTrue(
            "raw backend timestamps reached the screen: $offenders",
            offenders.isEmpty(),
        )
    }
}

/** The clock parts of the timestamps this test feeds in; no formatted date contains them. */
private val ISO_CLOCK_MARKERS = listOf("T13:22", "T14:33")
