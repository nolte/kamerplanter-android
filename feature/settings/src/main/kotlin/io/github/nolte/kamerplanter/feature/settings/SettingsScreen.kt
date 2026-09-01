package io.github.nolte.kamerplanter.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nolte.kamerplanter.core.camera.rememberCameraPermission
import io.github.nolte.kamerplanter.core.camera.rememberLocalNetworkPermission
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionClient
import io.github.nolte.kamerplanter.core.connection.ConnectionMethod
import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.PayloadRefusal
import io.github.nolte.kamerplanter.core.connection.Tenant
import kotlinx.coroutines.delay

/**
 * Settings screen whose centrepiece is connecting to the (self-hosted) kamerplanter
 * backend. The screen owns the CAMERA runtime permission; the connection state machine
 * lives in [SettingsViewModel] and the backend is faked behind [ConnectionClient].
 *
 * The API-key form and the light-mode form are states the machine already carries but that
 * [issue #8](https://github.com/nolte/kamerplanter-android/issues/8) gives their own steps
 * (R9, R10, R26). The tenant picker (R15) is no longer among them.
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
    // Asked whenever this screen is reached without it, not only while scanning. Tying it to
    // the scanner left it unreachable for the user who needs it most: someone already
    // connected to `192.168.x.x` who updates to Android 16 has every request dropped from
    // then on, and the scanner sits behind disconnecting the connection that stopped working.
    // Settings is where connections live, so the ask has a visible reason here.
    //
    // Held back only while the camera dialogue is the one on screen.
    // `Activity.requestPermissions` refuses a second request while one is open — it logs "Can
    // request only one set of permissions at a time" and delivers an empty result, which the
    // launcher reads as a denial and the helper records as permanent, for a dialogue the user
    // never saw.
    val cameraDialogueIsOpen =
        state is ConnectionState.Collecting.ScanningQr && !permission.isGranted
    val localNetwork = rememberLocalNetworkPermission(requestOnFirstShow = !cameraDialogueIsOpen)

    SettingsContent(
        state = state,
        hasCameraPermission = permission.isGranted,
        hasLocalNetworkPermission = localNetwork.isGranted,
        actions = ConnectionActions(
            onConnect = viewModel::startConnecting,
            entry = EntryActions(
                onSubmit = viewModel::submit,
                onSelectTenant = viewModel::selectTenant,
            ),
            onCancel = viewModel::cancel,
            onDisconnect = viewModel::disconnect,
            scanner = ScannerActions(
                onQrDetected = viewModel::onQrDetected,
                onScannerError = viewModel::onScannerError,
                // Only ever the dialogue. The scanner fires this on its own when it opens
                // without the grant, and routing it to system settings after a permanent
                // denial would launch another app's screen with nobody having tapped anything.
                permission = PermissionActions(
                    onRequest = permission.request,
                    canAsk = permission.canAsk,
                    onOpenSettings = permission.openSettings,
                ),
            ),
            localNetwork = PermissionActions(
                onRequest = localNetwork.request,
                canAsk = localNetwork.canAsk,
                onOpenSettings = localNetwork.openSettings,
            ),
        ),
        modifier = modifier,
    )
}

/** The screen's callbacks, bundled so the content stays within its parameter budget. */
internal class ConnectionActions(
    val onConnect: (ConnectionMethod) -> Unit,
    /** What the user hands the machine mid-attempt: a typed request, or the tenant pick. */
    val entry: EntryActions,
    val onCancel: () -> Unit,
    val onDisconnect: () -> Unit,
    /** Everything the scanner needs, grouped: only one state uses any of it. */
    val scanner: ScannerActions,
    /** The local-network grant, for the failure screen to offer where it may be the cause. */
    val localNetwork: PermissionActions,
)

/** The mid-attempt inputs: a typed request (R13) and the tenant choice (R15). */
internal class EntryActions(
    /** Hands a typed request to verification; the machine ignores a stale one. */
    val onSubmit: (ConnectionRequest) -> Unit,
    val onSelectTenant: (Tenant) -> Unit,
)

/** The scanner's own callbacks and its camera grant. */
internal class ScannerActions(
    val onQrDetected: (String) -> QrReading,
    val onScannerError: () -> Unit,
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
            ConnectionState.Disconnected -> NotConnectedBody(onConnect = actions.onConnect)
            is ConnectionState.Collecting.ApiKeyEntry -> ApiKeyEntryBody(
                prefilledBaseUrl = state.prefilledBaseUrl,
                onSubmit = actions.entry.onSubmit,
                onCancel = actions.onCancel,
            )
            is ConnectionState.Collecting.LightModeEntry -> LightModeEntryBody(
                prefilledBaseUrl = state.prefilledBaseUrl,
                onSubmit = actions.entry.onSubmit,
                onCancel = actions.onCancel,
            )
            is ConnectionState.Discovered -> DiscoveredBody(
                state = state,
                onConnect = actions.onConnect,
                onDismiss = actions.onCancel,
            )
            is ConnectionState.LinkRefused -> LinkRefusedBody(
                reason = state.reason,
                onDismiss = actions.onCancel,
            )
            is ConnectionState.Collecting.ScanningQr -> ScanningBody(
                hasCameraPermission = hasCameraPermission,
                scanner = actions.scanner,
                onCancel = actions.onCancel,
            )
            ConnectionState.CameraUnavailable -> CameraUnavailableBody(
                onRetry = { actions.onConnect(ConnectionMethod.QR_PAIRING) },
            )
            is ConnectionState.Verifying -> CenteredProgress(
                label = stringResource(R.string.settings_verifying),
            )
            is ConnectionState.SelectingTenant -> TenantChoiceBody(
                tenants = state.tenants,
                onSelect = actions.entry.onSelectTenant,
                onCancel = actions.onCancel,
            )
            is ConnectionState.Connected -> ConnectedBody(
                connection = state.connection,
                onConnect = actions.onConnect,
                onDisconnect = actions.onDisconnect,
            )
            is ConnectionState.Failed -> FailedBody(
                reason = state.reason,
                // Only where the grant could have been the cause: the instance did not answer.
                // A refusal is never this, and shown after every failure the advice is about
                // the wrong thing most of the time — advice a user skips no longer works when
                // it is right.
                //
                // Judged on the outcome, not on how the address is spelled. Split-horizon DNS
                // is the ordinary way to self-host with TLS, so the address that needs this
                // grant most often looks entirely public.
                localNetwork = actions.localNetwork.takeIf {
                    !hasLocalNetworkPermission && state.unreachable
                },
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
 * All three methods are offered, the same set the disconnected screen has (R28) — the link
 * names an instance, not a way in, and its address travels into whichever collection step the
 * user picks.
 */
@Composable
private fun DiscoveredBody(
    state: ConnectionState.Discovered,
    onConnect: (ConnectionMethod) -> Unit,
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
            MethodButtons(onConnect = onConnect)
        }
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.settings_discovered_dismiss))
        }
    }
}

/**
 * The instance offered several tenants and the user picks one (R15).
 *
 * This screen used to say the picker did not exist yet, and the state machine kept light mode
 * away from it on the grounds that light mode had no tenants. Both halves have given way:
 * light mode does have them, so it reaches this state, and a state that only apologises is a
 * dead end — an instance with two gardens could not be connected to at all, by any method.
 * `selectTenant` was fully implemented in the ViewModel the whole time; only this was missing.
 *
 * Cancelling returns to the previous connection, or to disconnected, without storing anything.
 */
@Composable
private fun TenantChoiceBody(
    tenants: List<Tenant>,
    onSelect: (Tenant) -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = stringResource(R.string.settings_tenant_choice_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_tenant_choice_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        tenants.forEach { tenant ->
            OutlinedButton(
                onClick = { onSelect(tenant) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                // The display name, with the slug beneath it: two gardens can share a name,
                // and the slug is what the connection actually stores and addresses.
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = tenant.displayName)
                    if (tenant.displayName != tenant.slug) {
                        Text(
                            text = tenant.slug,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
            Text(text = stringResource(R.string.settings_cancel))
        }
    }
}

/**
 * Nothing is connected, and the user picks one of the three ways in (R6, R28). The QR path
 * leads with a filled button because pairing is the instance's own hand-off and the path the
 * web UI points people to; the other two are real alternatives, not fallbacks, and sit right
 * below it.
 */
@Composable
private fun NotConnectedBody(onConnect: (ConnectionMethod) -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_pairing_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.settings_not_paired),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        MethodButtons(onConnect = onConnect)
    }
}

/** The three ways to connect, in one place for the chooser and the discovered offer alike. */
@Composable
private fun MethodButtons(onConnect: (ConnectionMethod) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { onConnect(ConnectionMethod.QR_PAIRING) }) {
            Text(text = stringResource(R.string.settings_pair_button))
        }
        OutlinedButton(onClick = { onConnect(ConnectionMethod.API_KEY) }) {
            Text(text = stringResource(R.string.settings_method_api_key))
        }
        OutlinedButton(onClick = { onConnect(ConnectionMethod.LIGHT_MODE) }) {
            Text(text = stringResource(R.string.settings_method_light_mode))
        }
    }
}

/**
 * Collects a base URL and a `kp_sk_…` key (F-7). The key field masks its input: the rule
 * that no stored secret is rendered in the clear (R19) starts at the moment of typing, not
 * at the moment of storing.
 *
 * The address is judged by [InstanceAddressInput] on submit, not per keystroke — an address
 * is wrong the whole time someone is typing it, and a form that says so from the first
 * character is a form shouting at its user. A refusal shows the same sentence the scanner
 * would, and stays until the next submit.
 */
@Composable
private fun ApiKeyEntryBody(
    prefilledBaseUrl: String?,
    onSubmit: (ConnectionRequest) -> Unit,
    onCancel: () -> Unit,
) {
    val form = rememberEntryFormState(prefilledBaseUrl)
    // Plain remember on purpose: rememberSaveable writes to the Bundle, and a pasted key on
    // its way to the Keystore has no business surviving there in the clear (R19).
    var key by remember { mutableStateOf("") }
    EntryForm(
        form = form,
        title = R.string.settings_method_api_key,
        explanation = R.string.settings_api_key_explanation,
        buttons = EntryFormButtons(
            submitEnabled = form.address.isNotBlank() && key.isNotBlank(),
            onSubmit = {
                form.submit { address ->
                    onSubmit(ConnectionRequest.ApiKey(address, key.trim()))
                }
            },
            onCancel = onCancel,
        ),
    ) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.settings_api_key_label)) },
            singleLine = true,
            // Masked like a password: a pasted key on screen is a secret on screen (R19).
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Collects the base URL of a light-mode instance — the address is all there is (F-11). */
@Composable
private fun LightModeEntryBody(
    prefilledBaseUrl: String?,
    onSubmit: (ConnectionRequest) -> Unit,
    onCancel: () -> Unit,
) {
    val form = rememberEntryFormState(prefilledBaseUrl)
    EntryForm(
        form = form,
        title = R.string.settings_method_light_mode,
        explanation = R.string.settings_light_mode_explanation,
        buttons = EntryFormButtons(
            submitEnabled = form.address.isNotBlank(),
            onSubmit = {
                form.submit { address -> onSubmit(ConnectionRequest.LightMode(address)) }
            },
            onCancel = onCancel,
        ),
    )
}

/**
 * The live values of one typed entry form. A plain state holder rather than parameters,
 * for the same reason [ConnectionActions] bundles callbacks: the form's shape stays within
 * a readable parameter budget, and the address/refusal pair never travels separately.
 */
private class EntryFormState(addressState: MutableState<String>) {

    var address by addressState

    /** The last submit's verdict on [address]; `null` before the first and after a pass. */
    var refusal by mutableStateOf<PayloadRefusal?>(null)
        private set

    /** Judges the address and hands the normalized form on only when it may be used. */
    fun submit(onAccepted: (String) -> Unit) {
        refusal = InstanceAddressInput.refusalFor(address)
        if (refusal == null) onAccepted(InstanceAddressInput.normalize(address))
    }
}

/**
 * Both remembers are keyed on the prefill, so a transition straight from one entry state to
 * another with a different link-supplied address cannot keep a stale form. The address also
 * survives process recreation — it is not a secret, and retyping it after a rotation would
 * be the screen forgetting on the user's behalf. The refusal deliberately does not: it is a
 * verdict about a submit that has not happened in the new process.
 */
@Composable
private fun rememberEntryFormState(prefilledBaseUrl: String?): EntryFormState {
    val address = rememberSaveable(prefilledBaseUrl, stateSaver = autoSaver()) {
        mutableStateOf(prefilledBaseUrl.orEmpty())
    }
    return remember(prefilledBaseUrl) { EntryFormState(address) }
}

/** The connect/cancel pair of an entry form, and whether connect may be pressed yet. */
private class EntryFormButtons(
    val submitEnabled: Boolean,
    val onSubmit: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * The shape the two typed methods share: an explanation, the address, whatever else the
 * method needs, the refusal where there is one, and the connect/cancel pair. Scrollable
 * because the keyboard takes half the screen exactly while this form is in use.
 */
@Composable
private fun EntryForm(
    form: EntryFormState,
    @StringRes title: Int,
    @StringRes explanation: Int,
    buttons: EntryFormButtons,
    extraField: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = form.address,
            onValueChange = { form.address = it },
            label = { Text(stringResource(R.string.settings_address_label)) },
            placeholder = { Text(stringResource(R.string.settings_address_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        extraField?.invoke()
        form.refusal?.let {
            Text(
                text = stringResource(it.explanationRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = buttons.onSubmit,
            enabled = buttons.submitEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.settings_connect_button))
        }
        TextButton(onClick = buttons.onCancel) {
            Text(text = stringResource(R.string.settings_cancel))
        }
    }
}

@Composable
private fun ScanningBody(
    hasCameraPermission: Boolean,
    scanner: ScannerActions,
    onCancel: () -> Unit,
) {
    val permission = scanner.permission
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
                val reading = scanner.onQrDetected(raw)
                // A new object every time, so holding a foreign code in frame keeps the badge
                // alive instead of letting the first frame's timeout retire it.
                lastReading = ScanFeedback(reading, (lastReading?.seq ?: 0) + 1)
            },
            onError = scanner.onScannerError,
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
        QrReading.Accepted -> R.string.settings_scan_recognised
        QrReading.Foreign -> R.string.settings_scan_foreign
        // The user's own code, held correctly. Saying "not a kamerplanter code" would send
        // them looking for another one; the fault is elsewhere, and the sentence points at it
        // — the same sentence a tapped link gets, from the same place.
        is QrReading.Refused -> reading.reason.explanationRes()
        QrReading.Stale -> return
    }
    val colors = when (reading) {
        QrReading.Accepted -> CardDefaults.cardColors(
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

/**
 * A `/connect` link the app recognised and will not act on.
 *
 * Reached only from the deep-link channel, and deliberately plain: there is nothing to
 * continue to, so the screen says what happened and gets out of the way. The sentence itself
 * comes from [explanationRes], which is also what the scanner shows — the whole point of #40
 * is that the same URL cannot explain itself through one entry point and stay silent in the
 * other.
 */
@Composable
private fun LinkRefusedBody(reason: PayloadRefusal, onDismiss: () -> Unit) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_link_refused_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(reason.explanationRes()),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.settings_link_refused_dismiss))
        }
    }
}

/**
 * What a refusal says, wherever it is shown.
 *
 * One `when` over the payload contract's reasons, so a reason added there has to be worded
 * once and reaches both the scanner and the deep-link screen — the drift #40 named: an
 * explanation on one entry point, silence on the other.
 *
 * The strings name neither "code" nor "link", because the same sentence has to fit whichever
 * of the two the user actually used.
 */
@StringRes
internal fun PayloadRefusal.explanationRes(): Int = when (this) {
    PayloadRefusal.PAYLOAD_TOO_NEW -> R.string.settings_refused_too_new
    PayloadRefusal.PAYLOAD_TOO_OLD -> R.string.settings_refused_too_old
    PayloadRefusal.ADDRESS_NOT_ENCRYPTED -> R.string.settings_refused_address_not_encrypted
    PayloadRefusal.ADDRESS_UNUSABLE -> R.string.settings_refused_address_unusable
}

/**
 * The established connection, described in full (R26): address, method, tenant, and the
 * signed-in identity where the instance reported one. The pairing code the dummy used to
 * print here stays gone — no stored secret is ever rendered in clear text (R19); the most an
 * API-key connection shows is the masked hint composed at connect time.
 *
 * Below the description, the same three method buttons the disconnected screen offers: the
 * connection can be changed from any method to any other at any time (R27), the stored one
 * stays in place until the new attempt verifies, and cancelling returns here untouched — the
 * state machine has guaranteed all of that for a while; this is merely the way in.
 */
@Composable
private fun ConnectedBody(
    connection: Connection,
    onConnect: (ConnectionMethod) -> Unit,
    onDisconnect: () -> Unit,
) {
    CenteredColumn {
        Text(
            text = stringResource(R.string.settings_paired_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(text = stringResource(R.string.settings_paired_base_url, connection.baseUrl))
            Text(text = stringResource(R.string.settings_connected_method, connection.method.label()))
            Text(text = stringResource(R.string.settings_connected_tenant, connection.tenantSlug))
            connection.identityOrNull()?.let {
                Text(text = stringResource(R.string.settings_connected_identity, it))
            }
            if (connection is Connection.ApiKey) {
                Text(text = stringResource(R.string.settings_connected_key_hint, connection.keyHint))
            }
        }
        if (connection.belowVersionFloor) {
            // F-10's reduced mode, said where the connection lives: the instance works, but
            // it is older than this app was built against, and features may fall back.
            Text(
                text = stringResource(R.string.settings_below_version_floor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = stringResource(R.string.settings_change_connection),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        MethodButtons(onConnect = onConnect)
        OutlinedButton(onClick = onDisconnect, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.settings_unpair))
        }
    }
}

/** What the connected screen calls each method — the user-facing name, not the enum's. */
@Composable
private fun ConnectionMethod.label(): String = stringResource(
    when (this) {
        ConnectionMethod.QR_PAIRING -> R.string.settings_method_label_qr
        ConnectionMethod.API_KEY -> R.string.settings_method_label_api_key
        ConnectionMethod.LIGHT_MODE -> R.string.settings_method_label_light_mode
    },
)

/** The identity a connection can show, for the two kinds that can carry one. */
private fun Connection.identityOrNull(): String? = when (this) {
    is Connection.QrPairing -> identity
    is Connection.ApiKey -> identity
    is Connection.LightMode -> null
}

@Composable
private fun FailedBody(
    reason: String,
    /** Non-null when a missing local-network grant is a plausible cause and can be asked for. */
    localNetwork: PermissionActions?,
    onRetry: () -> Unit,
) {
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
        if (localNetwork != null) {
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
            // With a way to act on it. Naming a missing grant and offering nothing is the dead
            // end this file criticises on the camera path — and once the system stops
            // prompting, app settings is the only route left.
            TextButton(
                onClick = if (localNetwork.canAsk) {
                    localNetwork.onRequest
                } else {
                    localNetwork.onOpenSettings
                },
            ) {
                Text(
                    text = stringResource(
                        if (localNetwork.canAsk) {
                            R.string.settings_grant_local_network
                        } else {
                            R.string.settings_open_app_settings
                        },
                    ),
                )
            }
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
