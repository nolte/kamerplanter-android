package io.github.nolte.kamerplanter.feature.microscope

import org.junit.Assert.assertEquals
import org.junit.Test

class CropRegionTest {

    @Test
    fun `unzoomed capture keeps the whole frame`() {
        assertEquals(
            CropRegion(left = 0, top = 0, width = 1920, height = 1080),
            zoomedRegion(width = 1920, height = 1080, zoomFactor = 1f),
        )
    }

    @Test
    fun `doubling the zoom halves each edge and stays centred`() {
        assertEquals(
            CropRegion(left = 480, top = 270, width = 960, height = 540),
            zoomedRegion(width = 1920, height = 1080, zoomFactor = 2f),
        )
    }

    @Test
    fun `every edge lands on an even pixel so the chroma planes stay aligned`() {
        val region = zoomedRegion(width = 1920, height = 1080, zoomFactor = 2.7f)

        listOf(region.left, region.top, region.width, region.height).forEach { edge ->
            assertEquals("$edge should be even", 0, edge % 2)
        }
    }

    @Test
    fun `a crop never reaches outside the frame`() {
        val region = zoomedRegion(width = 640, height = 480, zoomFactor = 0.5f)

        assertEquals(0, region.left)
        assertEquals(0, region.top)
        assertEquals(640, region.width)
        assertEquals(480, region.height)
    }
}
