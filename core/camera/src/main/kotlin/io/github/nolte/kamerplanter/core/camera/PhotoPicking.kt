package io.github.nolte.kamerplanter.core.camera

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Picks photos, from the library or from the camera, as JPEG bytes ready to upload.
 *
 * Both sources hand back a `content://` URI and nothing else, so both end here: read, re-encode
 * to a size an upload can carry, and hand over bytes. Screens deal in bytes because that is
 * what the diary endpoint takes, and because a URI's permission is tied to the Activity result
 * that produced it — passing one into a ViewModel is how a photo becomes unreadable the moment
 * the process is recreated.
 */
class PhotoPicking internal constructor(
    /** Opens the system photo picker. No permission needed — the picker grants per item. */
    val pickFromLibrary: () -> Unit,
    /** Opens the camera. Requires the CAMERA grant; see [rememberCameraPermission]. */
    val takePhoto: () -> Unit,
)

/**
 * Photo picking, wired to [onPhotos].
 *
 * [maxBytes] bounds each photo after re-encoding. The default is what the pest-detection
 * upload already uses: large enough that a leaf's underside is legible, small enough that five
 * of them do not time out on a phone connection.
 */
@Composable
fun rememberPhotoPicking(
    maxBytes: Int = MAX_PHOTO_BYTES,
    maxCount: Int = MAX_PHOTOS,
    onPhotos: suspend (List<ByteArray>) -> Unit,
): PhotoPicking {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val library = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxCount),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            onPhotos(uris.mapNotNull { context.readUploadable(it, maxBytes) })
        }
    }

    // One fixed name, not a fresh temp file. `createTempFile` per composition left a new empty
    // file in the cache on every visit and deleted none of them — and worse, a recomposition
    // after an Activity restart named a *different* file from the one the camera app had been
    // told to write, so the photo arrived nowhere. The name is derived, so the destination
    // survives a restart mid-capture; the file is deleted once its bytes have been read.
    val target = remember(context) { context.captureFile() }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (!saved) return@rememberLauncherForActivityResult
        scope.launch {
            val photo = context.readUploadable(target.toUri(), maxBytes)
            withContext(Dispatchers.IO) { target.delete() }
            onPhotos(listOfNotNull(photo))
        }
    }

    return remember(library, camera, target, context) {
        PhotoPicking(
            pickFromLibrary = {
                library.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            takePhoto = {
                // Created here, not in the composition: a dialogue whose user never reaches
                // for the camera should not touch the filesystem at all.
                target.parentFile?.mkdirs()
                camera.launch(context.uriFor(target))
            },
        )
    }
}

/** Reads a picked image and re-encodes it to something an upload can carry. */
private suspend fun Context.readUploadable(uri: Uri, maxBytes: Int): ByteArray? =
    withContext(Dispatchers.IO) {
        // Decoding and re-encoding a multi-megapixel photo is tens of milliseconds of work per
        // image, and five of them on the main thread is a visible freeze on the screen the
        // user just tapped.
        val raw = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        JpegDownscale.toUploadable(raw, maxBytes, raw.exifRotationDegrees())
    }

/**
 * How far the photo has to turn to sit upright.
 *
 * A phone photographs through a sensor that is mounted sideways and records the correction as
 * an EXIF tag rather than rotating the pixels. Re-encoding drops the tag, so a photo passed
 * through here without its rotation reached the instance lying on its side — which
 * `PhoneCameraShutter` already knew, and took from CameraX's `rotationDegrees`. A picked image
 * has no CameraX to ask.
 *
 * `android.media.ExifInterface`, not the AndroidX one: reading a stream has worked since API
 * 24 and this app starts at 26, so the dependency would buy nothing. Mirrored orientations are
 * left alone — they come from front cameras and scanners, not from a leaf under a lens.
 */
private fun ByteArray.exifRotationDegrees(): Int = runCatching {
    when (
        ExifInterface(ByteArrayInputStream(this))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> QUARTER_TURN
        ExifInterface.ORIENTATION_ROTATE_180 -> HALF_TURN
        ExifInterface.ORIENTATION_ROTATE_270 -> THREE_QUARTER_TURN
        else -> 0
    }
}.getOrDefault(0)

/** Where the camera app writes, inside the path the FileProvider publishes. */
private fun Context.captureFile(): File = File(File(cacheDir, "captures"), "capture.jpg")

private fun Context.uriFor(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.camera.fileprovider", file)

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270

/** The endpoint's own ceiling: a diary entry references at most five photos. */
const val MAX_PHOTOS: Int = 5

private const val MAX_PHOTO_BYTES = 4 * 1024 * 1024
