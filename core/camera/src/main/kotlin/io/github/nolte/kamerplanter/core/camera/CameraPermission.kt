package io.github.nolte.kamerplanter.core.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * The CAMERA grant, and the one honest way out when it is refused for good.
 *
 * Shared because three screens need it for two different reasons — QR pairing and pest
 * detection use the device camera, and the microscope needs it because AOSP refuses to show
 * the USB permission dialogue for a video-class device to an app that does not hold it. Each
 * had grown its own copy, and only one of them handled a permanent denial: the other two
 * offered a button that visibly did nothing.
 */
class CameraPermission internal constructor(
    /** Whether the app may use the camera right now. */
    val isGranted: Boolean,
    /**
     * Whether asking again would show a dialogue.
     *
     * `false` after "Don't ask again": the system silently denies without prompting, so a
     * request button there is a control that does nothing. [openSettings] is the way out.
     */
    val canAsk: Boolean,
    /** Shows the system dialogue. A no-op once [canAsk] is `false`. */
    val request: () -> Unit,
    /** Opens this app's system settings page, the only route back after a permanent denial. */
    val openSettings: () -> Unit,
)

/**
 * The CAMERA grant, kept current.
 *
 * Re-read on resume rather than sampled once: granting from system settings does not restart
 * the process, so a value read at first composition would keep showing the rationale to
 * someone who has already said yes.
 *
 * [requestOnFirstShow] asks immediately where a screen is useless without the camera — the
 * viewfinders — and is left off where the screen has something to say first.
 */
@Composable
fun rememberCameraPermission(requestOnFirstShow: Boolean = true): CameraPermission {
    val context = LocalContext.current
    var isGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    // Saved, both of them, because a configuration change recreates the Activity: a plain
    // `remember` resets on every rotation, so the screen would ask again for a grant the user
    // had just refused, and would briefly offer "grant" to someone who has refused for good.
    var canAsk by rememberSaveable { mutableStateOf(true) }
    var asked by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isGranted = context.hasCameraPermission()
        if (isGranted) canAsk = true
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        isGranted = granted
        // After a refusal the system tells us whether another dialogue is still possible. It
        // says "false" both for a permanent denial and — on some versions — for the very first
        // refusal, which is why this is only read once a request has actually happened.
        canAsk = granted || context.shouldShowCameraRationale()
    }

    val request = { launcher.launch(Manifest.permission.CAMERA) }
    // In an effect, not in the composition: `rememberLauncherForActivityResult` registers its
    // launcher in a DisposableEffect that runs *after* composition, so launching from the
    // composition itself throws "Launcher has not been initialized" — an immediate crash on
    // every first visit without the grant. The flag keeps a recomposition from asking twice.
    // Keyed on the flag, not on Unit: a caller that starts out not wanting the request and
    // later does would otherwise be ignored for the life of the composition.
    LaunchedEffect(requestOnFirstShow) {
        if (requestOnFirstShow && !isGranted && !asked) {
            asked = true
            request()
        }
    }

    return CameraPermission(
        isGranted = isGranted,
        canAsk = canAsk,
        request = request,
        openSettings = { context.openAppSettings() },
    )
}

fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Opens this app's system settings page — the only route back to the camera permission after a
 * permanent denial ("Don't ask again"), where re-requesting no longer shows a dialogue and
 * would otherwise leave the screen in a dead end.
 */
fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private fun Context.shouldShowCameraRationale(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

/**
 * The Activity behind a composition's context.
 *
 * `LocalContext` is a `ContextThemeWrapper` in a Compose hierarchy, not the Activity itself, so
 * the rationale query — which only an Activity can answer — needs unwrapping to reach it.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
