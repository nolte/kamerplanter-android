package io.github.nolte.kamerplanter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeScreen
import io.github.nolte.kamerplanter.ui.theme.KamerplanterTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KamerplanterTheme {
                KamerplanterNavHost()
            }
        }
    }
}

object Routes {
    const val MICROSCOPE = "microscope"
}

@Composable
fun KamerplanterNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.MICROSCOPE) {
        composable(Routes.MICROSCOPE) {
            MicroscopeScreen()
        }
    }
}
