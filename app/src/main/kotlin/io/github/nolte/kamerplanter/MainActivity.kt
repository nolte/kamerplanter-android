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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.nolte.kamerplanter.core.connection.DiscoveryLink
import io.github.nolte.kamerplanter.core.connection.DiscoveryLinkParser
import io.github.nolte.kamerplanter.core.connection.PendingDiscovery
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeScreen
import io.github.nolte.kamerplanter.feature.pestdetection.PestDetectionScreen
import io.github.nolte.kamerplanter.feature.plants.PlantsScreen
import io.github.nolte.kamerplanter.feature.settings.SettingsScreen
import io.github.nolte.kamerplanter.ui.theme.KamerplanterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                KamerplanterApp(discoveries = pendingDiscovery.link)
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
     * Publishes an incoming link, and silently ignores anything else.
     *
     * A link this app cannot read — an unknown payload version, a foreign URL that happens to
     * carry `/connect` — is dropped here rather than shown as an error. The user asked to open
     * a web address; landing in the app on a screen complaining about it would be a worse
     * answer than the app simply not claiming it.
     */
    private fun offerDiscoveryLink(intent: Intent?) {
        val raw = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        DiscoveryLinkParser.parse(raw)?.let(pendingDiscovery::offer)
    }
}

/** Pest detection, pushed onto whichever tab the user started it from. */
private const val PEST_DETECTION_ROUTE = "pest-detection"

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
fun KamerplanterApp(discoveries: StateFlow<DiscoveryLink?> = MutableStateFlow(null)) {
    val navController = rememberNavController()
    // A scanned `/connect` link has to land where it can be acted on. Settings owns the
    // connection flow, and the link is only *read* there — this navigates, it does not consume,
    // so whichever of the two gets there first cannot leave the other with nothing.
    val waiting by discoveries.collectAsStateWithLifecycle()
    LaunchedEffect(waiting) {
        if (waiting != null) navController.navigateToTab(TopLevelDestination.SETTINGS)
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
            composable(TopLevelDestination.PLANTS.route) {
                PlantsScreen(
                    // The disconnected and credential-rejected states both point here: the
                    // list cannot fix either, and Settings is where a connection is made.
                    onOpenSettings = { navController.navigateToTab(TopLevelDestination.SETTINGS) },
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
