package io.github.nolte.kamerplanter.feature.pestdetection

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.github.nolte.kamerplanter.core.network.BoundingBox
import kotlin.math.min

/**
 * Where a finding's box lands on screen.
 *
 * Two coordinate systems meet here and neither is the one the box arrives in. The backend
 * reports fractions of the **full image**; the canvas is whatever space the layout gave the
 * picture; and `ContentScale.Fit` puts the image somewhere inside that space with bars on two
 * sides whenever the aspect ratios differ. Multiplying the fractions by the canvas — the
 * obvious reading — slides every box off its subject by the size of those bars, which on a
 * portrait phone showing a 16:9 capture is most of the screen.
 *
 * Pulled out of the composable because it is the only real arithmetic on that screen, and a
 * box drawn in the wrong place is wrong silently: it still looks like a detection.
 */
internal fun overlayRect(box: BoundingBox, canvas: Size, image: Size): Rect {
    val scale = min(canvas.width / image.width, canvas.height / image.height)
    val drawnWidth = image.width * scale
    val drawnHeight = image.height * scale
    // Fit centres what it letterboxes.
    val left = (canvas.width - drawnWidth) / 2
    val top = (canvas.height - drawnHeight) / 2
    return Rect(
        left = left + (box.x * drawnWidth).toFloat(),
        top = top + (box.y * drawnHeight).toFloat(),
        right = left + ((box.x + box.width) * drawnWidth).toFloat(),
        bottom = top + ((box.y + box.height) * drawnHeight).toFloat(),
    )
}
