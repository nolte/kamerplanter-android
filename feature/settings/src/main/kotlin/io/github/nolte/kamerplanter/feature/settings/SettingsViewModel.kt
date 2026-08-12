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
 * The CAMERA runtime permission is owned by the Composable, not by this ViewModel (mirrors
 * `MicroscopeScreen`).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val client: ConnectionClient,
    private val store: ConnectionStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** The connection currently stored, so an attempt that fails can fall back to it (R14). */
    private var established: Connection? = null

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
            viewModelScope.launch { establish(connection) }
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
                -> restingState
                else -> current
            }
        }
    }

    /**
     * Clears the stored connection and returns the app to the disconnected state (R25,
     * R28). Ending the session server-side is the session lifecycle's job (R24), not this
     * state machine's.
     */
    fun disconnect() {
        viewModelScope.launch {
            store.clear()
            established = null
            _state.value = ConnectionState.Disconnected
        }
    }

    private fun verify(request: ConnectionRequest) {
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
            _state.value = ConnectionState.SelectingTenant(request, result.tenants, result.identity)
        } else {
            val connection = request.connectionFor(result.tenants.singleOrNull(), result.identity)
            if (connection == null) {
                _state.value = ConnectionState.Failed(request.method, "no tenant is scoped to this credential")
            } else {
                establish(connection)
            }
        }
    }

    private suspend fun establish(connection: Connection) {
        store.save(connection)
        established = connection
        _state.value = ConnectionState.Connected(connection)
    }
}
