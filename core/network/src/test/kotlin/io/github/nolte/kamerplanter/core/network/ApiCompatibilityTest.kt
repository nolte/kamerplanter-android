package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.network.ApiCompatibility.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The judgement in isolation, with floors the shipped constants cannot express: today's
 * [ApiCompatibility.MINIMUM_API_VERSION] is the lowest `apiVersion` ever released, so the
 * below-floor verdict is unreachable through the client until the first floor raise — and
 * this is where its behaviour is pinned down for that day.
 */
class ApiCompatibilityTest {

    @Test
    fun `the shipped floor accepts the version it was built against`() {
        assertEquals(Verdict.Compatible, ApiCompatibility.judge("1.0.0"))
    }

    @Test
    fun `a newer version of the same major is compatible`() {
        assertEquals(Verdict.Compatible, ApiCompatibility.judge("1.7.3"))
    }

    @Test
    fun `a foreign major shares nothing, in either direction`() {
        assertEquals(Verdict.NoSharedMajor("2.0.0"), ApiCompatibility.judge("2.0.0"))
        assertEquals(Verdict.NoSharedMajor("0.9.0"), ApiCompatibility.judge("0.9.0"))
    }

    @Test
    fun `below a raised floor is a warning, not a refusal`() {
        assertEquals(
            Verdict.BelowFloor,
            ApiCompatibility.judge("1.1.9", minimumVersion = "1.2.0"),
        )
        assertEquals(
            Verdict.Compatible,
            ApiCompatibility.judge("1.2.0", minimumVersion = "1.2.0"),
        )
    }

    @Test
    fun `comparison is numeric, not lexicographic`() {
        // "1.10.0" < "1.9.0" as strings; as versions the order is the other way round.
        assertEquals(
            Verdict.Compatible,
            ApiCompatibility.judge("1.10.0", minimumVersion = "1.9.0"),
        )
    }

    @Test
    fun `a pre-release suffix reads as its numeric prefix`() {
        assertEquals(
            Verdict.BelowFloor,
            ApiCompatibility.judge("1.1.0-rc1", minimumVersion = "1.2.0"),
        )
    }

    @Test
    fun `a missing part reads as zero`() {
        assertEquals(
            Verdict.BelowFloor,
            ApiCompatibility.judge("1.1", minimumVersion = "1.2.0"),
        )
    }

    @Test
    fun `nothing to judge is judged compatible`() {
        assertEquals(Verdict.Compatible, ApiCompatibility.judge(null))
        assertEquals(Verdict.Compatible, ApiCompatibility.judge("healthy"))
        assertEquals(Verdict.Compatible, ApiCompatibility.judge(""))
    }
}
