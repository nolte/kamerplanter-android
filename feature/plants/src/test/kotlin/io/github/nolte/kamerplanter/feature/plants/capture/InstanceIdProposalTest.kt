package io.github.nolte.kamerplanter.feature.plants.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R19–R21: the identifier proposal follows the instance's planting-run convention. */
class InstanceIdProposalTest {

    @Test
    fun `location, three letters of the species key and the first free sequence`() {
        assertEquals("balcony_MON_01", proposeInstanceId("monstera-deliciosa", "balcony", emptySet()))
    }

    @Test
    fun `the sequence skips what is already taken`() {
        val taken = setOf("balcony_MON_01", "balcony_MON_02", "balcony_MON_04")

        assertEquals("balcony_MON_03", proposeInstanceId("monstera-deliciosa", "balcony", taken))
    }

    /** R20: without a location the segment is left out rather than filled with a placeholder. */
    @Test
    fun `no location means no location segment`() {
        assertEquals("MON_01", proposeInstanceId("monstera-deliciosa", null, emptySet()))
        assertEquals("MON_01", proposeInstanceId("monstera-deliciosa", " ", emptySet()))
    }

    @Test
    fun `a two-letter key still yields a prefix, a shorter one none`() {
        assertEquals("OK_01", proposeInstanceId("ok", null, emptySet()))
        assertNull(proposeInstanceId("x-1", null, emptySet()))
        assertNull(proposeInstanceId(null, "balcony", emptySet()))
    }
}
