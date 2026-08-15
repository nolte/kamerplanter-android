package io.github.nolte.kamerplanter.core.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Brings a phone photo inside the upload contract.
 *
 * A modern phone sensor produces 12–50 megapixels and a JPEG of several megabytes; kamerplanter
 * refuses anything over 8 MB, and a reverse proxy in front of it usually refuses far less. The
 * microscope has no such problem — its frames are already modest — so this is the phone path's
 * own concern rather than something the upload does to everything.
 *
 * Two knobs, applied in that order. The **long edge** is capped first, because the backend
 * downscales to 2048 before tiling anyway: sending more pixels than that is bandwidth spent on
 * detail the recogniser will never see. Quality is only stepped down afterwards, and only if
 * the result is still too large — losing pixels is cheaper than losing sharpness on a subject
 * that may be a mite a few pixels across.
 */
object JpegDownscale {

    /**
     * The longest edge worth sending.
     *
     * The backend's own `pest_detection_max_image_dimension` defaults to 2048 and it downscales
     * to that before tiling, so anything beyond is discarded on arrival. Matching it exactly
     * rather than undercutting: the value is configurable upward, and a client that sent less
     * would silently cap an instance tuned for more.
     */
    const val MAX_EDGE_PX = 2048

    /** Where quality starts. Below this a mite a few pixels across stops being resolvable. */
    private const val INITIAL_QUALITY = 90
    private const val MINIMUM_QUALITY = 60
    private const val QUALITY_STEP = 10

    /**
     * Re-encodes [jpeg] so it is at most [maxBytes], or returns `null` when it cannot be.
     *
     * `null` rather than a best effort: an image that still exceeds the limit would be refused
     * by the instance anyway, and the caller can say so before spending the upload.
     */
    fun toUploadable(jpeg: ByteArray, maxBytes: Int, rotationDegrees: Int = 0): ByteArray? {
        val decoded = decode(jpeg) ?: return null
        val scaled = decoded.scaledToFit(MAX_EDGE_PX).rotated(rotationDegrees)

        var quality = INITIAL_QUALITY
        while (true) {
            val encoded = scaled.encode(quality)
            if (encoded.size <= maxBytes || quality <= MINIMUM_QUALITY) {
                return encoded.takeIf { it.size <= maxBytes }
            }
            quality -= QUALITY_STEP
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Subsampled on the way in, so a 50-megapixel capture never becomes 200 MB of
        // ARGB_8888 in the first place — the scale below would be too late.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), MAX_EDGE_PX)
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    private fun Bitmap.scaledToFit(maxEdge: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return this
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    /**
     * Applies the sensor rotation into the pixels.
     *
     * CameraX reports it as metadata, and the backend strips EXIF before it looks at anything —
     * so a portrait photo that only *says* it is portrait arrives sideways, and every bounding
     * box comes back against an image the user never saw.
     */
    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees % FULL_TURN == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.encode(quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    private const val FULL_TURN = 360
}

/**
 * The subsampling factor that brings [sourceEdge] to [target] or below.
 *
 * The smallest power of two that fits: `BitmapFactory` rounds anything else *down* to one, so
 * asking for 3 gets 2 and decodes twice the intended size — and doubling once more would halve
 * the resolution again, which on a subject a few pixels across is the difference between a
 * finding and nothing.
 */
internal fun sampleSizeFor(sourceEdge: Int, target: Int): Int {
    if (sourceEdge <= target) return 1
    var sample = 2
    while (sourceEdge / sample > target) sample *= 2
    return sample
}
