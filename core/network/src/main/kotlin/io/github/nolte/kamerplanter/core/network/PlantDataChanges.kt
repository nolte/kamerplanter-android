package io.github.nolte.kamerplanter.core.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Announces that this app changed something about the tenant's plants.
 *
 * Exists because the plant list and a plant's own page are two screens over one dataset, and
 * the list's ViewModel outlives a trip into the detail page: watering a plant there cleared
 * the reminder on the instance and on that page, and the list the user came back to still
 * showed it as overdue. Which reads as the button having done nothing.
 *
 * A signal rather than a reload on resume: the list costs a request per plant for its
 * thumbnails, and reloading it every time the tab regains focus would pay that price on every
 * glance. This fires only when a write actually succeeded.
 *
 * Deliberately carries no payload. What changed is the instance's business — the confirmation
 * may have closed one reminder or rescheduled three — and a screen that acted on a guess about
 * which would be wrong exactly when it mattered.
 */
@Singleton
class PlantDataChanges @Inject constructor() {

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        // A second change while the first is still being read is the same news. Dropping it
        // keeps a burst of confirmations from queueing up a reload each.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val changes: Flow<Unit> = _changes.asSharedFlow()

    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
