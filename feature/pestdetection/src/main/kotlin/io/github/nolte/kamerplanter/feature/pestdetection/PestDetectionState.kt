package io.github.nolte.kamerplanter.feature.pestdetection

import androidx.annotation.StringRes
import io.github.nolte.kamerplanter.core.network.ConsentTerms
import io.github.nolte.kamerplanter.core.network.Detection

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

    /** Ready to capture. [isUploading] covers the frame being taken and sent. */
    data class Ready(val isUploading: Boolean = false) : PestDetectionState

    /**
     * A detection came back.
     *
     * [frame] is the JPEG the boxes are drawn over — kept because the backend never stores the
     * image, so this is the only copy that exists, and a findings list without the picture it
     * refers to is unreadable.
     */
    data class Result(val frame: ByteArray, val detection: Detection) : PestDetectionState {

        // Generated equals/hashCode would compare the array by identity, which makes two
        // states holding the same frame unequal and re-triggers recomposition. Compared by
        // content instead, and the array is never mutated after capture.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Result && detection == other.detection && frame.contentEquals(other.frame))

        override fun hashCode(): Int = 31 * frame.contentHashCode() + detection.hashCode()
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
