package io.github.nolte.kamerplanter.core.camera

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // Created once per composition rather than per capture: the launcher has to be told the
    // destination before it starts, and a file named at launch time would be a new file on
    // every recomposition — including the one that follows the result arriving.
    val target = remember { context.newCaptureFile() }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (!saved) return@rememberLauncherForActivityResult
        scope.launch {
            onPhotos(listOfNotNull(context.readUploadable(target, maxBytes)))
        }
    }

    return remember(library, camera, target) {
        PhotoPicking(
            pickFromLibrary = {
                library.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            takePhoto = { camera.launch(target) },
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
        JpegDownscale.toUploadable(raw, maxBytes)
    }

/** A cache file inside the path the FileProvider publishes, and its content URI. */
private fun Context.newCaptureFile(): Uri {
    val dir = File(cacheDir, "captures").apply { mkdirs() }
    val file = File.createTempFile("capture", ".jpg", dir)
    return FileProvider.getUriForFile(this, "$packageName.camera.fileprovider", file)
}

/** The endpoint's own ceiling: a diary entry references at most five photos. */
const val MAX_PHOTOS: Int = 5

private const val MAX_PHOTO_BYTES = 4 * 1024 * 1024
