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
        // Every width, not a sample of them: the only ones that expose the off-by-one are the
        // exact multiples, and a stride skips them.
        (1..4400).forEach { width ->
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

    /**
     * The whole contract in one sweep: the smallest power of two that fits.
     *
     * Both halves are needed and neither implies the other. "At or under the target" alone is
     * satisfied by every factor that is too large — which is exactly the off-by-one this test
     * exists for. "One step less would not fit" is what makes the answer minimal, and it is
     * stated that way rather than as a lower bound on the decoded width, because at a width
     * just past the target the optimal answer really does land at half of it.
     *
     * Every width rather than a stride: the only ones that expose the off-by-one are the exact
     * multiples of the target, and a stride steps over them.
     */
    @Test
    fun `every width gets the smallest power of two that fits`() {
        (1..4400).forEach { width ->
            val sample = sampleSizeFor(width)
            val decoded = width / sample
            assert(decoded <= DISPLAY_TARGET_PX) { "$width decoded to $decoded, past $DISPLAY_TARGET_PX" }
            assert(sample == 1 || width / (sample / 2) > DISPLAY_TARGET_PX) {
                "$width used $sample, but ${sample / 2} would already have fit"
            }
        }
    }
}
