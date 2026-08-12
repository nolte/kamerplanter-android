package io.github.nolte.kamerplanter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nolte.kamerplanter.R

/** Placeholder for the future Plants tab — no real functionality yet (requirement R3). */
@Composable
fun PlantsScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.plants_placeholder),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}
