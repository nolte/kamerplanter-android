package io.github.nolte.kamerplanter.feature.plants

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.FakeConnectionStore
import io.github.nolte.kamerplanter.core.connection.InMemoryCredentialStore
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient
import io.github.nolte.kamerplanter.core.network.DiaryEntry
import io.github.nolte.kamerplanter.core.network.PlantDataChanges
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Long enough for the page's sections to load off fakes, short enough to fail fast. */
private const val WAIT_FOR_PAGE_MILLIS = 5_000L

/**
 * The per-entry rule issue #12 calls out by name, checked where it can actually go wrong.
 *
 * > `can_request_analysis` is evaluated per entry, because it depends on authorship — in a
 * > shared garden one list legitimately mixes `true` and `false` rows. Never cache one verdict
 * > for the whole page.
 *
 * A screen that read the first entry's verdict and applied it to the page would look correct
 * in every single-entry test and in every view-model test, because the view model does hold
 * the right value per entry. It only shows up in a list that mixes the two — which is what
 * this renders.
 *
 * The second test covers the other half of the issue's delete criterion: the confirmation has
 * to say that the analysis goes with the entry, because the instance is the only place either
 * of them exists.
 */
@RunWith(JUnit4::class)
class DiaryEntryActionsTest {

    /** The page offers a diary photo, so it asks for CAMERA. Outermost, wrapping composition. */
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun showPage(entries: List<DiaryEntry>, actions: FakeActionsClient) {
        val store = FakeConnectionStore(
            Connection.ApiKey(baseUrl = "https://x", tenantSlug = "demo", keyHint = "…x"),
        )
        val model = PlantDetailViewModel(
            imageClient = AuthenticatedImageClient(
                OkHttpClient(),
                InMemoryCredentialStore(),
                store,
            ),
            sources = PlantPageSources(
                page = FakePageClient(),
                actions = actions,
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
            composeRule.onAllNodesWithText(entries.first().text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The same mixed list for both halves of the claim, one menu opened per test.
     *
     * Split rather than sequenced: closing a dropdown mid-test means either clicking one of
     * its own items — which acts — or reaching for back-press plumbing, and neither belongs in
     * an assertion about what the menu offered. The menus are addressed by position, because
     * at the moment of the click they look identical, which is the confusion under test.
     */
    private fun mixedAuthorship() = listOf(
        diaryEntry("d1", "I repotted it.", canRequestAnalysis = true),
        diaryEntry("d2", "Someone else wrote this.", canRequestAnalysis = false),
    )

    @Test
    fun theEntryTheInstanceSaysMayBeAnalysedOffersIt() {
        val entries = mixedAuthorship()
        showPage(entries, FakeActionsClient(entries))

        composeRule
            .onAllNodesWithContentDescription(resources.getString(R.string.plants_entry_actions))[0]
            .performClick()

        composeRule
            .onNodeWithText(resources.getString(R.string.plants_entry_analyse))
            .assertIsDisplayed()
    }

    /**
     * The one a page-wide verdict would break.
     *
     * Same list, same screen, second row — a screen that read the first entry's `true` and
     * applied it to the page passes every other test and fails this one.
     */
    @Test
    fun theEntryTheInstanceSaysMayNotBeAnalysedDoesNotOfferIt() {
        val entries = mixedAuthorship()
        showPage(entries, FakeActionsClient(entries))

        // Scrolled to first: the second entry sits below the fold on this page, and a menu
        // opened off-screen would make the assertion below pass for the wrong reason.
        composeRule.onNodeWithText(entries[1].text).performScrollTo()
        composeRule
            .onAllNodesWithContentDescription(resources.getString(R.string.plants_entry_actions))[1]
            .performClick()

        // The menu is open — the edit item proves it — and the analysis item is not in it.
        composeRule
            .onNodeWithText(resources.getString(R.string.plants_entry_edit))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(resources.getString(R.string.plants_entry_analyse))
            .assertDoesNotExist()
    }

    /** Deleting says what else goes: the instance holds the only copy of both. */
    @Test
    fun deletingAnEntryWarnsThatItsAnalysisGoesWithIt() {
        val entry = diaryEntry("d1", "I repotted it.")
        val actions = FakeActionsClient(listOf(entry))
        showPage(listOf(entry), actions)

        composeRule
            .onAllNodesWithContentDescription(resources.getString(R.string.plants_entry_actions))[0]
            .performClick()
        composeRule.onNodeWithText(resources.getString(R.string.plants_entry_delete)).performClick()

        composeRule
            .onNodeWithText(resources.getString(R.string.plants_entry_delete_body))
            .assertIsDisplayed()
    }
}
