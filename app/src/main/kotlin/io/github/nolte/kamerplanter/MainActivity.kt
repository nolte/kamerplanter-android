package io.github.nolte.kamerplanter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import io.github.nolte.kamerplanter.core.connection.DiscoveryLinkParser
import io.github.nolte.kamerplanter.core.connection.PendingDiscovery
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeScreen
import io.github.nolte.kamerplanter.feature.pestdetection.PLANT_KEY_ARG
import io.github.nolte.kamerplanter.feature.pestdetection.PestDetectionScreen
import io.github.nolte.kamerplanter.feature.plants.PlantDetailScreen
import io.github.nolte.kamerplanter.feature.plants.PlantDetailViewModel
import io.github.nolte.kamerplanter.feature.plants.PlantsScreen
import io.github.nolte.kamerplanter.feature.settings.SettingsScreen
import io.github.nolte.kamerplanter.ui.theme.KamerplanterTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Where a `/connect` link is left for the Settings screen to pick up.
     *
     * The activity does not know what a connection flow is, and the screen may not exist yet:
     * a link scanned with the system camera starts the app cold. Handing it over through a
     * held value covers both that and the warm case below.
     */
    @Inject
    lateinit var pendingDiscovery: PendingDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh start: on a recreation — a rotation, say — the same intent is
        // delivered again, and re-offering it would restart a flow the user may have left.
        if (savedInstanceState == null) {
            offerDiscoveryLink(intent)
        }
        setContent {
            KamerplanterTheme {
                KamerplanterApp(discoveries = pendingDiscovery.arrivals)
            }
        }
    }

    /** Reached because the activity is `singleTop`: a link arriving while the app is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerDiscoveryLink(intent)
    }

    /**
     * Publishes what an incoming link turned out to be, and silently ignores anything else.
     *
     * A foreign URL that happens to carry `/connect` is dropped here rather than shown as an
     * error. The user asked to open a web address; landing in the app on a screen complaining
     * about it would be a worse answer than the app simply not claiming it.
     *
     * A link the app *recognises* and cannot act on is not that case, and used to be treated
     * as if it were: the manifest claims any host's `/connect` path whatever the query carries,
     * so tapping a link from a newer release opened the app, which then did nothing at all —
     * while scanning that same URL explained itself. Both now travel the same refusal to the
     * same screen (#40).
     */
    private fun offerDiscoveryLink(intent: Intent?) {
        val raw = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        DiscoveryLinkParser.read(raw)?.let(pendingDiscovery::offer)
    }
}

/** Pest detection, pushed onto whichever tab the user started it from. */
private const val PEST_DETECTION_ROUTE = "pest-detection"

/**
 * The same destination, entered for a named plant.
 *
 * One route with an optional argument rather than two destinations: the screen is identical
 * either way, and the only difference — whether a finding is filed against a plant — is
 * exactly what an argument is for.
 */
private const val PEST_DETECTION_FOR_PLANT_ROUTE = "pest-detection/{$PLANT_KEY_ARG}"

private const val PLANT_DETAIL_ROUTE = "plants/{${PlantDetailViewModel.PLANT_KEY_ARG}}"

/** The final top-level destinations of the app shell (requirement R1). */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    CAPTURE("capture", R.string.tab_capture, Icons.Filled.PhotoCamera),
    PLANTS("plants", R.string.tab_plants, Icons.Filled.Yard),
    SETTINGS("settings", R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun KamerplanterApp(discoveries: Flow<Unit> = emptyFlow()) {
    val navController = rememberNavController()
    // A scanned `/connect` link has to land where it can be acted on, and Settings owns the
    // connection flow. This listens to *arrivals* rather than to the link itself: the link is
    // consumed by the screen that shows the offer, and its collector outlives this one — it
    // keeps running while the app is backgrounded. Sharing the value would mean the screen took
    // it before this ever saw it, leaving the user on whichever tab they were on with the offer
    // invisible behind Settings.
    LaunchedEffect(discoveries) {
        discoveries.collect { navController.navigateToTab(TopLevelDestination.SETTINGS) }
    }
    Scaffold(
        bottomBar = { KamerplanterNavigationBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.CAPTURE.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.CAPTURE.route) {
                MicroscopeScreen(
                    onIdentifyPest = { navController.navigate(PEST_DETECTION_ROUTE) },
                )
            }
            // Not a tab: it is an action taken on a capture rather than a place, and it is
            // entered from the Capture tab today and from a plant tomorrow (#10). Reached by
            // an ordinary push, so Back returns to whichever of those it came from.
            composable(PEST_DETECTION_ROUTE) {
                PestDetectionScreen(
                    onOpenSettings = { navController.navigateToTab(TopLevelDestination.SETTINGS) },
                )
            }
            composable(
                route = PEST_DETECTION_FOR_PLANT_ROUTE,
                arguments = listOf(navArgument(PLANT_KEY_ARG) { type = NavType.StringType }),
            ) {
                PestDetectionScreen(
                    onOpenSettings = { navController.navigateToTab(TopLevelDestination.SETTINGS) },
                )
            }
            composable(TopLevelDestination.PLANTS.route) {
                PlantsScreen(
                    // The disconnected and credential-rejected states both point here: the
                    // list cannot fix either, and Settings is where a connection is made.
                    onOpenSettings = { navController.navigateToTab(TopLevelDestination.SETTINGS) },
                    onOpenPlant = { navController.navigate("plants/$it") },
                )
            }
            composable(
                route = PLANT_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument(PlantDetailViewModel.PLANT_KEY_ARG) { type = NavType.StringType },
                ),
            ) {
                PlantDetailScreen(
                    onBack = { navController.popBackStack() },
                    // Pushed, not swapped: Back from the camera returns to the plant it was
                    // opened for, which is where the finding belongs.
                    onDetectPests = { navController.navigate("pest-detection/$it") },
                )
            }
            composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
        }
    }
}

/**
 * Switches tabs the same way the bottom bar does, so a jump from the Plants tab into Settings
 * leaves the back stack in the state a tap on Settings would have.
 */
private fun NavHostController.navigateToTab(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun KamerplanterNavigationBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigate(destination.route) {
                        // Standard bottom-nav behaviour: single instance per tab, restore
                        // its state, and keep only the start destination on the back stack.
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                label = { Text(text = stringResource(destination.labelRes)) },
            )
        }
    }
}
