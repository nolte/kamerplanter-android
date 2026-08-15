package io.github.nolte.kamerplanter.core.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The JVM stand-in for `DataStoreConnectionStore`.
 *
 * A shared fixture rather than a copy per test file: three suites across two modules drive
 * the connection — the refresh cycle, the plant list and the settings state machine — and a
 * fake that drifts between them would leave each testing a slightly different store while
 * all of them stay green. It also stopped being optional the moment two of those files
 * declared a `FakeConnectionStore` in the same package.
 *
 * [current] exposes what is stored so a test can assert that a failure cleared it, which is
 * what R23 turns on.
 */
class FakeConnectionStore(initial: Connection? = null) : ConnectionStore {

    private val flow = MutableStateFlow(initial)

    override val connection: Flow<Connection?> = flow

    val current: Connection? get() = flow.value

    /** Emits a new connection the way a re-pairing would. */
    fun set(connection: Connection?) {
        flow.value = connection
    }

    override suspend fun save(connection: Connection) {
        flow.value = connection
    }

    override suspend fun clear() {
        flow.value = null
    }
}
