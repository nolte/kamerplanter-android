package io.github.nolte.kamerplanter.core.camera

/**
 * An image as it was captured or picked, before any upload-bound normalisation.
 *
 * Held rather than normalised on the spot because one capture can feed two uploads with two
 * profiles (R10): the species recogniser is sent the `recognition` cut, the plant's gallery
 * keeps the `gallery` cut, and re-uploading the recogniser's bytes as the plant's picture would
 * archive a thumbnail. Each cut is made from the original when it is needed rather than kept
 * side by side — a sensor's frame plus two derivatives is enough memory to matter on a low-RAM
 * device.
 *
 * A function type rather than a class so a screen's caller can be tested without a bitmap:
 * the JVM unit tests have no `BitmapFactory`, and what they assert is which profile was asked
 * for, not how the pixels were scaled.
 */
fun interface CapturedImage {

    /**
     * The image brought under [profile] and [maxBytes], upright and stripped of metadata, or
     * `null` when it cannot be. Decodes and re-encodes, so call it off the main thread.
     */
    fun normalized(profile: NormalizationProfile, maxBytes: Int): ByteArray?
}

/** A picked or photographed JPEG, with the rotation its EXIF asked for baked in on the way out. */
class PickedImage(
    private val raw: ByteArray,
    private val rotationDegrees: Int,
) : CapturedImage {

    override fun normalized(profile: NormalizationProfile, maxBytes: Int): ByteArray? =
        JpegDownscale.toUploadable(raw, maxBytes, rotationDegrees, profile)
}
