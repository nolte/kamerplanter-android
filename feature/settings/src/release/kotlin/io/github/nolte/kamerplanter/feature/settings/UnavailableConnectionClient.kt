package io.github.nolte.kamerplanter.feature.settings

import android.util.Log
import javax.inject.Inject

/**
 * **Placeholder — WP-6 / WP-9 replace this file, they do not extend it.**
 *
 * The release variant may not bind [FakeConnectionClient] (R34), and the real client does
 * not exist yet: it is generated into `core/network/` from the backend's `openapi.json`,
 * which is blocked on the upstream `v0.2.0` release (R1, R2). Until that lands the release
 * variant has no [ConnectionClient] to bind, and a graph with no binding at all would not
 * compile.
 *
 * So this stands in, and it stands in *honestly*: it reaches no network, claims no success,
 * and refuses every request with a diagnostic that names itself. A release build therefore
 * cannot connect — which is the truth about a release build today — instead of appearing to
 * connect against a canned instance.
 *
 * It refuses rather than throws on purpose: [ConnectionResult.Failure] is a state the
 * connection machine already handles (R14), so the app stays usable and leaves an existing
 * connection untouched, where a crash on a user action would not.
 *
 * When the generated client lands, delete this class together with
 * `di/ReleaseConnectionClientModule` and bind the `core/network/`-backed client in their
 * place. Nothing else in the module changes: the seam is [ConnectionClient].
 */
class UnavailableConnectionClient @Inject constructor() : ConnectionClient {

    override suspend fun connect(request: ConnectionRequest): ConnectionResult {
        // Loud in logcat, because a release build silently refusing every connection would
        // otherwise look like a backend problem. The request masks its own secret (R19).
        Log.e(TAG, "No real ConnectionClient in this build; refusing $request")
        return ConnectionResult.Failure(REASON)
    }

    private companion object {
        const val TAG = "UnavailableConnection"

        /** A diagnostic string, not a user-facing message — see [ConnectionResult.Failure]. */
        const val REASON =
            "no ConnectionClient implementation in the release variant: the generated " +
                "core/network/ client is not built yet (WP-6/WP-9)"
    }
}
