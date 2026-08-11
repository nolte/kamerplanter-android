package io.github.nolte.kamerplanter.feature.microscope

import com.serenegiant.usb.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reference device's mode list (issue #1). Its 4K mode is why the preview must not
 * simply take the largest thing on offer: at roughly 4.7 fps it cannot be framed by.
 */
private fun referenceModes() = listOf(
    mode(3840, 2160),
    mode(2048, 1024),
    mode(1920, 1080),
    mode(1280, 720),
)

private fun mode(width: Int, height: Int) = Size(0, 0, 0, width, height)

class ModeSelectionTest {

    @Test
    fun `the preview takes the largest mode inside its budget`() {
        val chosen = referenceModes().bestFitting(maxWidth = 1920, maxHeight = 1080)

        assertEquals(1920, chosen?.width)
        assertEquals(1080, chosen?.height)
    }

    @Test
    fun `a mode wider than the budget is rejected even when it is shorter`() {
        // 2048x1024 has fewer pixels than 1920x1080 but is too wide; both axes bind.
        val chosen = listOf(mode(2048, 1024), mode(1280, 720)).bestFitting(1920, 1080)

        assertEquals(1280, chosen?.width)
    }

    @Test
    fun `a device offering nothing small enough still streams, at its smallest mode`() {
        val chosen = listOf(mode(3840, 2160), mode(2560, 1440)).bestFitting(1920, 1080)

        assertEquals(2560, chosen?.width)
    }

    @Test
    fun `a device reporting no modes selects nothing rather than inventing one`() {
        assertNull(emptyList<Size>().bestFitting(1920, 1080))
        assertNull(emptyList<Size>().largest())
    }

    @Test
    fun `the still takes the largest mode the device has`() {
        val still = referenceModes().largest()

        assertEquals(3840, still?.width)
        assertEquals(2160, still?.height)
    }
}
