package io.github.nolte.kamerplanter

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The harness itself, under test.
 *
 * This asserts nothing about a feature. It answers the one question every later instrumented
 * test rests on: does this app assemble its Hilt graph, reach its first frame and render its
 * navigation on a managed device at all. When this goes red, no failure below it means
 * anything.
 *
 * Labels are read from resources rather than written as literals — a test that hardcodes
 * "Plants" passes only in English and turns a locale change into a mystery failure.
 */
@RunWith(JUnit4::class)
@HiltAndroidTest
class AppLaunchTest {

    /**
     * Granted before the activity launches, and the reason this test failed on its first run.
     *
     * The start destination is the Capture tab, which asks for CAMERA on a cold start — the
     * microscope needs that grant because AOSP refuses to show the USB dialogue for a
     * video-class device without it. The system dialogue then covers the activity, and since
     * Compose only searches *resumed* activities for semantics, the app's own hierarchy became
     * invisible: the failure reads "No compose hierarchies found in the app", which sounds like
     * the app never drew anything.
     *
     * Order matters. This rule has to wrap the activity launch, so it sits outermost.
     */
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAppLaunchesAndShowsItsThreeTabs() {
        hiltRule.inject()

        val context = composeRule.activity
        composeRule.onNodeWithText(context.getString(R.string.tab_capture)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.tab_plants)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.tab_settings)).assertIsDisplayed()
    }
}
