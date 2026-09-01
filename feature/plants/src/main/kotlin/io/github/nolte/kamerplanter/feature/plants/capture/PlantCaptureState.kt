package io.github.nolte.kamerplanter.feature.plants.capture

import androidx.annotation.StringRes
import io.github.nolte.kamerplanter.core.network.Location
import io.github.nolte.kamerplanter.core.network.Site
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import java.time.LocalDate

/**
 * What the add-a-plant screen can be showing (R-8).
 *
 * The form is one state with many fields rather than a state per step: both routes end in it,
 * and the user moves freely between its fields. Everything before it — loading the catalogue,
 * the places and the identifiers already taken — and everything after it — the plant existing —
 * is its own state with its own action (R33).
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
     * identifier proposal (R19) is derived into [inputs] whenever species or location change
     * and the user has not taken the field over.
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
    ) : PlantCaptureState {

        val species: SpeciesEntry? get() = catalogue.firstOrNull { it.key == inputs.speciesKey }

        /** What the species field shows below the query: matches, nothing, or no catalogue at all. */
        val speciesMatches: List<SpeciesEntry>
            get() {
                val query = inputs.speciesQuery.trim()
                if (query.isEmpty() || inputs.speciesKey != null) return emptyList()
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
    val instanceId: String = "",
    /** Once the user edits the identifier, proposals stop overwriting it (R19). */
    val instanceIdEdited: Boolean = false,
    val plantedOn: LocalDate? = null,
    val plantName: String = "",
    val siteKey: String? = null,
    val locationKey: String? = null,
)

enum class FormField { SPECIES, INSTANCE_ID, PLANTED_ON }

/**
 * A photo held for the plant. Identity rather than content equality on purpose: the bytes
 * are a few megabytes, and a state comparison that hashed them on every recomposition would
 * cost more than the comparison saves.
 */
class CapturedPhoto(val jpeg: ByteArray)

/** Common or scientific name, case-insensitively, anywhere in the name (R18). */
internal fun SpeciesEntry.matches(query: String): Boolean =
    scientificName.contains(query, ignoreCase = true) ||
        commonNames.any { it.contains(query, ignoreCase = true) }
