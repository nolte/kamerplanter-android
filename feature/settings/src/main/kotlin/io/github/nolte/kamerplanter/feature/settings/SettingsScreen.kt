package io.github.nolte.kamerplanter.feature.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionClient
import io.github.nolte.kamerplanter.core.connection.ConnectionMethod

/**
 * Settings screen whose centrepiece is connecting to the (self-hosted) kamerplanter
 * backend. The screen owns the CAMERA runtime permission; the connection state machine
 * lives in [SettingsViewModel] and the backend is faked behind [ConnectionClient].
 *
 * The surface is still the dummy's QR-only one: the API-key form, the light-mode form and
 * the tenant picker are states the machine already carries but that
 * [issue #8](https://github.com/nolte/kamerplanter-android/issues/8) gives their own
 * steps (R9, R10, R15, R26).
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    // Re-read on resume: granting from system Settings does not restart the process.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCameraPermission = context.hasCameraPermission()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    SettingsContent(
        state = state,
        hasCameraPermission = hasCameraPermission,
        actions = ConnectionActions(
            onConnect = viewModel::startConnecting,
            onQrDetected = viewModel::onQrDetected,
            onScannerError = viewModel::onScannerError,
            onCancel = viewModel::cancel,
            onDisconnect = viewModel::disconnect,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        ),
        modifier = modifier,
    )
}

/** The screen's callbacks, bundled so the content stays within its parameter budget. */
internal class ConnectionActions(
    val onConnect: (ConnectionMethod) -> Unit,
    val onQrDetected: (String) -> Unit,
    val onScannerError: () -> Unit,
    val onCancel: () -> Unit,
    val onDisconnect: () -> Unit,
    val onRequestPermission: () -> Unit,
)

@Composable
private fun SettingsContent(
    state: ConnectionState,
    hasCameraPermission: Boolean,
    actions: ConnectionActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            ConnectionState.Loading -> CenteredProgress()
            // Only the QR method has a surface today, so the disconnected body offers it
            // directly instead of a method chooser.
            ConnectionState.Disconnected,
            ConnectionState.Collecting.ApiKeyEntry,
            ConnectionState.Collecting.LightModeEntry,
            -> NotConnectedBody(onConnect = { actions.onConnect(ConnectionMethod.QR_PAIRING) })
            ConnectionState.Collecting.ScanningQr -> ScanningBody(
                hasCameraPermission = hasCameraPermission,
                onQrDetected = actions.onQrDetected,
                onScannerError = actions.onScannerError,
                onCancel = actions.onCancel,
                onRequestPermission = actions.onRequestPermission,
            )
            ConnectionState.CameraUnavailable -> CameraUnavailableBody(
                onRetry = { actions.onConnect(ConnectionMethod.QR_PAIRING) },
            )
            is ConnectionState.Verifying -> CenteredProgress(
                label = stringResource(R.string.settings_verifying),
            )
            // The picker itself is still missing (R15), but this is a *resting* state: the
            // machine waits here until selectTenant() is called, and nothing calls it yet.
            // Without an escape the user would be stuck on a spinner for good the first time
            // an instance offers more than one tenant, so it says so and offers a way back.
            is ConnectionState.SelectingTenant -> PendingTenantChoiceBody(
                onCancel = actions.onCancel,
            )
            is ConnectionState.Connected -> ConnectedBody(
                connection = state.connection,
                onDisconnect = actions.onDisconnect,
            )
            is ConnectionState.Failed -> FailedBody(onRetry = { actions.onConnect(state.method) })
        }
    }
}

/**
 * Shown while the machine rests in [ConnectionState.SelectingTenant] — the instance offered
 * several tenants and the picker that would resolve it does not exist yet (R15).
 *
 * It states that plainly rather than spinning: a progress indicator would promise work that
 * is not happening, and the state does not resolve on its own. Cancelling returns to the
 * previous connection, or to disconnected, without storing anything.
 */
@Composable
private fun PendingTenantChoiceBody(onCancel: () -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_tenant_choice_pending),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.settings_cancel))
        }
    }
}

@Composable
private fun NotConnectedBody(onConnect: () -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_pairing_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.settings_not_paired),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onConnect, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.settings_pair_button))
        }
    }
}

@Composable
private fun ScanningBody(
    hasCameraPermission: Boolean,
    onQrDetected: (String) -> Unit,
    onScannerError: () -> Unit,
    onCancel: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val context = LocalContext.current
    if (!hasCameraPermission) {
        LaunchedEffect(Unit) { onRequestPermission() }
        CameraPermissionBody(
            onRequest = onRequestPermission,
            onOpenSettings = { context.openAppSettings() },
        )
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        QrScannerView(
            onQrDetected = onQrDetected,
            onError = onScannerError,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = stringResource(R.string.settings_scanning_hint),
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(24.dp),
        )
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        ) {
            Text(text = stringResource(R.string.settings_cancel))
        }
    }
}

// The pairing code the dummy used to print here is gone on purpose: no stored secret is
// ever rendered in clear text (R19). Method, tenant and identity join the base URL when
// the connection surface gets its own step of issue #8 (R26).
@Composable
private fun ConnectedBody(connection: Connection, onDisconnect: () -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_paired_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.settings_paired_base_url, connection.baseUrl),
            modifier = Modifier.padding(top = 12.dp),
        )
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.settings_unpair))
        }
    }
}

@Composable
private fun FailedBody(onRetry: () -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_failed),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.settings_retry))
        }
    }
}

@Composable
private fun CameraUnavailableBody(onRetry: () -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_camera_unavailable),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.settings_retry))
        }
    }
}

@Composable
private fun CameraPermissionBody(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_camera_permission),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.settings_grant_permission))
        }
        // Fallback route for a permanent denial, where re-requesting shows no dialog.
        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
            Text(text = stringResource(R.string.settings_open_app_settings))
        }
    }
}

@Composable
private fun CenteredProgress(label: String? = null) {
    CenteredColumn {
        CircularProgressIndicator()
        if (label != null) {
            Text(text = label, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

/** A vertically- and horizontally-centred column with the screen's standard padding. */
@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
