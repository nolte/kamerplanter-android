package io.github.nolte.kamerplanter.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

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
 * Every transition *out of* a resting state is a compare-and-set rather than a plain write.
 * [onQrDetected] runs on ML Kit's analyzer thread while [cancel] runs on the main thread, so
 * a read-then-write would let a verification overwrite a cancellation the user had already
 * performed — and then persist a credential for a connection they backed out of.
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
     * dropped the moment the attempt ends — no [ConnectionState] carries it, because states
     * are observed by the UI (R19).
     */
    private var pendingCredential: Credential = Credential.None

    /**
     * The request of the attempt in flight, held for the same reason as [pendingCredential]:
     * it carries the plaintext pairing code or API key, so it must not travel in observable
     * state. Needed after tenant selection, when the connection is finally composed.
     */
    private var pendingRequest: ConnectionRequest? = null

    /** Where the machine rests when no attempt is in flight. */
    private val restingState: ConnectionState
        get() = established?.let { ConnectionState.Connected(it) } ?: ConnectionState.Disconnected

    init {
        viewModelScope.launch {
            val stored = store.connection.first()
            val credential = runCatchingCancellable { credentials.load() }.getOrDefault(Credential.None)
            established = stored?.takeIf { it.isWholeWith(credential) }
            if (stored != null && established == null) {
                // The two halves disagree: a connection is stored whose secret cannot be
                // read back. A restore or a device transfer produces exactly this — the
                // connection file returns, the Keystore key does not, and every decrypt
                // fails. Showing `Connected` for an instance the app can never authenticate
                // against gives the user no way to notice, let alone repair, so the orphaned
                // half is dropped and they land on `Disconnected` with the three methods.
                withContext(NonCancellable) { runCatchingCancellable { store.clear() } }
            }
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
     *
     * This runs on the analyzer's executor, not the main thread, so the move out of
     * `ScanningQr` is a compare-and-set: only the caller that wins it proceeds, and a
     * [cancel] that lands first makes this a no-op instead of being overwritten.
     */
    fun onQrDetected(raw: String) {
        if (_state.value != ConnectionState.Collecting.ScanningQr) return
        val request = QrPayloadParser.parse(raw) ?: return
        verify(ConnectionState.Collecting.ScanningQr, request)
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
        if (collecting.method != request.method) return
        verify(collecting, request)
    }

    /**
     * Adopts the tenant the user picked out of [ConnectionState.SelectingTenant] (R15).
     *
     * The move out of `SelectingTenant` is a compare-and-set for the same reason the entry
     * into verification is: two taps on different tenants would otherwise start two
     * concurrent [establish] calls whose store writes interleave, leaving the stored
     * connection describing whichever one happened to finish last.
     */
    fun selectTenant(tenant: Tenant) {
        val selecting = _state.value as? ConnectionState.SelectingTenant ?: return
        if (tenant !in selecting.tenants) return
        val request = pendingRequest ?: return
        val connection = request.connectionFor(tenant, selecting.identity) ?: return
        val credential = pendingCredential
        if (!_state.compareAndSet(selecting, ConnectionState.Verifying(selecting.method))) return
        pendingRequest = null
        viewModelScope.launch { establish(connection, credential) }
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
                -> {
                    pendingCredential = Credential.None
                    pendingRequest = null
                    restingState
                }
                else -> current
            }
        }
    }

    /**
     * Clears both halves of the stored connection and returns the app to the disconnected
     * state (R25, R28). The secret goes first: whatever else fails, the device must not be
     * left holding a usable credential. Ending the session server-side is the session
     * lifecycle's job (R24), not this state machine's.
     *
     * The erase runs [NonCancellable]. `viewModelScope` is cancelled the moment the user
     * navigates away, and a cancelled `DataStore.edit` writes nothing — so without this,
     * tapping Disconnect and leaving immediately would report `Disconnected` while the
     * refresh token stayed on the device and the next launch read the connection back.
     */
    fun disconnect() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                // Storage failures are still tolerated: the next connect wipes the record
                // before it writes, so nothing survives it either way.
                runCatchingCancellable { credentials.clear() }
                runCatchingCancellable { store.clear() }
                established = null
                pendingCredential = Credential.None
                pendingRequest = null
                _state.value = ConnectionState.Disconnected
            }
        }
    }

    /**
     * Moves out of [from] into verification and runs it. The compare-and-set is what makes
     * this safe to call off the main thread: a caller that loses the race does nothing at
     * all — no state write, no stashed secret, no backend call — so a cancellation that
     * landed first stands instead of being overwritten.
     */
    private fun verify(from: ConnectionState, request: ConnectionRequest) {
        if (!_state.compareAndSet(from, ConnectionState.Verifying(request.method))) return
        pendingCredential = Credential.None
        pendingRequest = request
        viewModelScope.launch {
            when (val result = client.connect(request)) {
                is ConnectionResult.Failure -> {
                    pendingCredential = Credential.None
                    pendingRequest = null
                    _state.value = ConnectionState.Failed(request.method, result.reason)
                }
                is ConnectionResult.Verified -> resolveTenant(request, result)
            }
        }
    }

    /** R15's adoption rule; light mode short-circuits it because it has no tenants (R10). */
    private suspend fun resolveTenant(request: ConnectionRequest, result: ConnectionResult.Verified) {
        val choiceNeeded = request.method != ConnectionMethod.LIGHT_MODE && result.tenants.size > 1
        if (choiceNeeded) {
            // Both the secret and the request wait here rather than in observable state (R19).
            pendingCredential = result.credential
            pendingRequest = request
            _state.value = ConnectionState.SelectingTenant(request.method, result.tenants, result.identity)
        } else {
            val connection = request.connectionFor(result.tenants.singleOrNull(), result.identity)
            if (connection == null) {
                pendingCredential = Credential.None
                pendingRequest = null
                _state.value = ConnectionState.Failed(request.method, "no tenant is scoped to this credential")
            } else {
                pendingRequest = null
                establish(connection, result.credential)
            }
        }
    }

    /**
     * Writes both halves, or neither — and distinguishes *which* write failed, because the
     * two failures leave the device in materially different states.
     *
     * [CredentialStore.save] is a single storage transaction: when it throws, nothing was
     * written and the previous secret is still intact. That is exactly R14's no-op case, so
     * the machine reports the failure and falls back to the connection it already had.
     * Wiping there would destroy a working connection the user never asked to leave — the
     * likely trigger being a Keystore key the system invalidated, which has nothing to do
     * with the connection that is already stored.
     *
     * Once the secret *is* written, the stored halves no longer agree with each other, so a
     * failure of the second write clears both. That erase is [NonCancellable]: a cancelled
     * rollback is what would strand the orphaned secret it exists to remove.
     */
    private suspend fun establish(connection: Connection, credential: Credential) {
        pendingCredential = Credential.None

        val secretStored = runCatchingCancellable { credentials.save(credential) }
        if (secretStored.isFailure) {
            _state.value = ConnectionState.Failed(connection.method, STORAGE_FAILURE_REASON)
            return
        }

        val connectionStored = runCatchingCancellable { store.save(connection) }
        if (connectionStored.isSuccess) {
            established = connection
            _state.value = ConnectionState.Connected(connection)
            return
        }

        withContext(NonCancellable) {
            runCatchingCancellable { credentials.clear() }
            runCatchingCancellable { store.clear() }
            established = null
            _state.value = ConnectionState.Failed(connection.method, STORAGE_FAILURE_REASON)
        }
    }
}

/**
 * Whether [this] connection and [credential] are the two halves of one whole. Light mode
 * needs no secret (R10); the other two kinds are only usable with a credential of their own
 * kind. Anything else is a split record — a restore that brought the connection back without
 * its Keystore key, or an interrupted write — and the app cannot authenticate with it.
 */
private fun Connection.isWholeWith(credential: Credential): Boolean = when (this) {
    is Connection.LightMode -> true
    is Connection.QrPairing -> credential is Credential.Session
    is Connection.ApiKey -> credential is Credential.ApiKey
}

/**
 * [runCatching], minus the one throwable it must never swallow. `runCatching` catches
 * `Throwable`, which includes [CancellationException] — so wrapping a suspending call in it
 * turns "this coroutine was cancelled" into an ordinary failure and lets the caller carry on
 * as though the work had merely not succeeded. For an erase, that is the difference between
 * a secret being removed and only appearing to be.
 */
@Suppress("TooGenericExceptionCaught")
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

/** Diagnostic, never a UI string — and never one that could echo the secret it failed on. */
private const val STORAGE_FAILURE_REASON = "the connection could not be stored"
