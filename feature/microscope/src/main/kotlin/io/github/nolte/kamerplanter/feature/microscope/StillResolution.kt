package io.github.nolte.kamerplanter.feature.microscope

import android.util.Log
import com.jiangdg.ausbc.camera.CameraUVC
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [grab] with the stream switched to the sensor's largest mode, then restores the
 * preview mode.
 *
 * The reference device streams 4K at roughly 4.7 fps against 17 fps at 1080p (issue #1),
 * far too slow to frame a leaf by — so the preview stays at 1080p and only the still
 * costs the extra seconds. Note that AUSBC implements a resolution change as a full
 * stream restart with a hard-coded one-second pause, so the preview freezes for a few
 * seconds per shot.
 *
 * Degrades rather than fails: if the device offers nothing larger, or the switch does
 * not take within [SWITCH_TIMEOUT_MS], [grab] still runs at whatever mode is live.
 */
internal suspend fun <T> CameraUVC.atLargestResolution(grab: suspend () -> T): T {
    val request = getCameraRequest()
    val previewWidth = request?.previewWidth
    val previewHeight = request?.previewHeight
    val largest = runCatching { getAllPreviewSizes(null).maxByOrNull { it.width * it.height } }
        .onFailure { Log.w(TAG, "cannot read the supported resolutions", it) }
        .getOrNull()

    val worthSwitching = largest != null && previewWidth != null && previewHeight != null &&
        (largest.width != previewWidth || largest.height != previewHeight)
    if (!worthSwitching) {
        return grab()
    }
    return try {
        switchTo(largest.width, largest.height)
        grab()
    } finally {
        // The preview has to come back even when the capture was cancelled.
        withContext(NonCancellable) { switchTo(previewWidth, previewHeight) }
    }
}

/** Requests [width] x [height] and waits until the restarted stream actually runs at it. */
private suspend fun CameraUVC.switchTo(width: Int, height: Int) {
    updateResolution(width, height)
    val switched = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
        // updateResolution closes the camera, waits a second, then reopens it, so the
        // request only reports the new size once the new stream is up.
        while (!isCameraOpened() || getCameraRequest()?.previewWidth != width) {
            delay(POLL_INTERVAL_MS)
        }
        true
    }
    if (switched == null) {
        Log.w(TAG, "stream did not reach ${width}x$height in time")
    }
}

private const val TAG = "MicroscopeCamera"

/** Covers AUSBC's one-second restart pause plus the device's own open time. */
private const val SWITCH_TIMEOUT_MS = 6_000L
private const val POLL_INTERVAL_MS = 50L
