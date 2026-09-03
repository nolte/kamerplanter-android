package io.github.nolte.kamerplanter.feature.plants.capture

import androidx.annotation.StringRes
import io.github.nolte.kamerplanter.core.camera.CapturedImage
import io.github.nolte.kamerplanter.core.network.ConsentTerms
import io.github.nolte.kamerplanter.core.network.IdentificationReadiness
import io.github.nolte.kamerplanter.core.network.Location
import io.github.nolte.kamerplanter.core.network.PlantOrgan
import io.github.nolte.kamerplanter.core.network.Site
import io.github.nolte.kamerplanter.core.network.SpeciesDraft
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import io.github.nolte.kamerplanter.core.network.Suggestion
import java.time.LocalDate

/**
 * What the add-a-plant screen can be showing (R-8).
 *
 * The form is one state with many fields rather than a state per step: both routes end in it,
 * and the user moves freely between its fields. Everything before it — loading the catalogue,
 * the places and the identifiers already taken — and everything after it — the plant existing —
 * is its own state with its own action (R33). The identification route is a step laid over
 * the form ([Form.step]) rather than a state beside it, so what the user had already typed
 * survives a detour through the recogniser.
 */
sealed interface PlantCaptureState {

    /** Loading what the form needs before it can be filled in. */
    data object Loading : PlantCaptureState

    data object NotConnected : PlantCaptureState

    /** The stored credential was refused; the user has to reconnect in Settings. */
    data object Unauthorized : PlantCaptureState

    /** The form could not be prepared; [canRetry] is false for a refusal asking again cannot fix. */
    data class Failed(@StringRes val message: Int, val canRetry: Boolean = true) : PlantCaptureState

    /**
     * The capture form (R17–R24, R28).
     *
     * [inputs] is what the user typed or chose; the rest is what the instance offered. The
     * identifier proposal (R19) is derived into [inputs] whenever the species changes and the
     * user has not taken the field over.
     */
    data class Form(
        val inputs: FormInputs,
        val catalogue: List<SpeciesEntry>,
        val sites: List<Site>,
        /** The chosen site's locations, `null` while no site is chosen or they are loading. */
        val locations: List<Location>?,
        /** Identifiers already in use, for the best-effort collision check (R21). */
        val takenIds: Set<String>,
        val photo: CapturedPhoto? = null,
        /** On whenever a photo exists, unless the user turned it off (R28). */
        val keepPhoto: Boolean = true,
        val submitting: Boolean = false,
        /** A field the last submission was refused on (R22); empty until a submission was tried. */
        val errors: Set<FormField> = emptySet(),
        /** A sentence about the last thing that happened — a refused submission, a place that would not load. */
        @StringRes val notice: Int? = null,
        /** The instance's own words for a refusal, shown beside [notice] where it gave any. */
        val noticeDetail: String? = null,
        /**
         * Whether the identification route is on offer (R1–R3): `null` while the instance is
         * still being asked. Only [IdentificationReadiness.Ready] and
         * [IdentificationReadiness.ConsentRequired] put the route on screen; every other answer
         * leaves the manual route alone, which is always there.
         */
        val identification: IdentificationReadiness? = null,
        /** Where the identification route is, while the user is on it; `null` otherwise. */
        val step: IdentificationStep? = null,
        /**
         * The request the user's chosen candidate came from, kept so the plant can be linked
         * back to it once it exists (R31). `null` on the manual route, and where the recogniser
         * ranked nothing.
         */
        val identificationRequestKey: String? = null,
    ) : PlantCaptureState {

        val species: SpeciesEntry? get() = catalogue.firstOrNull { it.key == inputs.speciesKey }

        /** The identification route can be started: the recogniser answers, consent aside. */
        val identificationOffered: Boolean
            get() = identification is IdentificationReadiness.Ready ||
                identification is IdentificationReadiness.ConsentRequired

        /** What the species field shows below the query: matches, nothing, or no catalogue at all. */
        val speciesMatches: List<SpeciesEntry>
            get() {
                val query = inputs.speciesQuery.trim()
                if (query.isEmpty() || inputs.speciesKey != null || inputs.pendingSpecies != null) return emptyList()
                return catalogue.filter { it.matches(query) }.take(MAX_MATCHES)
            }

        val instanceIdTaken: Boolean get() = inputs.instanceId.trim() in takenIds

        private companion object {
            const val MAX_MATCHES = 8
        }
    }

    /**
     * The plant exists. [photoSaved] is `null` when no photo was to be kept, `false` when the
     * upload or the cover call failed — the plant is created either way (R30), and the way on
     * is its page rather than back into the form.
     */
    data class Created(val plantKey: String, val photoSaved: Boolean?) : PlantCaptureState
}

/** What the user typed or chose. */
data class FormInputs(
    val speciesKey: String? = null,
    /** The text in the species field; a chosen species shows its own name there. */
    val speciesQuery: String = "",
    /**
     * A species the recogniser named and the catalogue does not carry (R25). Created on the
     * instance when the plant is, never before; the plant is then created against its key.
     * Typing in the species field again drops it, the same way it drops a chosen key.
     */
    val pendingSpecies: SpeciesDraft? = null,
    val instanceId: String = "",
    /** Once the user edits the identifier, proposals stop overwriting it (R19). */
    val instanceIdEdited: Boolean = false,
    val plantedOn: LocalDate? = null,
    val plantName: String = "",
    val siteKey: String? = null,
    val locationKey: String? = null,
) {
    /** A species is settled either way: chosen from the catalogue, or about to be created. */
    val hasSpecies: Boolean get() = speciesKey != null || pendingSpecies != null
}

enum class FormField { SPECIES, INSTANCE_ID, PLANTED_ON }

/**
 * A photo held for the plant. Identity rather than content equality on purpose: the bytes
 * are a few megabytes, and a state comparison that hashed them on every recomposition would
 * cost more than the comparison saves.
 */
class CapturedPhoto(val jpeg: ByteArray)

/**
 * The image on its way through the identification route: the original, for the second cut
 * the gallery will need (R10), and the recogniser's cut, which is what the preview shows (R9)
 * and what every re-run with another organ sends again (R16). Identity equality, as
 * [CapturedPhoto].
 */
class IdentificationImage(val original: CapturedImage, val recognitionJpeg: ByteArray)

/**
 * Where the identification route is (R5–R16, R33).
 *
 * Each step is its own shape with its own actions. The consent comes first and the camera
 * after it — never an image waiting for an answer that might be "no" — and each way the
 * recogniser can decline reads differently, because each asks the user for something else:
 * a retake, a wait, a different route.
 */
sealed interface IdentificationStep {

    /**
     * The `plant_identification` consent is not on record (R5). [terms] is the instance's own
     * wording, shown verbatim; `null` only where it supplied none. [failed] is a grant that
     * did not go through, worded beside the prompt rather than instead of it.
     */
    data class Consent(val terms: ConsentTerms?, val granting: Boolean = false, val failed: Boolean = false) :
        IdentificationStep

    /** Camera or library, each asked for only now that the user reached for it (R8). */
    data object ChooseSource : IdentificationStep

    /** The image could not be brought under the recogniser's own ceiling; retake. */
    data object Unusable : IdentificationStep

    /** The normalised image, before anything is sent: identify, retake, or leave (R9). */
    data class Preview(val image: IdentificationImage) : IdentificationStep

    data class Identifying(val image: IdentificationImage, val organ: PlantOrgan) : IdentificationStep

    /**
     * The recogniser answered (R13, R15). [weak] says which of the three thin answers this is,
     * `null` for an ordinary ranked list; [organ] is what the answer was asked with, so the
     * organ chips can leave out the one already tried. [selecting] is the rank whose choice is
     * on its way to the instance.
     */
    data class Suggestions(
        val image: IdentificationImage,
        val requestKey: String?,
        val suggestions: List<Suggestion>,
        val weak: WeakResult?,
        val organ: PlantOrgan,
        val message: String? = null,
        val selecting: Int? = null,
    ) : IdentificationStep

    /** The instance did not accept the image; [reason] in its own words. Retake. */
    data class Refused(val image: IdentificationImage, val reason: String) : IdentificationStep

    /** The recogniser asked for a pause (R33); [retryAfterSeconds] where it said how long. */
    data class RateLimited(val image: IdentificationImage, val retryAfterSeconds: Long?) : IdentificationStep

    /** Unreachable, or an answer this build could not read. Retry, or go on by hand. */
    data class Unavailable(val image: IdentificationImage) : IdentificationStep

    /** A role the account lacks; the manual route is the only way on, and no retry is offered. */
    data object NotPermitted : IdentificationStep
}

/**
 * The three answers that are not a usable list (R15), told apart because each calls for a
 * different sentence — and all three offer the organ hint and the manual route (R16).
 */
enum class WeakResult {

    /** `is_plant: false` — whatever this is, the recogniser does not take it for a plant. */
    NOT_A_PLANT,

    /** A plant, but nothing ranked. */
    NOTHING_RECOGNISED,

    /** Candidates, but none the recogniser would stand behind; the manual search leads. */
    LOW_CONFIDENCE,
}

/**
 * Below this, a list of candidates counts as [WeakResult.LOW_CONFIDENCE].
 *
 * The instance already drops anything under its own `CONFIDENCE_SHOW_RESULTS` (0.10) and flags
 * `auto_accept` from 0.85; between the two it leaves the reading to the client. A best guess
 * under thirty percent is one the recogniser itself would not call probable, and the organ
 * hint moves the answer most in exactly that band.
 */
internal const val WEAK_CONFIDENCE = 0.30

/** Common or scientific name, case-insensitively, anywhere in the name (R18). */
internal fun SpeciesEntry.matches(query: String): Boolean =
    scientificName.contains(query, ignoreCase = true) ||
        commonNames.any { it.contains(query, ignoreCase = true) }
