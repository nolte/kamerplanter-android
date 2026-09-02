package io.github.nolte.kamerplanter.feature.pestdetection

import android.content.Context
import android.view.View
import io.github.nolte.kamerplanter.core.camera.PhoneCameraShutter
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.DetectionFeedback
import io.github.nolte.kamerplanter.core.network.DetectionHistoryOutcome
import io.github.nolte.kamerplanter.core.network.DetectionOutcome
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.DiaryDraft
import io.github.nolte.kamerplanter.core.network.DiaryOutcome
import io.github.nolte.kamerplanter.core.network.FeedbackOutcome
import io.github.nolte.kamerplanter.core.network.Finding
import io.github.nolte.kamerplanter.core.network.InspectionOutcome
import io.github.nolte.kamerplanter.core.network.PestDetectionClient
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.feature.microscope.CapturedFrame
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeButton
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeCamera
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import io.github.nolte.kamerplanter.feature.microscope.UnavailableReason
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stand-ins for what this screen talks to, kept separate from the unit tests' own.
 *
 * The unit suite has near-identical fakes, declared `private` inside its test class. They are
 * deliberately not shared: hoisting them into `testFixtures` would mean reworking a green
 * 397-test suite to prove a rendering claim, and the risk of breaking those tests outweighs
 * the duplication. If a third caller ever needs them, that is the moment to hoist.
 *
 * The instrumented flavour differs in one way that matters: there is no `TestDispatcher` here.
 * Compose needs the real main looper, so tests wait on the rendered result with
 * `waitUntil` instead of advancing a scheduler.
 */
internal class FakeDetectionClient : PestDetectionClient {

    var readiness: DetectionReadiness = DetectionReadiness.Ready
    var outcome: DetectionOutcome = DetectionOutcome.Unavailable("not set")
    var feedbackOutcome: FeedbackOutcome = FeedbackOutcome.Failed("not set")
    var inspectionOutcome: InspectionOutcome = InspectionOutcome.Failed("not set")
    var historyOutcome: DetectionHistoryOutcome = DetectionHistoryOutcome.Loaded(emptyList())

    override suspend fun readiness(): DetectionReadiness = readiness

    override suspend fun grantConsent(purpose: String): ConsentOutcome = ConsentOutcome.Granted

    override suspend fun detect(
        jpeg: ByteArray,
        plantKey: String?,
        language: String,
    ): DetectionOutcome = outcome

    override suspend fun submitFeedback(
        detectionKey: String,
        feedback: DetectionFeedback,
    ): FeedbackOutcome = feedbackOutcome

    override suspend fun createInspection(
        detectionKey: String,
        plantKey: String,
    ): InspectionOutcome = inspectionOutcome

    override suspend fun history(plantKey: String, limit: Int): DetectionHistoryOutcome =
        historyOutcome
}

/** A phone shutter that hands back [jpeg] without touching a camera. */
internal class FakeShutter(private val jpeg: ByteArray? = byteArrayOf(9)) : PhoneCameraShutter {
    override suspend fun capture(maxBytes: Int): ByteArray? = jpeg
}

/**
 * A microscope that is never attached.
 *
 * These tests drive the phone path, so the UVC side only has to stay quiet and out of the way.
 * `createPreviewView` throwing is the point: if a test ever reaches it, the test is exercising
 * a path it did not mean to.
 */
internal class FakeCamera : MicroscopeCamera {

    override val state: StateFlow<MicroscopeState> = MutableStateFlow(
        MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED),
    )

    override val buttonPresses: SharedFlow<MicroscopeButton> = MutableSharedFlow()

    override fun createPreviewView(context: Context): View =
        error("these tests drive the phone path; no preview should be built")

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun captureFrame(): Result<CapturedFrame> =
        Result.failure(IllegalStateException("no microscope in these tests"))

    override fun zoomBy(deltaPercent: Int) = Unit
}

/** A pest finding, with only the fields a test varies spelled out at each call site. */
internal fun finding(
    label: String = "aphid",
    commonName: String = "Aphid",
    isBeneficial: Boolean = false,
    mode: String = "direct",
) = Finding(
    label = label,
    commonName = commonName,
    category = if (isBeneficial) "beneficial" else "pest",
    confidence = 0.9,
    mode = mode,
    boundingBox = null,
    isBeneficial = isBeneficial,
)

/** A completed detection. [isConfident] `false` is the abstention case, not an error. */
internal fun detection(
    findings: List<Finding> = emptyList(),
    isConfident: Boolean = true,
    disclaimer: String = "Only an estimate.",
) = Detection(
    key = "det-1",
    isConfident = isConfident,
    findings = findings,
    disclaimer = disclaimer,
    suggestedNextStep = "Look again",
    tilesProcessed = 4,
    feedback = emptyList(),
)

/** The plant-photo seam, inert: these rendering tests never keep a photo. */
internal class FakePlantActions : PlantActionsClient {

    override suspend fun diary(plantKey: String, offset: Int, limit: Int): DiaryOutcome =
        DiaryOutcome.Loaded(emptyList())

    override suspend fun addEntry(plantKey: String, draft: DiaryDraft): ActionOutcome =
        ActionOutcome.Done

    override suspend fun updateEntry(
        plantKey: String,
        entryKey: String,
        draft: DiaryDraft,
    ): ActionOutcome = ActionOutcome.Done

    override suspend fun deleteEntry(plantKey: String, entryKey: String): ActionOutcome =
        ActionOutcome.Done

    override suspend fun requestAnalysis(plantKey: String, entryKey: String): ActionOutcome =
        ActionOutcome.Done

    override suspend fun addPhoto(plantKey: String, jpeg: ByteArray, asCover: Boolean): ActionOutcome =
        ActionOutcome.Done

    override suspend fun confirmCare(plantKey: String, kind: String): ActionOutcome =
        ActionOutcome.Done
}
