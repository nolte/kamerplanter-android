package io.github.nolte.kamerplanter.core.network

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkModuleTest {

    @Serializable
    private data class Probe(val id: Int)

    @Test
    fun `json tolerates unknown fields from a newer backend`() {
        val json = NetworkModule.provideJson()

        val probe = json.decodeFromString<Probe>("""{"id": 7, "added_in_future_version": true}""")

        assertEquals(7, probe.id)
    }
}
