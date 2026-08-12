package io.github.nolte.kamerplanter.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The connection flow's state machine (requirement R29); see [ConnectionState] for the
 * diagram. It owns three policies the UI must not re-invent:
 *
 * - **Verify before persist (R13).** Nothing reaches [ConnectionStore] until
 *   [ConnectionClient] reports [ConnectionResult.Verified].
 * - **Tenant adoption (R15).** Exactly one tenant is adopted silently, several make the
 *   user pick, none is a failure — on the light-mode path there are no tenants at all.
 * - **A failed change is a no-op (R14, R27).** Re-connecting from [ConnectionState.Connected]
 *   leaves the stored connection in place until the new one verifies; cancelling or failing
 *   returns to it.
 *
 * A connection has two halves in two stores — the displayable [Connection] and the encrypted
 * [Credential] — and this is the only place that writes both. They are established together
 * and cleared together, because either half alone is a broken state: a connection whose
 * secret is missing cannot call anything, and a secret without its connection is an
 * unreachable secret sitting on the device.
 *
 * The CAMERA runtime permission is owned by the Composable, not by this ViewModel (mirrors
 * `MicroscopeScreen`).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val client: ConnectionClient,
    private val store: ConnectionStore,
    private val credentials: CredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** The connection currently stored, so an attempt that fails can fall back to it (R14). */
    private var established: Connection? = null

    /**
     * The secret of the attempt in flight, held here and nowhere else. It waits for the
     * tenant the user still has to pick before both halves can be written together, and it is
     * dropped the moment the attempt ends — [ConnectionState.SelectingTenant] deliberately
     * does not carry it, because that state is observed by the UI (R19).
     */
    private var pendingCredential: Credential = Credential.None

    /** Where the machine rests when no attempt is in flight. */
    private val restingState: ConnectionState
        get() = established?.let { ConnectionState.Connected(it) } ?: ConnectionState.Disconnected

    init {
        viewModelScope.launch {
            established = store.connection.first()
            _state.update { current ->
                if (current is ConnectionState.Loading) restingState else current
            }
        }
    }

    /**
     * Starts collecting the credential [method] needs — from the disconnected state, after
     * a failure, or to change an existing connection to another method (R27). Ignored while
     * an attempt is already in flight, so a stray tap cannot discard a verification.
     */
    fun startConnecting(method: ConnectionMethod) {
        _state.update { current ->
            when (current) {
                ConnectionState.Loading,
                is ConnectionState.Verifying,
                is ConnectionState.SelectingTenant,
                -> current
                else -> method.collectionState()
            }
        }
    }

    /**
     * Fed every barcode ML Kit decodes. Ignored unless the QR code is what is being
     * collected; an unparseable or foreign payload is silently dropped so scanning
     * continues (R44).
     */
    fun onQrDetected(raw: String) {
        if (_state.value != ConnectionState.Collecting.ScanningQr) return
        val request = QrPayloadParser.parse(raw) ?: return
        verify(request)
    }

    /** The device camera could not be bound; leave scanning for a recoverable error state. */
    fun onScannerError() {
        _state.update {
            if (it == ConnectionState.Collecting.ScanningQr) ConnectionState.CameraUnavailable else it
        }
    }

    /**
     * Hands what the user typed to verification (R13). Ignored unless the machine is
     * collecting exactly this method, so a form left behind by a method switch cannot
     * submit into the wrong path.
     */
    fun submit(request: ConnectionRequest) {
        val collecting = _state.value as? ConnectionState.Collecting ?: return
        if (collecting.method == request.method) verify(request)
    }

    /** Adopts the tenant the user picked out of [ConnectionState.SelectingTenant] (R15). */
    fun selectTenant(tenant: Tenant) {
        val selecting = _state.value as? ConnectionState.SelectingTenant ?: return
        val connection = selecting.request
            .connectionFor(tenant.takeIf { it in selecting.tenants }, selecting.identity)
        if (connection != null) {
            val credential = pendingCredential
            viewModelScope.launch { establish(connection, credential) }
        }
    }

    /**
     * Leaves collection, tenant selection or a failure without connecting. The previously
     * stored connection — if any — is what the machine returns to, untouched (R14).
     */
    fun cancel() {
        _state.update { current ->
            when (current) {
                is ConnectionState.Collecting,
                is ConnectionState.SelectingTenant,
                is ConnectionState.Failed,
                ConnectionState.CameraUnavailable,
                -> restingState.also { pendingCredential = Credential.None }
                else -> current
            }
        }
    }

    /**
     * Clears both halves of the stored connection and returns the app to the disconnected
     * state (R25, R28). The secret goes first: whatever else fails, the device must not be
     * left holding a usable credential. Ending the session server-side is the session
     * lifecycle's job (R24), not this state machine's.
     */
    fun disconnect() {
        viewModelScope.launch {
            // A failing erase must not strand the user in a connection they asked to leave;
            // the next connect wipes the record before it writes, so nothing survives it.
            runCatching { credentials.clear() }
            runCatching { store.clear() }
            established = null
            pendingCredential = Credential.None
            _state.value = ConnectionState.Disconnected
        }
    }

    private fun verify(request: ConnectionRequest) {
        pendingCredential = Credential.None
        _state.value = ConnectionState.Verifying(request)
        viewModelScope.launch {
            when (val result = client.connect(request)) {
                is ConnectionResult.Failure ->
                    _state.value = ConnectionState.Failed(request.method, result.reason)
                is ConnectionResult.Verified -> resolveTenant(request, result)
            }
        }
    }

    /** R15's adoption rule; light mode short-circuits it because it has no tenants (R10). */
    private suspend fun resolveTenant(request: ConnectionRequest, result: ConnectionResult.Verified) {
        val choiceNeeded = request.method != ConnectionMethod.LIGHT_MODE && result.tenants.size > 1
        if (choiceNeeded) {
            // The secret waits here rather than in the state the UI observes (R19).
            pendingCredential = result.credential
            _state.value = ConnectionState.SelectingTenant(request, result.tenants, result.identity)
        } else {
            val connection = request.connectionFor(result.tenants.singleOrNull(), result.identity)
            if (connection == null) {
                _state.value = ConnectionState.Failed(request.method, "no tenant is scoped to this credential")
            } else {
                establish(connection, result.credential)
            }
        }
    }

    /**
     * Writes both halves, or neither. The secret is written first, so an interrupted write
     * leaves an orphaned secret rather than a connection the app cannot authenticate; the
     * orphan is then erased on the spot, and every later write wipes the record before it
     * writes anyway.
     *
     * A storage failure is not R14's case: the previous credential has already been
     * overwritten by then, so there is no earlier connection left to fall back to. Rolling
     * all the way back to disconnected is the only honest outcome.
     */
    private suspend fun establish(connection: Connection, credential: Credential) {
        val stored = runCatching {
            credentials.save(credential)
            store.save(connection)
        }
        pendingCredential = Credential.None
        if (stored.isSuccess) {
            established = connection
            _state.value = ConnectionState.Connected(connection)
        } else {
            runCatching { credentials.clear() }
            runCatching { store.clear() }
            established = null
            _state.value = ConnectionState.Failed(connection.method, STORAGE_FAILURE_REASON)
        }
    }
}

/** Diagnostic, never a UI string — and never one that could echo the secret it failed on. */
private const val STORAGE_FAILURE_REASON = "the connection could not be stored"
