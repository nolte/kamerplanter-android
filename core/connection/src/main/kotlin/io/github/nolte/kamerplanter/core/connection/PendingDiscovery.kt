package io.github.nolte.kamerplanter.core.connection

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A `/connect` link waiting to be acted on.
 *
 * The activity receives the intent and Settings acts on it, and neither can hand it to the
 * other directly: the link can arrive before Settings exists (a cold start from the system
 * camera) or while it is already on screen (`onNewIntent`). A singleton the activity writes
 * and the screen reads covers both without the activity knowing what a connection flow is.
 *
 * State rather than an event stream on purpose. A link that arrives during a cold start would
 * be emitted before anything collects, and a one-shot event would simply be lost — the user
 * scanned a code and the app opened on the wrong screen with no explanation. Held until
 * [consume] instead, so whoever gets there first picks it up.
 */
@Singleton
class PendingDiscovery @Inject constructor() {

    private val _link = MutableStateFlow<DiscoveryLink?>(null)

    /** The link waiting to be acted on, or `null` when there is none. */
    val link: StateFlow<DiscoveryLink?> = _link.asStateFlow()

    private val _arrivals = Channel<Unit>(Channel.CONFLATED)

    /**
     * One signal per link that arrives, for whoever has to *navigate* to it.
     *
     * Separate from [link] because the two readers are independent and one of them removes
     * what it reads. The screen that shows the offer consumes the link; the shell only has to
     * put that screen on top. Sharing one value between them means whichever runs first
     * decides whether the other sees anything — and the screen's collector keeps running while
     * the app is backgrounded, so it wins, and the user comes back to the wrong tab with the
     * offer sitting invisibly behind it.
     *
     * Conflated: two links arriving before either is acted on need one navigation, not two.
     */
    val arrivals: Flow<Unit> = _arrivals.receiveAsFlow()

    fun offer(link: DiscoveryLink) {
        _link.value = link
        _arrivals.trySend(Unit)
    }

    /**
     * Takes the waiting link, leaving nothing behind.
     *
     * Clearing on read is what stops the same link being acted on twice — leaving Settings and
     * returning would otherwise restart a flow the user already dismissed.
     */
    fun consume(): DiscoveryLink? = _link.getAndUpdate { null }
}
