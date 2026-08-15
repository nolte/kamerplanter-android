package io.github.nolte.kamerplanter.core.network

/**
 * A single recognized pest, beneficial or damage pattern.
 *
 * App-owned so no generated DTO crosses out of `:core:network` (ADR 0001, R-GEN-5).
 *
 * [category] and [mode] stay backend strings rather than enums on purpose: an instance one
 * release ahead will name a category this build has never heard of, and a finding that renders
 * under a generic heading beats a detection that fails to parse (R-COMPAT-3).
 */
data class Finding(
    /** The recognizer's own label — stable across languages, and what feedback posts back. */
    val label: String,
    /** Localized name for display; the backend translates it per the request's language. */
    val commonName: String,
    /** `pest`, `beneficial`, `damage` … — not exhaustive, and not this app's to enumerate. */
    val category: String,
    /** 0..1. Shown as a percentage, never as a verdict. */
    val confidence: Double,
    /** `direct` for a recognized organism, `symptom` for a damage pattern. */
    val mode: String,
    /** Where in the image, when the recognizer localized it; symptom findings carry none. */
    val boundingBox: BoundingBox?,
    /**
     * The finding matched a known beneficial rather than a pest.
     *
     * Derived from `matched_beneficial_key` rather than from [category], because acting on
     * this is a treatment decision: a beneficial the user sprays because the app called it a
     * pest is the one failure this feature must not produce.
     */
    val isBeneficial: Boolean,
)

/**
 * Where a finding sits in the image, normalized to 0..1 of the **full** image.
 *
 * Normalized, not pixels: the backend tiles the image and merges per-tile boxes back into
 * full-image coordinates before answering, and it re-encodes (and may downscale) the upload
 * first — so pixel coordinates would refer to an image the app never had. Scaling is uniform,
 * so the fractions apply unchanged to the frame the app captured.
 */
data class BoundingBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * One completed detection, in the shape the result screen needs.
 *
 * [isConfident] is a first-class result rather than an error: the recognizer abstains when
 * nothing clears its threshold, and the screen says "no reliable detection" instead of
 * showing an empty findings list that reads like a clean bill of health.
 */
data class Detection(
    /** Addresses the detection for feedback; the backend may answer without one. */
    val key: String?,
    val isConfident: Boolean,
    val findings: List<Finding>,
    /**
     * The instance's own wording, displayed verbatim.
     *
     * Never paraphrased and never omitted: it is what keeps a recognizer's guess from reading
     * like a diagnosis, and rewording it client-side would put this app's words on the
     * instance's legal position.
     */
    val disclaimer: String,
    val suggestedNextStep: String,
    /** How many tiles the backend ran inference on; shown as detail, not as a headline. */
    val tilesProcessed: Int,
)

/**
 * Whether the connected instance can run a detection at all — asked before the camera opens.
 *
 * The feature ships disabled (`pest_detection_enabled` defaults to false upstream) and depends
 * on an adapter an operator has to configure, so "unavailable" is the ordinary case rather
 * than the edge one. Offering a shutter that answers 4xx is the thing this type exists to
 * prevent.
 */
sealed interface DetectionReadiness {

    /** The feature is on, an adapter is configured, and any required consent is in place. */
    data object Ready : DetectionReadiness

    /**
     * The active adapter sends the image off the instance and the user has not agreed to that.
     *
     * [purpose] is the backend's consent key (`pest_detection_cloud` for the cloud adapter),
     * passed straight back to [PestDetectionClient.grantConsent]. [terms] is the instance's own
     * wording for it, and is `null` only where the instance did not supply any — see
     * [ConsentTerms].
     */
    data class ConsentRequired(val purpose: String, val terms: ConsentTerms? = null) :
        DetectionReadiness

    /** The operator has not enabled the feature, or configured no adapter for it. */
    data object NotOffered : DetectionReadiness

    /** No instance is connected, so there is nothing to ask. */
    data object NotConnected : DetectionReadiness

    /** The stored credential was refused — the connection needs re-establishing. */
    data object Unauthorized : DetectionReadiness

    /** Anything else: unreachable instance, server error, malformed answer. */
    data class Unavailable(val reason: String) : DetectionReadiness
}

/** The outcome of an upload, in terms the result screen can act on. */
sealed interface DetectionOutcome {

    data class Completed(val detection: Detection) : DetectionOutcome

    /** The image itself was refused; [reason] says which of the contract's limits it missed. */
    data class Refused(val reason: RefusedReason) : DetectionOutcome

    /** The stored credential was refused. */
    data object Unauthorized : DetectionOutcome

    /** Anything else — worth a retry with the same frame. */
    data class Unavailable(val reason: String) : DetectionOutcome
}

/** Why an instance would not look at an image. */
enum class RefusedReason {

    /** Not a JPEG or PNG (HTTP 415). The two capture paths should make this unreachable. */
    UNSUPPORTED_TYPE,

    /** Past the instance's upload limit — caught locally where possible, else HTTP 422. */
    TOO_LARGE,

    /** The image was not decodable, or the instance rejected it for a reason it did not name. */
    NOT_PROCESSABLE,

    /**
     * The cloud adapter's consent is missing (HTTP 403, `CONSENT_REQUIRED`).
     *
     * Reachable despite the pre-flight check: the consent can be revoked in the web UI between
     * asking and uploading, and the image has then already left the device — which is why the
     * check happens first and this stays a fallback rather than the primary gate.
     */
    CONSENT_MISSING,

    /** The credential authenticated but may not run detections in this tenant (HTTP 403). */
    NOT_PERMITTED,
}

/**
 * What the instance says a consent means, in its own words.
 *
 * Carried across the module boundary rather than re-worded in the UI because the user is
 * agreeing to *this* — an Art. 6(1)(a) GDPR consent — and app-authored wording would have them
 * agreeing to text this app invented about processing it does not perform. The screen renders
 * these verbatim; nothing here is a resource id.
 */
data class ConsentTerms(
    /** Short name of the purpose, used as the heading. */
    val label: String,
    /** What the user is agreeing to. */
    val description: String,
    /** The lawful basis the instance records for it. */
    val legalBasis: String,
)

/** The outcome of granting a consent. */
sealed interface ConsentOutcome {
    data object Granted : ConsentOutcome
    data object Unauthorized : ConsentOutcome
    data class Failed(val reason: String) : ConsentOutcome
}

/**
 * Runs pest detection against the connected instance.
 *
 * A seam rather than a concrete class so the flow can be driven from tests without HTTP, and
 * so the feature module never sees a networking type.
 */
interface PestDetectionClient {

    /** Whether a detection can be run right now, and what stands in the way if not. */
    suspend fun readiness(): DetectionReadiness

    /**
     * Records the user's consent for [purpose], as reported by
     * [DetectionReadiness.ConsentRequired].
     */
    suspend fun grantConsent(purpose: String): ConsentOutcome

    /**
     * Uploads [jpeg] and returns what the instance recognized in it.
     *
     * @param plantKey binds the detection to a plant, which is what enables its history and
     *   the IPM inspection. `null` runs the plant-agnostic endpoint.
     * @param language the language the labels and the disclaimer come back in.
     */
    suspend fun detect(jpeg: ByteArray, plantKey: String?, language: String): DetectionOutcome
}
