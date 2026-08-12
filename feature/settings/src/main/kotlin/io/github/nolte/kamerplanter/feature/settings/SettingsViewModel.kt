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
 * The pairing flow's state machine, per requirement R9:
 *
 * ```
 * Loading ─▶ Idle ─(startScan)─▶ Scanning ─(valid QR)─▶ Verifying ─▶ Paired | Failed
 *              ▲                     │                                    │
 *              └──────(unpair)───────┴──────(cancel)         (startScan)──┘
 * ```
 *
 * On startup a persisted pairing resolves straight to [PairingState.Paired] (R12). An
 * unparseable/foreign QR is ignored while [PairingState.Scanning] (R15). The CAMERA
 * runtime permission is owned by the Composable, not by this ViewModel (mirrors
 * `MicroscopeScreen`).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val pairingClient: PairingClient,
    private val store: PairingStore,
) : ViewModel() {

    private val _state = MutableStateFlow<PairingState>(PairingState.Loading)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val persisted = store.pairing.first()
            _state.update { current ->
                if (current is PairingState.Loading) {
                    persisted?.let { PairingState.Paired(it) } ?: PairingState.Idle
                } else {
                    current
                }
            }
        }
    }

    /** Opens the scanner from [PairingState.Idle], a prior [PairingState.Failed], or a
     *  [PairingState.CameraUnavailable] retry. */
    fun startScan() {
        _state.update { current ->
            when (current) {
                PairingState.Idle,
                PairingState.CameraUnavailable,
                is PairingState.Failed,
                -> PairingState.Scanning
                else -> current
            }
        }
    }

    /** The device camera could not be bound; leave scanning for a recoverable error state. */
    fun onScannerError() {
        _state.update { if (it is PairingState.Scanning) PairingState.CameraUnavailable else it }
    }

    /**
     * Fed every barcode ML Kit decodes. Ignored unless [PairingState.Scanning]; an
     * unparseable payload is silently dropped so scanning continues (R15). A valid payload
     * moves to [PairingState.Verifying] and drives the (fake) backend call exactly once.
     */
    fun onQrDetected(raw: String) {
        if (_state.value !is PairingState.Scanning) return
        val payload = QrPayloadParser.parse(raw) ?: return
        _state.value = PairingState.Verifying(payload)
        viewModelScope.launch {
            _state.value = when (val result = pairingClient.pair(payload)) {
                PairingResult.Success -> {
                    store.save(payload)
                    PairingState.Paired(payload)
                }
                is PairingResult.Failure -> PairingState.Failed(result.reason)
            }
        }
    }

    /** Leaves the scanner without pairing. */
    fun cancelScan() {
        _state.update { if (it is PairingState.Scanning) PairingState.Idle else it }
    }

    /** Clears the persisted pairing and returns to [PairingState.Idle] (R13). */
    fun unpair() {
        viewModelScope.launch {
            store.clear()
            _state.value = PairingState.Idle
        }
    }
}
