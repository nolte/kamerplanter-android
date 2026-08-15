package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.connection.Connection
import io.github.nolte.kamerplanter.core.connection.ConnectionStore
import io.github.nolte.kamerplanter.core.connection.CredentialStore
import io.github.nolte.kamerplanter.core.network.generated.apis.PestDetectionApi
import io.github.nolte.kamerplanter.core.network.generated.apis.PrivacyApi
import io.github.nolte.kamerplanter.core.network.generated.models.ConsentGrantRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ConsentResponse
import io.github.nolte.kamerplanter.core.network.generated.models.FindingSchema
import io.github.nolte.kamerplanter.core.network.generated.models.PestDetectionResponse
import io.github.nolte.kamerplanter.core.network.generated.models.PestDetectionStatusResponse
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs pest detection against the connected instance.
 *
 * Two things shape this class. The feature is **off by default** upstream and depends on an
 * adapter an operator has to configure, so [readiness] runs before anything is captured —
 * a shutter that uploads into a 404 is worse than no shutter. And the cloud adapter sends the
 * image to a third party, which the user has to have agreed to *before* the bytes leave the
 * device; the adapter status says only that a consent is required, never whether it was
 * granted, so the consent list is what answers that.
 */
@Singleton
class NetworkPestDetectionClient @Inject constructor(
    private val apis: InstanceApiFactory,
    private val connections: ConnectionStore,
    private val credentials: CredentialStore,
    private val json: Json,
) : PestDetectionClient {

    override suspend fun readiness(): DetectionReadiness = runCatchingCancellable {
        val target = target() ?: return DetectionReadiness.NotConnected
        val status = target.retrofit.create(PestDetectionApi::class.java)
            .pestDetectionStatusApiV1TTenantSlugPestsStatusGet(tenantSlug = target.tenant)
            .bodyOrThrow()

        val consent = status.consentPurpose()
            ?: return@runCatchingCancellable if (status.usable()) {
                DetectionReadiness.Ready
            } else {
                DetectionReadiness.NotOffered
            }

        if (!status.usable()) return@runCatchingCancellable DetectionReadiness.NotOffered

        val recorded = target.retrofit.consent(consent)
        if (recorded?.granted == true) {
            DetectionReadiness.Ready
        } else {
            DetectionReadiness.ConsentRequired(
                purpose = consent,
                terms = recorded?.let {
                    ConsentTerms(
                        label = it.label,
                        description = it.description,
                        legalBasis = it.legalBasis,
                    )
                },
            )
        }
    }.getOrElse { failure -> failure.asReadinessFailure() }

    override suspend fun grantConsent(purpose: String): ConsentOutcome = runCatchingCancellable {
        val target = target() ?: return ConsentOutcome.Failed("the app is not connected to an instance")
        target.retrofit.create(PrivacyApi::class.java)
            .grantConsentApiV1PrivacyConsentsPost(ConsentGrantRequest(purpose = purpose))
            .bodyOrThrow()
        ConsentOutcome.Granted
    }.getOrElse { failure ->
        when {
            failure is HttpFailure && failure.status == UNAUTHORIZED -> ConsentOutcome.Unauthorized
            // Same distinction readiness() makes: a credential that authenticates but may not
            // record a consent gains nothing from re-pairing, and "connect again" would send
            // its owner round a loop back to this 403.
            failure is HttpFailure && failure.status == FORBIDDEN -> ConsentOutcome.NotPermitted
            failure is HttpFailure -> ConsentOutcome.Failed("the instance answered HTTP ${failure.status}")
            else -> ConsentOutcome.Failed(failure::class.simpleName.orEmpty())
        }
    }

    override suspend fun detect(
        jpeg: ByteArray,
        plantKey: String?,
        language: String,
    ): DetectionOutcome {
        // Checked before the request rather than left to the instance's 422. The upload is the
        // expensive half of this call — a 4K microscope frame over a phone uplink — and there
        // is no point spending it to be told a size the app could measure itself.
        if (jpeg.size > MAX_IMAGE_BYTES) return DetectionOutcome.Refused(RefusedReason.TOO_LARGE)
        if (jpeg.isEmpty()) return DetectionOutcome.Refused(RefusedReason.NOT_PROCESSABLE)

        return runCatchingCancellable {
            val target = target() ?: return DetectionOutcome.Unavailable("the app is not connected to an instance")
            val api = target.retrofit.create(PestDetectionApi::class.java)
            val image = MultipartBody.Part.createFormData(
                IMAGE_PART,
                // The backend never stores the image, so this name is only what the multipart
                // envelope needs; it is not a filename anything on the instance keeps.
                "capture.jpg",
                jpeg.toRequestBody(JPEG_MEDIA_TYPE.toMediaType()),
            )

            val response = if (plantKey == null) {
                api.detectPestsGlobalApiV1TTenantSlugPestsDetectPost(
                    tenantSlug = target.tenant,
                    image = image,
                    language = language,
                )
            } else {
                api.detectPestsApiV1TTenantSlugPestsPlantsPlantKeyDetectPost(
                    plantKey = plantKey,
                    tenantSlug = target.tenant,
                    image = image,
                    language = language,
                )
            }
            DetectionOutcome.Completed(response.bodyOrThrow().asDetection())
        }.getOrElse { failure -> failure.asDetectionFailure() }
    }

    /** What a call needs: the instance to talk to and the tenant its routes are scoped to. */
    private class Target(val retrofit: Retrofit, val tenant: String)

    private suspend fun target(): Target? {
        val connection = connections.connection.first() ?: return null
        // Light mode addresses no tenant, and every route here is tenant-scoped. Treated as
        // "not connected" rather than as its own state: from this feature's side the two are
        // the same thing — there is nothing to ask and nothing the user can do about it here.
        val tenant = connection.tenantSlug ?: return null
        val credential = credentials.load()
        return Target(apis.create(connection.baseUrl) { credential }, tenant)
    }

    /**
     * What the instance records for [purpose] — whether it is granted, and how it describes it.
     *
     * `GET /privacy/consents` lists every known purpose annotated with its state, so an
     * ungranted one is in there with its wording; that is what lets the app show the
     * instance's own text instead of inventing consent language.
     *
     * `null` where the instance did not answer, and that is deliberately *not* read as
     * "granted": the whole point of asking is that an image must not leave the device on an
     * assumption. An unreachable consent list routes the user through the prompt, which grants
     * it again — idempotent upstream — rather than uploading on a guess.
     */
    private suspend fun Retrofit.consent(purpose: String): ConsentResponse? = runCatchingCancellable {
        create(PrivacyApi::class.java)
            .listConsentsApiV1PrivacyConsentsGet()
            .bodyOrThrow()
            .firstOrNull { it.purpose == purpose }
    }.getOrElse { null }

    private companion object {
        const val IMAGE_PART = "image"
        const val JPEG_MEDIA_TYPE = "image/jpeg"
        const val NOT_FOUND = 404

        /**
         * The instance's own limit is configurable (`pest_detection_max_image_size_mb`,
         * default 8). This mirrors the default so the common case fails locally and instantly,
         * before an upload is spent. An instance configured *lower* answers 422 — which the
         * backend also uses for an undecodable image, so that lands on `NOT_PROCESSABLE` and
         * its message names both possibilities rather than guessing.
         */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        /** The backend's `error_code` for a missing consent, on an otherwise ordinary 403. */
        const val CONSENT_REQUIRED_CODE = "CONSENT_REQUIRED"

        /** The feature is usable only when the operator enabled it *and* an adapter answers. */
        fun PestDetectionStatusResponse.usable(): Boolean =
            available && featureEnabled && activeAdapterStatus()?.configured == true

        /**
         * The consent the active adapter needs, or `null` when it needs none.
         *
         * Read off the *active* adapter, not off any adapter in the map: a local adapter
         * alongside a configured cloud one must not inherit the cloud one's consent
         * requirement, and asking for a consent the upload will never need trains the user to
         * click through consent prompts.
         */
        fun PestDetectionStatusResponse.consentPurpose(): String? =
            activeAdapterStatus()?.requiresConsent?.takeIf { it.isNotBlank() }

        fun PestDetectionStatusResponse.activeAdapterStatus() =
            adapters?.get(activeAdapter ?: primaryAdapter)

        fun PestDetectionResponse.asDetection() = Detection(
            key = key,
            isConfident = isConfident,
            findings = findings.orEmpty().map { it.asFinding() },
            disclaimer = disclaimer,
            suggestedNextStep = suggestedNextStep,
            tilesProcessed = tilesProcessed,
        )

        fun FindingSchema.asFinding() = Finding(
            label = label,
            commonName = commonName,
            category = category,
            confidence = confidence.toDouble(),
            mode = mode,
            boundingBox = boundingBox?.let {
                BoundingBox(
                    x = it.x.toDouble(),
                    y = it.y.toDouble(),
                    width = it.width.toDouble(),
                    height = it.height.toDouble(),
                )
            },
            // `matched_beneficial_key`, not `category == "beneficial"`: the key is what the
            // backend resolved against its own beneficial master data, while the category is
            // the recognizer's own vocabulary and this build cannot know every value of it.
            isBeneficial = matchedBeneficialKey != null,
        )

        fun Throwable.asReadinessFailure(): DetectionReadiness = when {
            this !is HttpFailure -> DetectionReadiness.Unavailable(this::class.simpleName.orEmpty())
            status == UNAUTHORIZED -> DetectionReadiness.Unauthorized
            // Not folded in with 401, the way the plant list folds them: an API key that
            // authenticates but whose scope excludes pest detection answers 403 here, and
            // sending its owner to re-pair a connection that works is advice they cannot act
            // on. `detect()` already tells the two apart; this method used to contradict it.
            status == FORBIDDEN -> DetectionReadiness.NotPermitted
            // A 404 means this instance predates the endpoint. From the user's side that is the
            // same as an operator not offering it, and it is what an older self-hosted instance
            // answers — the app supports whatever the user runs.
            status == NOT_FOUND -> DetectionReadiness.NotOffered
            else -> DetectionReadiness.Unavailable("the instance answered HTTP $status")
        }

        fun Throwable.asDetectionFailure(): DetectionOutcome = when {
            this !is HttpFailure -> DetectionOutcome.Unavailable(this::class.simpleName.orEmpty())
            status == UNSUPPORTED_MEDIA_TYPE -> DetectionOutcome.Refused(RefusedReason.UNSUPPORTED_TYPE)
            // Not the instance's own limit but whatever sits in front of it: nginx defaults
            // to a 1 MB body, which is under every microscope capture, and the local 8 MB
            // guard never fires for it. Reported as "unreachable" this read as "your server is
            // down" when the answer was "too large" — and it is the one failure here whose fix
            // is in the operator's hands.
            status == PAYLOAD_TOO_LARGE -> DetectionOutcome.Refused(RefusedReason.REFUSED_BY_PROXY)
            status == UNPROCESSABLE -> DetectionOutcome.Refused(RefusedReason.NOT_PROCESSABLE)
            status == FORBIDDEN && errorCode == CONSENT_REQUIRED_CODE ->
                DetectionOutcome.Refused(RefusedReason.CONSENT_MISSING)
            status == FORBIDDEN -> DetectionOutcome.Refused(RefusedReason.NOT_PERMITTED)
            status == UNAUTHORIZED -> DetectionOutcome.Unauthorized
            else -> DetectionOutcome.Unavailable("the instance answered HTTP $status")
        }

        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val PAYLOAD_TOO_LARGE = 413
        const val UNSUPPORTED_MEDIA_TYPE = 415
        const val UNPROCESSABLE = 422

        @Suppress("TooGenericExceptionCaught")
        inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
    }

    /**
     * A failed call, carrying the instance's `error_code` where it sent one.
     *
     * The code matters for exactly one distinction, and it is the one the user feels: a 403 is
     * either "you may not do this" or "you have not agreed to cloud processing yet", and only
     * the second has a way out that the app can offer.
     */
    private class HttpFailure(val status: Int, val errorCode: String?) : Exception("HTTP $status")

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpFailure(code(), errorCode())
        return body() ?: throw HttpFailure(code(), null)
    }

    /**
     * The `error_code` out of an error envelope, or `null` when the body is not one.
     *
     * Parsed leniently on purpose: a reverse proxy in front of the instance can answer a 403
     * with HTML, and a detection must fail as a detection rather than as a parse error.
     */
    private fun Response<*>.errorCode(): String? = runCatchingCancellable {
        val body = errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return null
        ((json.parseToJsonElement(body) as? JsonObject)?.get("error_code") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    }.getOrNull()
}

/** The tenant a connection addresses, where it has one; light mode has none. */
private val Connection.tenantSlug: String?
    get() = when (this) {
        is Connection.QrPairing -> tenantSlug
        is Connection.ApiKey -> tenantSlug
        is Connection.LightMode -> null
    }
