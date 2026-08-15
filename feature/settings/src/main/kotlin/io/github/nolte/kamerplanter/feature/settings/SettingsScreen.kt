package io.github.nolte.kamerplanter.feature.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nolte.kamerplanter.core.camera.rememberCameraPermission
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
    // Asked only once the scanner is what is on screen — a method chooser comes first here,
    // unlike a viewfinder that is useless without the camera. Left to the shared helper rather
    // than to an effect of this screen's own, which would ask again on every rotation because
    // it keeps no memory of having asked.
    val permission = rememberCameraPermission(
        requestOnFirstShow = state is ConnectionState.Collecting.ScanningQr,
    )

    SettingsContent(
        state = state,
        hasCameraPermission = permission.isGranted,
        actions = ConnectionActions(
            onConnect = viewModel::startConnecting,
            onQrDetected = viewModel::onQrDetected,
            onScannerError = viewModel::onScannerError,
            onCancel = viewModel::cancel,
            onDisconnect = viewModel::disconnect,
            permission = PermissionActions(
                // Only ever the dialogue. The scanner fires this on its own when it opens
                // without the grant, and routing it to system settings after a permanent
                // denial would launch another app's screen with nobody having tapped anything.
                onRequest = permission.request,
                canAsk = permission.canAsk,
                onOpenSettings = permission.openSettings,
            ),
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
    val permission: PermissionActions,
)

/**
 * What the scanner can do about a missing camera grant.
 *
 * Two callbacks rather than one, because after "Don't ask again" the system stops prompting:
 * a request button there is a control that visibly does nothing, and app settings is the only
 * route back.
 */
internal class PermissionActions(
    val onRequest: () -> Unit,
    /** `false` after "Don't ask again": asking again shows nothing, so the button must not. */
    val canAsk: Boolean,
    val onOpenSettings: () -> Unit,
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
            is ConnectionState.Collecting.ApiKeyEntry,
            is ConnectionState.Collecting.LightModeEntry,
            -> NotConnectedBody(onConnect = { actions.onConnect(ConnectionMethod.QR_PAIRING) })
            is ConnectionState.Discovered -> DiscoveredBody(
                state = state,
                onContinue = { actions.onConnect(ConnectionMethod.QR_PAIRING) },
                onDismiss = actions.onCancel,
            )
            is ConnectionState.Collecting.ScanningQr -> ScanningBody(
                hasCameraPermission = hasCameraPermission,
                onQrDetected = actions.onQrDetected,
                onScannerError = actions.onScannerError,
                onCancel = actions.onCancel,
                permission = actions.permission,
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
 * An instance arrived from a `/connect` link, and the user decides what to do about it.
 *
 * The link is credential-free — it names an instance and calls nothing — so this is an offer,
 * not a step in an attempt. What it must say before anything else is how the instance stands to
 * the one already connected: continuing from here replaces a working connection, and finding
 * that out afterwards is the wrong order.
 *
 * Only the pairing method is offered because only it has a surface today; the address the link
 * carried travels with the choice regardless, ready for the other two.
 */
@Composable
private fun DiscoveredBody(
    state: ConnectionState.Discovered,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.settings_discovered_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        // The address verbatim: it is the one thing the user can check against the poster or
        // screen they scanned, and paraphrasing it would remove the only means of noticing that
        // a link pointed somewhere unexpected.
        Text(text = state.baseUrl, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(
            text = stringResource(
                when (state.relation) {
                    DiscoveredInstance.NEW -> R.string.settings_discovered_new
                    DiscoveredInstance.ALREADY_CONNECTED -> R.string.settings_discovered_same
                    DiscoveredInstance.REPLACES_ANOTHER -> R.string.settings_discovered_replaces
                },
            ),
            textAlign = TextAlign.Center,
        )
        // Nothing to do on an instance already connected — the link asked for something that is
        // already true, so the only sensible button is the one that dismisses it.
        if (state.relation != DiscoveredInstance.ALREADY_CONNECTED) {
            Button(onClick = onContinue) {
                Text(stringResource(R.string.settings_discovered_continue))
            }
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.settings_discovered_dismiss))
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
    permission: PermissionActions,
) {
    if (!hasCameraPermission) {
        CameraPermissionBody(
            canAsk = permission.canAsk,
            onRequest = permission.onRequest,
            onOpenSettings = permission.onOpenSettings,
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
private fun CameraPermissionBody(
    canAsk: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_camera_permission),
            textAlign = TextAlign.Center,
        )
        // Once the request shows no dialogue there is nothing for a "grant permission" button
        // to do, and offering one under that label leaves two controls doing the same thing
        // with only one of them saying so.
        if (canAsk) {
            Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = stringResource(R.string.settings_grant_permission))
            }
        }
        // The only route back from a permanent denial.
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
