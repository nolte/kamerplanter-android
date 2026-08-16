package io.github.nolte.kamerplanter.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionClient
import io.github.nolte.kamerplanter.core.connection.ConnectionMethod
import io.github.nolte.kamerplanter.core.connection.ConnectionRequest
import io.github.nolte.kamerplanter.core.connection.ConnectionResult
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.Credential
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.connection.PendingDiscovery
import io.github.nolte.kamerplanter.core.connection.Tenant
import io.github.nolte.kamerplanter.core.connection.sameInstanceAs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
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
    private val discoveries: PendingDiscovery,
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
     *
     * `@Volatile` because it is written from ML Kit's analyzer thread ([onQrDetected] →
     * [verify]) and read from the main thread. The compare-and-set on `_state` orders the
     * *transitions*, not these fields; without the annotation a write from one thread may
     * never become visible to the other.
     */
    @Volatile
    private var pendingCredential: Credential = Credential.None

    /**
     * The request of the attempt in flight, held for the same reason as [pendingCredential]:
     * it carries the plaintext pairing code or API key, so it must not travel in observable
     * state. Needed after tenant selection, when the connection is finally composed.
     */
    @Volatile
    private var pendingRequest: ConnectionRequest? = null

    /** Where the machine rests when no attempt is in flight. */
    private val restingState: ConnectionState
        get() = established?.let { ConnectionState.Connected(it) } ?: ConnectionState.Disconnected

    init {
        viewModelScope.launch {
            // Both reads are guarded. A DataStore file can be truncated by a power loss
            // mid-write or come back damaged from a partial restore, and its flow throws
            // rather than yielding null — unguarded, that crashes the app on every visit to
            // Settings, with no in-app way out.
            val storedConnection = runCatchingCancellable { store.connection.first() }
            val storedCredential = runCatchingCancellable { credentials.load() }

            established = storedConnection.getOrNull()
                ?.takeIf { storedCredential.getOrNull()?.let(it::isWholeWith) == true }
            credentials.clearOrphanedSecret(storedConnection.getOrNull(), storedCredential.getOrNull())

            _state.update { current ->
                if (current is ConnectionState.Loading) restingState else current
            }

            // Collected only after the stored connection has been read: the relation a
            // discovered instance stands in is a comparison against that connection, and
            // deciding it against a not-yet-loaded `established` would call every link "new".
            //
            // Both signals, not just the link. A link arriving mid-verification must not
            // interrupt it — the user may have switched to their camera app and scanned the
            // poster again, and the deliberate action wins — but it must still be offered once
            // that attempt ends. Collecting the link alone would never fire again, because
            // nothing about it changes while it waits: the offer would be lost until the
            // process died.
            combine(_state, discoveries.link, ::Pair).collect { (current, waiting) ->
                if (waiting == null || !current.isRestingState()) return@collect
                val offer = offerOf(waiting.baseUrl, established?.baseUrl)
                // Consumed only once the transition has landed. `update` re-runs its lambda on
                // a lost compare-and-set, so consuming inside one would take the link on the
                // first attempt and hand back nothing on the second — scanned, swallowed,
                // never shown. This file says as much in `cancel()`. A failed CAS here leaves
                // the link pending, and the next emission tries again.
                if (_state.compareAndSet(current, offer)) discoveries.consume(waiting)
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
                // Continuing from a discovered instance is the same act, with the link's
                // address travelling along — it is what the link was for (#13).
                is ConnectionState.Discovered ->
                    method.collectionState(prefilledBaseUrl = current.baseUrl)
                else -> method.collectionState()
            }
        }
    }

    /**
     * Fed every barcode ML Kit decodes, and answers what became of it.
     *
     * A payload this build cannot read is dropped so scanning continues (R44) — a stray QR in
     * frame must not end the scan — but it is reported as [QrReading.FOREIGN] rather than
     * dropped in silence, because "no kamerplanter code here" and "the camera sees nothing"
     * are the same picture to the person holding the phone.
     *
     * [QrScannerView] delivers on the main thread, so the read of `ScanningQr` and the move
     * out of it are one uninterrupted step. The compare-and-set form is kept anyway: it costs
     * nothing and it survives a caller that stops marshalling.
     */
    fun onQrDetected(raw: String): QrReading {
        val scanning = _state.value as? ConnectionState.Collecting.ScanningQr
            ?: return QrReading.STALE
        return when (val payload = QrPayloadParser.parse(raw)) {
            null -> QrReading.FOREIGN
            is QrPayload.Pairing -> {
                verify(scanning, payload.request)
                QrReading.ACCEPTED
            }
            // A link is an address, not a decision. It reaches the same offer a `/connect`
            // deep link does — including the warning that continuing replaces a working
            // connection — rather than silently starting an attempt against another instance
            // because its code happened to be in frame first.
            //
            // Unless the scan was *started* from that offer. The web UI shows both codes on
            // one dialogue and the analyser takes whichever it decodes first, so a user who
            // answered "yes, this instance" and returned to the scanner would be offered the
            // same instance again the moment its link won the frame — a loop out of which
            // pairing was reachable only by pointing the camera away.
            is QrPayload.Discovery -> {
                if (scanning.prefilledBaseUrl?.sameInstanceAs(payload.baseUrl) == true) {
                    QrReading.STALE
                } else {
                    _state.compareAndSet(scanning, offerOf(payload.baseUrl, established?.baseUrl))
                    QrReading.ACCEPTED
                }
            }
        }
    }

    /** The device camera could not be bound; leave scanning for a recoverable error state. */
    fun onScannerError() {
        _state.update {
            if (it is ConnectionState.Collecting.ScanningQr) ConnectionState.CameraUnavailable else it
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
        // getAndUpdate, not update-with-side-effects: the lambda re-runs on a lost CAS, so
        // clearing the pending fields inside it could wipe a request that the winning
        // transition had just stashed. Whether anything was actually left is decided by the
        // state we replaced, once, afterwards.
        val previous = _state.getAndUpdate { current ->
            when (current) {
                is ConnectionState.Collecting,
                is ConnectionState.SelectingTenant,
                is ConnectionState.Failed,
                ConnectionState.CameraUnavailable,
                // Dismissing a discovered instance is leaving without connecting, which is
                // what this already means. Nothing was in flight, so nothing is discarded.
                is ConnectionState.Discovered,
                -> restingState
                else -> current
            }
        }
        val left = previous is ConnectionState.Collecting ||
            previous is ConnectionState.SelectingTenant ||
            previous is ConnectionState.Failed ||
            previous == ConnectionState.CameraUnavailable
        if (left) {
            pendingCredential = Credential.None
            pendingRequest = null
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
                    _state.value = ConnectionState.Failed(
                        request.method,
                        result.reason,
                        request.baseUrl,
                        unreachable = result.unreachable,
                    )
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
                _state.value = ConnectionState.Failed(
                    request.method,
                    "no tenant is scoped to this credential",
                    request.baseUrl,
                )
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
            _state.value = ConnectionState.Failed(connection.method, STORAGE_FAILURE_REASON, connection.baseUrl)
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
            _state.value = ConnectionState.Failed(connection.method, STORAGE_FAILURE_REASON, connection.baseUrl)
        }
    }
}

/**
 * Repairs a stored pair whose halves do not belong together — but only in the direction that
 * is unambiguous.
 *
 * A **credential without a connection** is always garbage and always a liability: a
 * decryptable refresh token or API key that nothing can reach, left behind when a process
 * died between [SettingsViewModel]'s two writes. Nothing else ever removes it — a user
 * sitting at `Disconnected` cannot press Disconnect — so it would outlive the app's own
 * lifecycle. It is erased (R25).
 *
 * A **connection without a readable credential** is deliberately *not* erased. It looks
 * identical whether the Keystore key is permanently gone or a single decrypt failed:
 * [CredentialStore] reports both as [Credential.None], and so does a read that threw.
 * Deleting on that evidence would make a transient failure cost the user their pairing.
 * They land on `Disconnected` and can reconnect — which wipes the record before writing
 * anyway — while the bytes stay put in case the next read succeeds.
 */
private suspend fun CredentialStore.clearOrphanedSecret(
    connection: Connection?,
    credential: Credential?,
) {
    if (connection == null && credential != null && credential != Credential.None) {
        withContext(NonCancellable) { runCatchingCancellable { clear() } }
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

/**
 * Where the machine may be interrupted by something the user did outside it.
 *
 * A link can arrive at any moment — the user switches to their camera app mid-pairing and
 * scans the poster again — and discarding a verification for it would be the wrong answer to
 * the more deliberate action.
 */
private fun ConnectionState.isRestingState(): Boolean = when (this) {
    ConnectionState.Loading,
    is ConnectionState.Verifying,
    is ConnectionState.SelectingTenant,
    -> false
    else -> true
}

/**
 * The offer a discovered instance deserves, however it was discovered.
 *
 * One builder for both routes — the `/connect` deep link and the same link scanned in-app —
 * because the two differ only in how the address arrived. Built outside the state machine, as
 * [relationTo] already is: it decides nothing about the connection, it only describes one.
 */
private fun offerOf(discovered: String, connected: String?) = ConnectionState.Discovered(
    baseUrl = discovered,
    relation = relationTo(connected, discovered),
)

/**
 * How a [discovered] instance stands to the one already [connected].
 *
 * The distinction is the whole reason a link does not simply start a connection attempt:
 * scanning the code on the instance you are already connected to should say so rather than
 * walk you through pairing again, and scanning a different one is about to replace a working
 * connection — which the user should learn before, not after.
 */
private fun relationTo(connected: String?, discovered: String): DiscoveredInstance = when {
    connected == null -> DiscoveredInstance.NEW
    connected.sameInstanceAs(discovered) -> DiscoveredInstance.ALREADY_CONNECTED
    else -> DiscoveredInstance.REPLACES_ANOTHER
}
