package io.github.nolte.kamerplanter.feature.pestdetection

import androidx.compose.ui.geometry.Size
import io.github.nolte.kamerplanter.core.network.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A box drawn in the wrong place is wrong silently — it still looks like a detection, just of
 * the leaf next to the mite. These are the cases where "multiply the fractions by the canvas"
 * and "place them against the drawn image" give different answers.
 */
class OverlayGeometryTest {

    private val tolerance = 0.01f

    /** The whole image, so the box has to be exactly the drawn rectangle whatever the fit. */
    private val wholeImage = BoundingBox(x = 0.0, y = 0.0, width = 1.0, height = 1.0)

    @Test
    fun `a box in a canvas of the same aspect ratio spans the canvas`() {
        val rect = overlayRect(wholeImage, canvas = Size(400f, 225f), image = Size(1600f, 900f))

        assertEquals(0f, rect.left, tolerance)
        assertEquals(0f, rect.top, tolerance)
        assertEquals(400f, rect.right, tolerance)
        assertEquals(225f, rect.bottom, tolerance)
    }

    /**
     * The case the naive version gets wrong: a 16:9 capture in a tall portrait canvas is
     * letterboxed, and 87 % of that canvas is empty space above and below the picture.
     */
    @Test
    fun `a wide image in a tall canvas is placed inside the letterbox, not the canvas`() {
        val rect = overlayRect(wholeImage, canvas = Size(400f, 800f), image = Size(1600f, 900f))

        assertEquals("the image fills the width", 0f, rect.left, tolerance)
        assertEquals("the image fills the width", 400f, rect.right, tolerance)
        // 400 wide at 16:9 is 225 tall, centred in 800 → 287.5 of bar above and below.
        assertEquals(287.5f, rect.top, tolerance)
        assertEquals(512.5f, rect.bottom, tolerance)
    }

    /** The other orientation: a tall image in a wide canvas gets bars left and right. */
    @Test
    fun `a tall image in a wide canvas is pillarboxed`() {
        val rect = overlayRect(wholeImage, canvas = Size(800f, 400f), image = Size(900f, 1600f))

        assertEquals(0f, rect.top, tolerance)
        assertEquals(400f, rect.bottom, tolerance)
        // 400 tall at 9:16 is 225 wide, centred in 800 → 287.5 of bar either side.
        assertEquals(287.5f, rect.left, tolerance)
        assertEquals(512.5f, rect.right, tolerance)
    }

    /** A quarter-sized box in the middle stays a quarter-sized box in the middle. */
    @Test
    fun `an off-centre box keeps its position and size within the drawn image`() {
        val rect = overlayRect(
            BoundingBox(x = 0.25, y = 0.5, width = 0.25, height = 0.25),
            canvas = Size(400f, 800f),
            image = Size(1600f, 900f),
        )

        assertEquals(100f, rect.left, tolerance)
        assertEquals(200f, rect.right, tolerance)
        assertEquals(287.5f + 112.5f, rect.top, tolerance)
        assertEquals(287.5f + 168.75f, rect.bottom, tolerance)
    }

    /**
     * The boxes are fractions of the full image, so the decode's subsampling must not move
     * them. Halving the bitmap is what `inSampleSize` does to a large capture.
     */
    @Test
    fun `subsampling the bitmap does not move the box`() {
        val box = BoundingBox(x = 0.3, y = 0.28, width = 0.22, height = 0.2)
        val canvas = Size(400f, 800f)

        assertEquals(
            overlayRect(box, canvas, image = Size(1600f, 900f)),
            overlayRect(box, canvas, image = Size(800f, 450f)),
        )
    }
}
