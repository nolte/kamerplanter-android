package io.github.nolte.kamerplanter.feature.settings

import io.github.nolte.kamerplanter.core.connection.PayloadRefusal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One sentence per refusal, and a different one for each.
 *
 * The exhaustive `when` behind [explanationRes] already forces a new reason to be worded
 * before it compiles. What it cannot catch is the other half of the same mistake: two reasons
 * pointing at one string, which is how "your instance has no TLS" ends up shown for an address
 * that names no host at all — advice for a problem the reader does not have.
 *
 * Both entry points read this function, so a wording covered here is a wording the scanner and
 * the tapped `/connect` link agree on (#40).
 */
class RefusalWordingTest {

    @Test
    fun `every refusal has a wording of its own`() {
        val wordings = PayloadRefusal.entries.map { it.explanationRes() }

        assertEquals(
            "two refusals share a sentence",
            PayloadRefusal.entries.size,
            wordings.distinct().size,
        )
    }
}
