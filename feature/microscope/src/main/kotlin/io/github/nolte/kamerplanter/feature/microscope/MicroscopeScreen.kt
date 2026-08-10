package io.github.nolte.kamerplanter.feature.microscope

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Placeholder entry point for the USB-microscope capture flow (issue #1).
 * Replaced by the real preview/capture UI once [MicroscopeCamera] has an
 * AUSBC-backed implementation.
 */
@Composable
fun MicroscopeScreen(modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = stringResource(R.string.microscope_placeholder))
        }
    }
}
