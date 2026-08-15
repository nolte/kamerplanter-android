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

    @Test
    fun `every result is a power of two`() {
        (1..4000 step 7).forEach { width ->
            val sample = sampleSizeFor(width)
            assertEquals("$width produced a non-power-of-two $sample", 0, sample and (sample - 1))
        }
    }

    /** Rounding up is what keeps the decoded width at or under the target it was asked for. */
    @Test
    fun `the decoded width never exceeds the target`() {
        (1..4000 step 7).forEach { width ->
            val decoded = width / sampleSizeFor(width)
            assert(decoded <= DISPLAY_TARGET_PX) { "$width decoded to $decoded, past $DISPLAY_TARGET_PX" }
        }
    }
}
