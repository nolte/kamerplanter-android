package io.github.nolte.kamerplanter.feature.plants

import android.content.Context
import android.view.View
import io.github.nolte.kamerplanter.core.network.ActionOutcome
import io.github.nolte.kamerplanter.core.network.CareAction
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.DetectionFeedback
import io.github.nolte.kamerplanter.core.network.DetectionHistoryOutcome
import io.github.nolte.kamerplanter.core.network.DetectionOutcome
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.DiaryDraft
import io.github.nolte.kamerplanter.core.network.DiaryEntry
import io.github.nolte.kamerplanter.core.network.DiaryOutcome
import io.github.nolte.kamerplanter.core.network.FeedbackOutcome
import io.github.nolte.kamerplanter.core.network.InspectionOutcome
import io.github.nolte.kamerplanter.core.network.PestDetectionClient
import io.github.nolte.kamerplanter.core.network.PlantActionsClient
import io.github.nolte.kamerplanter.core.network.PlantDetail
import io.github.nolte.kamerplanter.core.network.PlantPageClient
import io.github.nolte.kamerplanter.core.network.PlantPhase
import io.github.nolte.kamerplanter.core.network.PlantPhoto
import io.github.nolte.kamerplanter.core.network.PlantRemoval
import io.github.nolte.kamerplanter.core.network.SectionOutcome
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
 * Stand-ins for what a plant's page talks to.
 *
 * Separate from the unit suite's near-identical fakes for the reason given in
 * `:feature:pestdetection`'s own copy: sharing them would mean reworking a green suite to
 * prove a rendering claim.
 */
internal class FakePageClient(
    private val detail: PlantDetail = plantDetail(),
    private val phases: List<PlantPhase> = emptyList(),
) : PlantPageClient {

    override suspend fun plant(key: String): SectionOutcome<PlantDetail> =
        SectionOutcome.Loaded(detail)

    override suspend fun photos(key: String): SectionOutcome<List<PlantPhoto>> =
        SectionOutcome.Loaded(emptyList())

    override suspend fun phaseHistory(key: String): SectionOutcome<List<PlantPhase>> =
        SectionOutcome.Loaded(phases)

    override suspend fun care(key: String): SectionOutcome<List<CareAction>> =
        SectionOutcome.Loaded(emptyList())
}

/** Serves one fixed page of diary entries and records what was asked of it. */
internal class FakeActionsClient(private val entries: List<DiaryEntry>) : PlantActionsClient {

    val analysed = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override suspend fun diary(plantKey: String, offset: Int, limit: Int): DiaryOutcome =
        if (offset == 0) DiaryOutcome.Loaded(entries) else DiaryOutcome.Loaded(emptyList())

    override suspend fun addPhoto(plantKey: String, jpeg: ByteArray, asCover: Boolean): ActionOutcome =
        ActionOutcome.Done

    override suspend fun addEntry(plantKey: String, draft: DiaryDraft): ActionOutcome =
        ActionOutcome.Done

    override suspend fun updateEntry(
        plantKey: String,
        entryKey: String,
        draft: DiaryDraft,
    ): ActionOutcome = ActionOutcome.Done

    override suspend fun deleteEntry(plantKey: String, entryKey: String): ActionOutcome {
        deleted += entryKey
        return ActionOutcome.Done
    }

    override suspend fun requestAnalysis(plantKey: String, entryKey: String): ActionOutcome {
        analysed += entryKey
        return ActionOutcome.Done
    }

    override suspend fun confirmCare(plantKey: String, kind: String): ActionOutcome =
        ActionOutcome.Done
}

/**
 * Reports the pest check as unavailable; these tests are about the diary.
 *
 * [pastChecks] is the exception: the plant page renders a past check's timestamp, so a test
 * about dates has to be able to put one there. Left empty, that section never composes and a
 * raw timestamp in it would go unseen.
 */
internal class QuietDetectionClient(
    private val pastChecks: List<Detection> = emptyList(),
) : PestDetectionClient {

    override suspend fun readiness(): DetectionReadiness =
        if (pastChecks.isEmpty()) DetectionReadiness.NotOffered else DetectionReadiness.Ready

    override suspend fun grantConsent(purpose: String): ConsentOutcome = ConsentOutcome.Granted

    override suspend fun detect(
        jpeg: ByteArray,
        plantKey: String?,
        language: String,
    ): DetectionOutcome = DetectionOutcome.Unavailable("not used here")

    override suspend fun submitFeedback(
        detectionKey: String,
        feedback: DetectionFeedback,
    ): FeedbackOutcome = FeedbackOutcome.Failed("not used here")

    override suspend fun createInspection(
        detectionKey: String,
        plantKey: String,
    ): InspectionOutcome = InspectionOutcome.Failed("not used here")

    override suspend fun history(plantKey: String, limit: Int): DetectionHistoryOutcome =
        DetectionHistoryOutcome.Loaded(pastChecks)
}

/** A completed check, carrying the timestamp shape the instance actually sends. */
internal fun pastCheck(recordedAt: String) = Detection(
    key = "det-1",
    isConfident = true,
    findings = emptyList(),
    disclaimer = "Only an estimate.",
    suggestedNextStep = "Look again",
    tilesProcessed = 1,
    feedback = emptyList(),
    recordedAt = recordedAt,
)

/** A microscope that is never attached; the diary tests never reach for one. */
internal class FakeCamera : MicroscopeCamera {

    override val state: StateFlow<MicroscopeState> = MutableStateFlow(
        MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED),
    )

    override val buttonPresses: SharedFlow<MicroscopeButton> = MutableSharedFlow()

    override fun createPreviewView(context: Context): View =
        error("no preview should be built in a diary test")

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun captureFrame(): Result<CapturedFrame> =
        Result.failure(IllegalStateException("no microscope in these tests"))

    override fun zoomBy(deltaPercent: Int) = Unit
}

internal fun plantDetail(
    name: String = "Monstera",
    plantedOn: String? = null,
    phase: PlantPhase? = null,
    removal: PlantRemoval? = null,
) = PlantDetail(
    key = "p1",
    displayName = name,
    species = "Monstera deliciosa",
    location = "Windowsill",
    plantedOn = plantedOn,
    removal = removal,
    phase = phase,
    containerVolumeLiters = null,
    substrate = null,
    cultivationCycle = null,
    motherKey = null,
)

internal fun diaryEntry(
    key: String,
    text: String,
    canRequestAnalysis: Boolean = false,
) = DiaryEntry(
    key = key,
    kind = "note",
    title = null,
    text = text,
    createdAt = "2026-08-01",
    canRequestAnalysis = canRequestAnalysis,
)
