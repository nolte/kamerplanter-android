package io.github.nolte.kamerplanter.feature.microscope

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/** A window of a camera frame, in pixels. */
internal data class CropRegion(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The centred window a digital zoom of [zoomFactor] magnifies out of a [width] x
 * [height] frame.
 *
 * Every edge lands on an even pixel: NV21 subsamples chroma 2x2, so a crop on an odd
 * boundary shifts the colour planes against the luma plane.
 */
internal fun zoomedRegion(width: Int, height: Int, zoomFactor: Float): CropRegion {
    val cropWidth = (width / zoomFactor).toInt().floorToEven().coerceIn(MIN_EDGE, width.floorToEven())
    val cropHeight = (height / zoomFactor).toInt().floorToEven().coerceIn(MIN_EDGE, height.floorToEven())
    return CropRegion(
        left = ((width - cropWidth) / 2).floorToEven(),
        top = ((height - cropHeight) / 2).floorToEven(),
        width = cropWidth,
        height = cropHeight,
    )
}

/** Encodes [region] of the NV21 [frame] as a JPEG. */
internal fun nv21ToJpeg(
    frame: ByteArray,
    width: Int,
    height: Int,
    region: CropRegion,
    quality: Int,
): ByteArray {
    val stream = ByteArrayOutputStream()
    val rect = Rect(
        region.left,
        region.top,
        region.left + region.width,
        region.top + region.height,
    )
    val compressed = YuvImage(frame, ImageFormat.NV21, width, height, null)
        .compressToJpeg(rect, quality, stream)
    check(compressed) { "NV21 -> JPEG compression failed" }
    return stream.toByteArray()
}

private fun Int.floorToEven(): Int = this and 1.inv()

/** One chroma sample block — the smallest crop NV21 can express. */
private const val MIN_EDGE = 2
