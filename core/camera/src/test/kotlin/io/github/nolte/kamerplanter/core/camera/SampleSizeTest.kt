package io.github.nolte.kamerplanter.core.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The subsampling factor a phone photo is decoded at.
 *
 * `BitmapFactory` rounds a non-power-of-two `inSampleSize` **down** to one, so asking for 3
 * gets 2 and decodes twice the intended size — on a 50-megapixel capture that is the
 * difference between a bitmap that fits and an `OutOfMemoryError`. The arithmetic looks right
 * either way, which is why it needs a test rather than a comment.
 */
class SampleSizeTest {

    private val target = JpegDownscale.MAX_EDGE_PX

    @Test
    fun `a source at or below the target is not subsampled`() {
        assertEquals(1, sampleSizeFor(target, target))
        assertEquals(1, sampleSizeFor(800, target))
    }

    /** A 4000-wide phone photo: 4000/2048 is 1.95, and rounding down would not shrink at all. */
    @Test
    fun `a photo just past the target is halved once`() {
        assertEquals(2, sampleSizeFor(target + 1, target))
        assertEquals(2, sampleSizeFor(4000, target))
    }

    /** An exact multiple must not be halved once more — 4096/2 is already at the target. */
    @Test
    fun `an exact multiple of the target is not subsampled twice`() {
        assertEquals(2, sampleSizeFor(target * 2, target))
        assertEquals(4, sampleSizeFor(target * 4, target))
    }

    /** The whole contract: the smallest power of two that fits. */
    @Test
    fun `every width gets the smallest power of two that fits`() {
        (1..9000).forEach { width ->
            val sample = sampleSizeFor(width, target)
            assert(sample and (sample - 1) == 0) { "$width produced a non-power-of-two $sample" }
            assert(width / sample <= target) { "$width decoded to ${width / sample}, past $target" }
            assert(sample == 1 || width / (sample / 2) > target) {
                "$width used $sample, but ${sample / 2} would already have fit"
            }
        }
    }
}
