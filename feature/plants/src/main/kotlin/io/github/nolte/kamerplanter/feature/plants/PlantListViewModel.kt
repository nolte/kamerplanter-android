package io.github.nolte.kamerplanter.feature.plants

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.network.AuthenticatedImageClient
import io.github.nolte.kamerplanter.core.network.PlantListOutcome
import io.github.nolte.kamerplanter.core.network.PlantsClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Plants tab.
 *
 * The list is a function of the connection, so the connection is what it follows: connecting
 * loads, disconnecting clears. Nothing is fetched while disconnected, which is a requirement
 * rather than an optimisation — plant data belongs to an instance, and keeping the last
 * tenant's plants on screen after signing out of it would be a small leak of someone's data
 * to whoever holds the phone next.
 */
@HiltViewModel
class PlantListViewModel @Inject constructor(
    private val plants: PlantsClient,
    /**
     * Handed to the screen so Coil can fetch tenant-scoped thumbnails with the stored
     * credential. It travels through the ViewModel rather than being injected into the
     * Composable because the screen is built by `hiltViewModel()` and has no graph of its own.
     */
    val imageClient: AuthenticatedImageClient,
    connections: ConnectionStore,
) : ViewModel() {

    private val _state = MutableStateFlow<PlantListState>(PlantListState.Loading)
    val state: StateFlow<PlantListState> = _state.asStateFlow()

    /**
     * The load in flight. Held so a reconnect can cancel the previous one: without that, a
     * slow load against the old instance could finish after the new one and put the previous
     * tenant's plants on screen.
     */
    private var loading: Job? = null

    init {
        viewModelScope.launch {
            connections.connection
                // Only a *change* of instance matters. DataStore re-emits on every write, and
                // an unrelated write would otherwise restart the load and blank the list.
                //
                // Keyed on the address *and* on whether the screen is currently stuck: after
                // a refused credential, re-pairing with the same instance and tenant produces
                // an identical address, so keying on the address alone would suppress the
                // emission and leave the list reporting a rejected credential forever. The
                // only way out would be killing the process — the tab's ViewModel survives
                // navigation, and that state offers no retry by design.
                .distinctUntilChanged { old, new ->
                    old.addresses() == new.addresses() && !credentialWasRejected()
                }
                .collect { connection ->
                    loading?.cancel()
                    if (connection == null) {
                        _state.value = PlantListState.NotConnected
                    } else {
                        load()
                    }
                }
        }
    }

    private fun credentialWasRejected(): Boolean =
        (_state.value as? PlantListState.Failed)?.credentialRejected == true

    /** Re-runs the load; the screen offers this after a failure. */
    fun retry() {
        loading?.cancel()
        load()
    }

    private fun load() {
        _state.value = PlantListState.Loading
        loading = viewModelScope.launch {
            _state.value = when (val outcome = plants.loadPlants()) {
                is PlantListOutcome.Loaded ->
                    if (outcome.plants.isEmpty()) {
                        PlantListState.Empty
                    } else {
                        PlantListState.Content(outcome.plants)
                    }
                PlantListOutcome.Unauthorized -> PlantListState.Failed(credentialRejected = true)
                is PlantListOutcome.Unavailable -> PlantListState.Failed(credentialRejected = false)
            }
        }
    }
}

/**
 * What identifies the instance-and-tenant a list belongs to. Two connections that address the
 * same plants need no reload; anything else does.
 */
private fun Connection?.addresses(): String? = this?.let { "${it.baseUrl}/${it.tenantSlug}" }
