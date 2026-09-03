package io.github.nolte.kamerplanter.feature.plants.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The identifier proposal, held against the web UI's `idGenerator.ts` (R19): the same input
 * has to give the same shape on the phone as in the browser.
 */
class InstanceIdProposalTest {

    private val today = LocalDate.of(2026, 7, 13)

    /** `1_000_000 % 46656 = 20224`, which is `FLS` in base 36. */
    private val clock = 1_000_000L

    @Test
    fun `follows the web UI's shape, prefix from the scientific name`() {
        assertEquals("MONST-0713-FLS", proposeInstanceId("Monstera deliciosa", today, clock, emptySet()))
        assertEquals("AGLAO-0713-FLS", proposeInstanceId("Aglaonema commutatum", today, clock, emptySet()))
    }

    @Test
    fun `the prefix is sanitised as the web UI sanitises it`() {
        // Diacritics folded, the space and the hyphen dropped, five characters at most.
        assertEquals("MENTH-0713-FLS", proposeInstanceId("Ménthe", today, clock, emptySet()))
        assertEquals("ZURIC-0713-FLS", proposeInstanceId("Zürich-1 sp.", today, clock, emptySet()))
        assertEquals("OK-0713-FLS", proposeInstanceId("ok", today, clock, emptySet()))
    }

    @Test
    fun `without a species the prefix is PLANT`() {
        assertEquals("PLANT-0713-FLS", proposeInstanceId(null, today, clock, emptySet()))
        assertEquals("PLANT-0713-FLS", proposeInstanceId("×-!", today, clock, emptySet()))
    }

    @Test
    fun `the date is today's month and day, zero-padded`() {
        assertEquals("MONST-0102-FLS", proposeInstanceId("Monstera", LocalDate.of(2027, 1, 2), clock, emptySet()))
    }

    @Test
    fun `the suffix wraps at 36 cubed and is padded to three characters`() {
        assertEquals("MONST-0713-000", proposeInstanceId("Monstera", today, 46656L, emptySet()))
        assertEquals("MONST-0713-001", proposeInstanceId("Monstera", today, 46657L, emptySet()))
    }

    /** R21: what the web UI leaves to the clock, the form steps past. */
    @Test
    fun `an identifier already in use is stepped past`() {
        val taken = setOf("MONST-0713-FLS", "MONST-0713-FLT")
        assertEquals("MONST-0713-FLU", proposeInstanceId("Monstera deliciosa", today, clock, taken))
    }
}
