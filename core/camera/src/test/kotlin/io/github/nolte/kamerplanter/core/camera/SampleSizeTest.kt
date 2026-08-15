package io.github.nolte.kamerplanter.core.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The subsampling factor a phone photo is decoded at.
 *
 * Two ways to get this wrong, and both cost real detection quality. Subsampling only halves,
 * so a factor chosen to land *under* the target usually lands far under it — and
 * `BitmapFactory` rounds a non-power-of-two down to one, so asking for 3 silently gets 2. The
 * arithmetic looks right either way, which is why it needs a test rather than a comment.
 */
class SampleSizeTest {

    private val target = JpegDownscale.MAX_EDGE_PX

    @Test
    fun `a source at or below the target is not subsampled`() {
        assertEquals(1, sampleSizeFor(target, target))
        assertEquals(1, sampleSizeFor(800, target))
    }

    /**
     * The case that was wrong: an ordinary 16-megapixel sensor width.
     *
     * 4624 halves to 2312, which is still above the cap, and halves again to 1156 — 44 % of the
     * linear resolution thrown away before anything has looked at the image. The decode stops
     * at 2312 and the scale takes it to 2048 exactly.
     */
    @Test
    fun `a sensor width does not overshoot the cap`() {
        assertEquals(2, sampleSizeFor(4624, target))
        assertEquals(4, sampleSizeFor(9000, target))
        // 4000 halves to 2000, which is already below the cap — so it is not halved at all,
        // and the scale does the whole reduction.
        assertEquals(1, sampleSizeFor(4000, target))
    }

    /** An exact multiple may halve all the way down to the target, and not one step further. */
    @Test
    fun `an exact multiple lands exactly on the target`() {
        assertEquals(2, sampleSizeFor(target * 2, target))
        assertEquals(4, sampleSizeFor(target * 4, target))
    }

    /**
     * The whole contract: the largest power of two that still leaves the target reachable by
     * scaling down. Halving once more would take the image below the cap, which the scale
     * cannot undo.
     */
    @Test
    fun `every width keeps enough pixels to reach the target`() {
        (1..12_000).forEach { width ->
            val sample = sampleSizeFor(width, target)
            assert(sample and (sample - 1) == 0) { "$width produced a non-power-of-two $sample" }
            assert(width / sample >= target || width <= target) {
                "$width decoded to ${width / sample}, below the $target it must still reach"
            }
            assert(width / (sample * 2) < target) {
                "$width used $sample, but ${sample * 2} would still have reached $target"
            }
        }
    }
}
