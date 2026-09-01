package io.github.nolte.kamerplanter.core.network

/**
 * The app's compatibility stance towards a backend, judged from the `apiVersion` the
 * instance reports on `GET /api/health` (F-10).
 *
 * The axis matters: `core/network/openapi/provenance.json` records three version strings —
 * the release tag, the backend `apiVersion`, and the URL major `/api/v1` — and none is
 * derivable from another (R-VER-1). The floor here gates against `apiVersion`, the number
 * the health route actually answers with; reading it off the tag would gate against the
 * wrong axis entirely.
 *
 * Two distinct verdicts short of compatible, because the criteria ask for two different
 * behaviours:
 *
 * - **No shared API major** is a refusal. The app addresses everything under `/api/v1`; an
 *   instance whose `apiVersion` lives in another major does not promise that surface, and
 *   connecting anyway would fail on the first real call with a far less honest error.
 * - **Below the floor within the shared major** is a warning, not a refusal: the user still
 *   reaches their plants, the connection carries the fact, and features may fall back where
 *   an endpoint the floor promised is missing. Today the floor equals the lowest
 *   `apiVersion` ever released, so this verdict cannot fire against a real instance — the
 *   mechanism exists for the first floor raise, which is when it must already be in place.
 *
 * An instance that reports no version — or one this parser cannot read — is judged
 * [Verdict.Compatible]: there is nothing to compare, and refusing every instance older than
 * the field would gate hardest against exactly the instances the floor is meant to warn
 * about gently.
 */
internal object ApiCompatibility {

    /** The one URL major this app speaks; every generated route lives under `/api/v1`. */
    const val SUPPORTED_API_MAJOR = 1

    /**
     * The lowest backend `apiVersion` this app is built against. Keep in step with
     * `core/network/openapi/provenance.json` when the vendored schema starts relying on
     * behaviour older instances do not have.
     */
    const val MINIMUM_API_VERSION = "1.0.0"

    sealed interface Verdict {

        /** Same major, at or above the floor — or no version to judge. */
        data object Compatible : Verdict

        /** Same major, but older than [MINIMUM_API_VERSION]: connect, warn, reduce (F-10). */
        data object BelowFloor : Verdict

        /** No API major in common: the only verdict that refuses the connection. */
        data class NoSharedMajor(val reported: String) : Verdict
    }

    fun judge(
        reportedVersion: String?,
        supportedMajor: Int = SUPPORTED_API_MAJOR,
        minimumVersion: String = MINIMUM_API_VERSION,
    ): Verdict {
        val reported = reportedVersion?.let(::parse) ?: return Verdict.Compatible
        if (reported.major != supportedMajor) return Verdict.NoSharedMajor(reportedVersion)
        val floor = parse(minimumVersion) ?: return Verdict.Compatible
        return if (reported < floor) Verdict.BelowFloor else Verdict.Compatible
    }

    /**
     * The numeric `major.minor.patch` prefix of a version string, tolerant of a suffix
     * (`1.2.0-rc1` reads as `1.2.0`) and of missing parts (`1.2` reads as `1.2.0`).
     */
    private fun parse(version: String): SemVer? {
        val parts = version.trim()
            .takeWhile { it.isDigit() || it == '.' }
            .split('.')
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        return SemVer(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
    }

    private data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int =
            compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
    }
}
