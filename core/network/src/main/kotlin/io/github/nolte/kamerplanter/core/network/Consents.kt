package io.github.nolte.kamerplanter.core.network

import io.github.nolte.kamerplanter.core.network.generated.apis.PrivacyApi
import io.github.nolte.kamerplanter.core.network.generated.models.ConsentGrantRequest
import io.github.nolte.kamerplanter.core.network.generated.models.ConsentResponse
import retrofit2.Retrofit

/**
 * The consent a purpose is on record with, in the instance's own words.
 *
 * `GET /privacy/consents` lists every known purpose annotated with its state, so an ungranted
 * one is in there with its wording; that is what lets the app show the instance's own text
 * instead of inventing consent language — for pest detection and plant identification alike.
 *
 * `null` where the instance did not answer, and that is deliberately *not* read as "granted":
 * the whole point of asking is that an image must not leave the device on an assumption. An
 * unreachable consent list routes the user through the prompt, which grants it again —
 * idempotent upstream — rather than uploading on a guess.
 */
internal suspend fun Retrofit.consentFor(purpose: String): ConsentResponse? = runCatchingCancellable {
    create(PrivacyApi::class.java)
        .listConsentsApiV1PrivacyConsentsGet()
        .takeIf { it.isSuccessful }
        ?.body()
        ?.firstOrNull { it.purpose == purpose }
}.getOrElse { null }

/** Records [purpose] as granted. The caller maps the response; a refusal is its own answer. */
internal suspend fun Retrofit.grantConsent(purpose: String) =
    create(PrivacyApi::class.java).grantConsentApiV1PrivacyConsentsPost(ConsentGrantRequest(purpose = purpose))

/** The wording as the instance sent it, carried across the module boundary untouched. */
internal fun ConsentResponse.terms(): ConsentTerms =
    ConsentTerms(label = label, description = description, legalBasis = legalBasis)
