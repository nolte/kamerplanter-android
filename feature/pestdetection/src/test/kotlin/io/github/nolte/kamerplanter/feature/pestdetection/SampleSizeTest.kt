package io.github.nolte.kamerplanter.feature.pestdetection

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `BitmapFactory` rounds a non-power-of-two `inSampleSize` **down** to one, so asking for 3
 * gets 2 — a silent doubling of the decoded bitmap. On the reference device's 4K capture that
 * is the difference between ~4.5 MB and ~14 MB of ARGB_8888 held for a view a few hundred
 * pixels wide, which is an OOM candidate on a low-RAM phone and jank everywhere else. The
 * arithmetic looks right either way, which is why it needs a test rather than a comment.
 */
class SampleSizeTest {

    @Test
    fun `a source at or below the target is not subsampled`() {
        assertEquals(1, sampleSizeFor(1080))
        assertEquals(1, sampleSizeFor(800))
    }

    /** The case the naive division gets wrong: 3840 / 1080 is 3, which the decoder reads as 2. */
    @Test
    fun `a 4K capture is halved twice, not once`() {
        assertEquals(4, sampleSizeFor(3840))
    }

    /** One past the target still needs a halving; one below must not get one. */
    @Test
    fun `the boundary either side of the target`() {
        assertEquals(1, sampleSizeFor(DISPLAY_TARGET_PX))
        assertEquals(2, sampleSizeFor(DISPLAY_TARGET_PX + 1))
    }

    @Test
    fun `every result is a power of two`() {
        (1..4000 step 7).forEach { width ->
            val sample = sampleSizeFor(width)
            assertEquals("$width produced a non-power-of-two $sample", 0, sample and (sample - 1))
        }
    }

    /**
     * An exact multiple of the target must not be halved once more.
     *
     * This is the boundary the loop condition gets wrong when it uses `>=`: 2160 / 2 is
     * exactly 1080, which is already at the target, so a further halving decodes at 540 px.
     * The upper-bound assertion below cannot see that — both answers satisfy it.
     */
    @Test
    fun `an exact multiple of the target is not subsampled twice`() {
        assertEquals(2, sampleSizeFor(2160))
        assertEquals(4, sampleSizeFor(4320))
    }

    /** Both bounds together: at or under the target, and never further than necessary. */
    @Test
    fun `the decoded width lands in the half-open band below the target`() {
        (1..4000 step 7).forEach { width ->
            val decoded = width / sampleSizeFor(width)
            assert(decoded <= DISPLAY_TARGET_PX) { "$width decoded to $decoded, past $DISPLAY_TARGET_PX" }
            assert(decoded > DISPLAY_TARGET_PX / 2 || width <= DISPLAY_TARGET_PX) {
                "$width decoded to $decoded — subsampled one step further than needed"
            }
        }
    }
}
