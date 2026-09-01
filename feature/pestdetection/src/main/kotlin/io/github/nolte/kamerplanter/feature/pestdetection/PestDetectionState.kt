package io.github.nolte.kamerplanter.feature.pestdetection

import androidx.annotation.StringRes
import io.github.nolte.kamerplanter.core.network.ConsentTerms
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.RecordedFeedback

/**
 * What the pest-detection screen can be showing.
 *
 * The gating states are separate from the capture states on purpose. Whether the instance
 * offers detection at all is settled before the camera is ever opened — the feature ships
 * disabled upstream and needs an adapter an operator has to configure, so "your instance does
 * not offer this" is an ordinary answer rather than an error, and it must not look like one.
 */
sealed interface PestDetectionState {

    /** Asking the instance whether it can run a detection. */
    data object CheckingInstance : PestDetectionState

    /** No instance is connected, so there is nothing to ask and nothing to upload to. */
    data object NotConnected : PestDetectionState

    /** The instance answered, and its operator has not enabled detection. */
    data object NotOffered : PestDetectionState

    /** The stored credential was refused; the user has to reconnect in Settings. */
    data object Unauthorized : PestDetectionState

    /** The credential works, but this tenant does not let it run detections. */
    data object NotPermitted : PestDetectionState

    /** The instance answered something this build cannot read; asking again changes nothing. */
    data object NotUnderstood : PestDetectionState

    /**
     * The active adapter sends the image off the instance, and the user has not agreed to
     * that yet. No frame is captured in this state — the consent question comes before the
     * camera, not before the upload, so a captured frame can never sit around waiting for an
     * answer that might be "no".
     *
     * [terms] is the instance's own wording, shown verbatim. `null` only where the instance
     * did not supply any, which is the one case the screen falls back to its own text for.
     */
    data class ConsentRequired(
        val purpose: String,
        val terms: ConsentTerms? = null,
        val isGranting: Boolean = false,
    ) : PestDetectionState

    /**
     * Ready to capture, once a source is chosen.
     *
     * [source] is `null` until the user picks one, which is what the picker renders. Both feed
     * the same upload — the choice decides only where the bytes come from — but they suit
     * opposite subjects: the phone frames a whole leaf at arm's length for a damage pattern,
     * the microscope resolves the animal itself, and only the user can say which they are
     * looking at.
     *
     * [isUploading] covers the frame being taken and sent.
     */
    data class Ready(
        val source: CaptureSource? = null,
        val isUploading: Boolean = false,
        /**
         * Whether the phone camera has finished binding.
         *
         * In the state rather than only in the ViewModel because the shutter is gated on it:
         * binding is asynchronous, and a button offered before it completes does not fail
         * quietly — it ends the flow on an error screen and loses the chosen source. Cleared
         * whenever the source changes, so a stale "bound" from the phone cannot follow the
         * user to the microscope, which has its own readiness in its camera state.
         */
        val phoneReady: Boolean = false,
    ) : PestDetectionState

    /**
     * A detection came back.
     *
     * [frame] is the JPEG the boxes are drawn over — kept because the backend never stores the
     * image, so this is the only copy that exists, and a findings list without the picture it
     * refers to is unreadable.
     */
    data class Result(
        val frame: ByteArray,
        val detection: Detection,
        /**
         * Whether this detection is bound to a plant.
         *
         * What decides if an inspection can be offered at all: the endpoint files one against
         * a plant, and a detection run from the Capture tab has none. Carried in the state
         * rather than read from the route by the screen, so the screen renders what it is
         * given.
         */
        val plantBound: Boolean = false,
        /** The finding whose verdict is on its way; the others stay usable meanwhile. */
        val recordingFor: String? = null,
        val filingInspection: Boolean = false,
        /** Filed once, and the action does not come back: a second inspection is not a fix. */
        val inspectionFiled: Boolean = false,
        /** The frame is on its way into the plant's photo gallery (F-3). */
        val keepingPhoto: Boolean = false,
        /**
         * Kept once, or refused for good; the offer does not come back — the same photo twice
         * is not a feature, and a role the instance denies is not widened by asking again.
         */
        val photoKept: Boolean = false,
        /**
         * A sentence about what just happened — recorded, filed, or refused.
         *
         * A resource id rather than text, and separate from [PestDetectionState.Failed]
         * because none of these end the flow: the result stays on screen with its findings,
         * and "you may not file inspections" is something to read beside them rather than
         * instead of them.
         */
        @StringRes val notice: Int? = null,
    ) : PestDetectionState {

        /** What a human already said about [label], where anybody has. */
        fun verdictOn(label: String): RecordedFeedback? =
            detection.feedback.lastOrNull { it.findingLabel == label }

        // Generated equals/hashCode would compare the array by identity, which makes two
        // states holding the same frame unequal and re-triggers recomposition. Compared by
        // content instead, and the array is never mutated after capture.
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Result &&
                    detection == other.detection &&
                    plantBound == other.plantBound &&
                    recordingFor == other.recordingFor &&
                    filingInspection == other.filingInspection &&
                    inspectionFiled == other.inspectionFiled &&
                    keepingPhoto == other.keepingPhoto &&
                    photoKept == other.photoKept &&
                    notice == other.notice &&
                    frame.contentEquals(other.frame)
                )

        override fun hashCode(): Int {
            var result = frame.contentHashCode()
            result = 31 * result + detection.hashCode()
            result = 31 * result + plantBound.hashCode()
            result = 31 * result + recordingFor.hashCode()
            result = 31 * result + filingInspection.hashCode()
            result = 31 * result + inspectionFiled.hashCode()
            result = 31 * result + keepingPhoto.hashCode()
            result = 31 * result + photoKept.hashCode()
            result = 31 * result + notice.hashCode()
            return result
        }
    }

    /**
     * Something went wrong that the user can retry from.
     *
     * [message] is a resource id rather than text, so the message stays localizable and the
     * ViewModel stays free of `Context`.
     */
    data class Failed(@StringRes val message: Int, val canRetry: Boolean = true) :
        PestDetectionState
}

/** Where a frame comes from. */
enum class CaptureSource {

    /** The device's own camera — the whole leaf, at arm's length. */
    PHONE,

    /** The attached USB microscope — the animal itself, at magnification. */
    MICROSCOPE,
}

/** What a human says about one finding. */
enum class FeedbackVerdict {

    /** The recognizer got it right. */
    CORRECT,

    /** It got it wrong, and the user is not saying what it actually was. */
    WRONG,

    /**
     * It named a pest, and the animal is a beneficial.
     *
     * Told apart from [WRONG] because acting on the two differs by more than a label: a
     * beneficial sprayed as a pest is the one outcome this feature must never produce, and the
     * instance can only learn that from feedback that says so.
     */
    BENEFICIAL,
}

/**
 * What a plant's past detections look like on screen.
 *
 * Its own state beside [PestDetectionState] rather than inside it: the history is opened over
 * a result or over the viewfinder, and folding it into the flow's state would mean every
 * capture state needed a variant that also happens to be showing a list.
 */
sealed interface DetectionHistoryState {

    /** Not asked for. */
    data object Hidden : DetectionHistoryState

    data object Loading : DetectionHistoryState

    /** Newest first, as the instance ordered them; empty is a legitimate answer. */
    data class Shown(val detections: List<Detection>) : DetectionHistoryState

    data class Failed(@StringRes val message: Int) : DetectionHistoryState
}
