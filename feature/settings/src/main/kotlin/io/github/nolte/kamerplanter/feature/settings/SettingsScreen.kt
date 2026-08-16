package io.github.nolte.kamerplanter.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nolte.kamerplanter.core.camera.rememberCameraPermission
import io.github.nolte.kamerplanter.core.camera.rememberLocalNetworkPermission
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionClient
import io.github.nolte.kamerplanter.core.connection.ConnectionMethod
import io.github.nolte.kamerplanter.core.connection.InstanceAddressPolicy
import kotlinx.coroutines.delay

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
    // Asked in this flow rather than after the scan: since Android 16 an instance on the
    // user's own network is unreachable without this grant — and unreachable in the worst way,
    // with the connection dropped rather than refused, so the app sees only its own timeout
    // expire and reports a healthy server as down. Asking once the scan has produced an
    // address would mean interrupting an attempt already under way.
    //
    // Waits for the camera grant instead of asking beside it. `Activity.requestPermissions`
    // refuses a second request while one is open — it logs "Can request only one set of
    // permissions at a time" and immediately delivers an empty result, which the launcher
    // reads as a denial. The dialogue would never appear, and the helper would record a
    // permanent refusal for a permission the user was never asked about: precisely the
    // ungranted state this whole change exists to avoid.
    val localNetwork = rememberLocalNetworkPermission(
        requestOnFirstShow = state is ConnectionState.Collecting.ScanningQr && permission.isGranted,
    )

    SettingsContent(
        state = state,
        hasCameraPermission = permission.isGranted,
        hasLocalNetworkPermission = localNetwork.isGranted,
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
    val onQrDetected: (String) -> QrReading,
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
    hasLocalNetworkPermission: Boolean,
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
            is ConnectionState.Failed -> FailedBody(
                reason = state.reason,
                // Only where the grant could have been the cause. Shown after every failure
                // it is advice about the wrong thing most of the time, and advice a user
                // skips is advice that no longer works when it is right.
                needsLocalNetwork = !hasLocalNetworkPermission &&
                    InstanceAddressPolicy.isPrivate(state.baseUrl),
                onRetry = { actions.onConnect(state.method) },
            )
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
    onQrDetected: (String) -> QrReading,
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
    // The scanner's own verdict on the last code it saw, so that pointing the camera at a QR
    // code always produces a visible reaction. Owned here rather than by the scanner because
    // the cancel button below decides where the badge fits.
    var lastReading by remember { mutableStateOf<ScanFeedback?>(null) }
    ScanFeedbackTimeout(feedback = lastReading, onExpired = { lastReading = null })

    Box(modifier = Modifier.fillMaxSize()) {
        QrScannerView(
            onQrDetected = { raw ->
                val reading = onQrDetected(raw)
                // A new object every time, so holding a foreign code in frame keeps the badge
                // alive instead of letting the first frame's timeout retire it.
                lastReading = ScanFeedback(reading, (lastReading?.seq ?: 0) + 1)
            },
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        ) {
            lastReading?.reading?.let { ScanFeedbackBadge(reading = it) }
            OutlinedButton(onClick = onCancel) {
                Text(text = stringResource(R.string.settings_cancel))
            }
        }
    }
}

/** The last decode plus a sequence number, so an unchanged verdict still counts as new. */
private data class ScanFeedback(val reading: QrReading, val seq: Int)

private const val FEEDBACK_VISIBLE_MILLIS = 2_000L

/** Retires a badge that has stopped being refreshed — the code left the frame. */
@Composable
private fun ScanFeedbackTimeout(feedback: ScanFeedback?, onExpired: () -> Unit) {
    val currentOnExpired by rememberUpdatedState(onExpired)
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(FEEDBACK_VISIBLE_MILLIS)
            currentOnExpired()
        }
    }
}

/**
 * Says that a QR code was seen, and whether it meant anything here.
 *
 * [QrReading.STALE] shows nothing: it is the tail of frames still carrying the code that was
 * just accepted, and reporting it would contradict the pairing already under way.
 */
@Composable
private fun ScanFeedbackBadge(reading: QrReading, modifier: Modifier = Modifier) {
    val label = when (reading) {
        QrReading.ACCEPTED -> R.string.settings_scan_recognised
        QrReading.FOREIGN -> R.string.settings_scan_foreign
        QrReading.STALE -> return
    }
    val colors = when (reading) {
        QrReading.ACCEPTED -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        else -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Card(colors = colors, modifier = modifier.padding(bottom = 16.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
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
private fun FailedBody(reason: String, needsLocalNetwork: Boolean, onRetry: () -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_failed),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        // Unlocalised on purpose: it is the client's own words about what it found, and
        // translating it would mean inventing a fixed vocabulary of failures the client does
        // not have. Better a technical sentence the instance's administrator can act on than a
        // smooth one that says nothing.
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (needsLocalNetwork) {
            // The likeliest explanation for a timeout against a self-hosted instance, and one
            // the reason above cannot give: a connection refused for want of this grant is
            // dropped, not rejected, so what reaches the client is silence.
            Text(
                text = stringResource(R.string.settings_failed_local_network),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
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
