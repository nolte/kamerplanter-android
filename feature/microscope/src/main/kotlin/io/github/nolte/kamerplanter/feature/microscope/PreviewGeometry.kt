package io.github.nolte.kamerplanter.feature.microscope

import android.graphics.Matrix
import com.jiangdg.utils.Size

/**
 * The transform that fits a [streamWidth] x [streamHeight] stream into a
 * [viewWidth] x [viewHeight] `TextureView` without distorting it, magnified by
 * [zoomFactor] about the centre.
 *
 * A `TextureView` stretches its surface to fill itself, so a 16:9 stream in a taller view
 * would render squashed unless the aspect correction is applied here.
 */
internal fun previewTransform(
    viewWidth: Int,
    viewHeight: Int,
    streamWidth: Int,
    streamHeight: Int,
    zoomFactor: Float,
): Matrix {
    val matrix = Matrix()
    val viewIsLaidOut = viewWidth > 0 && viewHeight > 0
    val streamIsKnown = streamWidth > 0 && streamHeight > 0
    if (!viewIsLaidOut || !streamIsKnown) {
        return matrix
    }
    // The view already shows the stream stretched to its own bounds; these factors undo
    // that stretch and re-apply it uniformly, so the shorter axis letterboxes.
    val fit = minOf(
        viewWidth.toFloat() / streamWidth,
        viewHeight.toFloat() / streamHeight,
    )
    val scaleX = fit * streamWidth / viewWidth
    val scaleY = fit * streamHeight / viewHeight
    matrix.setScale(scaleX * zoomFactor, scaleY * zoomFactor, viewWidth / 2f, viewHeight / 2f)
    return matrix
}

/**
 * The mode to stream in: the largest the device offers that stays within [maxWidth] x
 * [maxHeight], falling back to the smallest mode when everything on offer is bigger.
 *
 * Sizes come from the device's own descriptors, so a body that lies about its modes is
 * caught when the stream fails to start rather than by guessing here.
 */
internal fun List<Size>.bestFitting(maxWidth: Int, maxHeight: Int): Size? {
    if (isEmpty()) {
        return null
    }
    val withinBudget = filter { it.width <= maxWidth && it.height <= maxHeight }
    return withinBudget.maxByOrNull { it.width.toLong() * it.height }
        ?: minByOrNull { it.width.toLong() * it.height }
}

/** The largest mode the device offers — what a still is worth, unlike a preview. */
internal fun List<Size>.largest(): Size? = maxByOrNull { it.width.toLong() * it.height }
