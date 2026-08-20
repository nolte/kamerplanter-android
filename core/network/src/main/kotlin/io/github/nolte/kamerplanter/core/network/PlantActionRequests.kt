package io.github.nolte.kamerplanter.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The body of a diary write.
 *
 * Hand-written rather than the generated `DiaryEntryCreateRequest`: a generated model may not
 * cross out of this module (ADR 0001, R-GEN-5), and this carries the four fields a note needs
 * out of the seven the endpoint accepts. Tags and measurements are the same call with more in
 * it, once there is a screen that collects them.
 *
 * **No default values, deliberately.** `kotlinx.serialization` omits a property that equals its
 * default unless `encodeDefaults` is on, and this app's `Json` leaves it off. Giving
 * `entry_type` the sensible default `"note"` therefore meant it was never sent — against an
 * endpoint that requires it. The result was a 422 on every diary entry the app ever wrote, from
 * a line that reads as obviously correct. Anything this body must always carry is stated at the
 * call site instead.
 */
@Serializable
internal data class NoteRequest(
    val text: String,
    /** Optional, and at most 200 characters; omitted entirely when the writer left it blank. */
    val title: String? = null,
    @SerialName("entry_type") val entryType: String,
    /** Attachment ids of photos already uploaded; the endpoint takes at most five. */
    @SerialName("photo_refs") val photoRefs: List<String>,
    val tags: List<String> = emptyList(),
    /**
     * Asks the instance to attach what its sensors read. The backend's own default is true.
     *
     * Null on an update, and omitted from the body then: `PUT` has no such field, because it
     * asks the server to *look* at its sensors and an entry being rewritten was looked at when
     * it was written.
     */
    @SerialName("capture_environment") val captureEnvironment: Boolean? = null,
)

/** The body of a care confirmation: which kind of task was done. */
@Serializable
internal data class ConfirmRequest(
    @SerialName("reminder_type") val reminderType: String,
)
