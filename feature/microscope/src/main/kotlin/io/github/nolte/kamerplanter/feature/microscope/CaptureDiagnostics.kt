package io.github.nolte.kamerplanter.feature.microscope

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drops the capture into the app's external files dir so a hardware session leaves a
 * reviewable artifact instead of a verbal report — the evidence rule the microscope spec
 * asks for. Debuggable builds only; a release build keeps the frame in memory, where the
 * upload pipeline is its sole consumer.
 *
 * The flag is read from the running application rather than a library `BuildConfig`,
 * which in a library module reflects how the *library* was built, not the app.
 */
internal suspend fun CapturedFrame.writeForInspection(context: Context) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
        return
    }
    // On a worker, not the caller's main thread — a diagnostic must not cost UI frames.
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val name = "capture-${width}x$height-${SystemClock.uptimeMillis()}.jpg"
            File(dir, name).apply { writeBytes(jpeg) }.also {
                Log.i(DIAGNOSTICS_TAG, "capture also written to ${it.absolutePath}")
            }
        }.onFailure { Log.w(DIAGNOSTICS_TAG, "could not persist the capture for inspection", it) }
    }
}

private const val DIAGNOSTICS_TAG = "MicroscopeCamera"
