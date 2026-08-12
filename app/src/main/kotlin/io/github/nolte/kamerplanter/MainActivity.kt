package io.github.nolte.kamerplanter

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeScreen
import io.github.nolte.kamerplanter.feature.settings.SettingsScreen
import io.github.nolte.kamerplanter.ui.PlantsScreen
import io.github.nolte.kamerplanter.ui.theme.KamerplanterTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KamerplanterTheme {
                KamerplanterApp()
            }
        }
    }
}

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
fun KamerplanterApp() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = { KamerplanterNavigationBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.CAPTURE.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.CAPTURE.route) { MicroscopeScreen() }
            composable(TopLevelDestination.PLANTS.route) { PlantsScreen() }
            composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
        }
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
