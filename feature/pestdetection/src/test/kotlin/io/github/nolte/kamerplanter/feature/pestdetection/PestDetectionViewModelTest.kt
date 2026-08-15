package io.github.nolte.kamerplanter.feature.pestdetection

import android.content.Context
import android.view.View
import io.github.nolte.kamerplanter.core.network.ConsentOutcome
import io.github.nolte.kamerplanter.core.network.Detection
import io.github.nolte.kamerplanter.core.network.DetectionOutcome
import io.github.nolte.kamerplanter.core.network.DetectionReadiness
import io.github.nolte.kamerplanter.core.network.PestDetectionClient
import io.github.nolte.kamerplanter.core.network.RefusedReason
import io.github.nolte.kamerplanter.feature.microscope.CapturedFrame
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeButton
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeCamera
import io.github.nolte.kamerplanter.feature.microscope.MicroscopeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the order the flow insists on: gate, then consent, then the frame.
 *
 * That order is the whole design. A captured image is a thing the user expects to be used, so
 * nothing may be captured before the instance has said it will look at it — and where the
 * active adapter sends the image off the instance, not before the user has agreed to that.
 * The tests that assert *no* capture happened are the load-bearing ones here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PestDetectionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val camera = FakeCamera()
    private val detections = FakeDetectionClient()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PestDetectionViewModel(detections, camera)

    // ── Gating ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a ready instance opens the viewfinder`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready

        val state = viewModel().state.settled()

        assertEquals(PestDetectionState.Ready(), state)
    }

    @Test
    fun `an instance that does not offer detection says so`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.NotOffered

        assertEquals(PestDetectionState.NotOffered, viewModel().state.settled())
    }

    @Test
    fun `a missing connection points at Settings rather than at the camera`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.NotConnected

        assertEquals(PestDetectionState.NotConnected, viewModel().state.settled())
    }

    @Test
    fun `a refused credential is its own state`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Unauthorized

        assertEquals(PestDetectionState.Unauthorized, viewModel().state.settled())
    }

    /**
     * The client tells "refused" from "not permitted" apart; this pins that the ViewModel keeps
     * them apart. Collapsing the two here would put "connect again" in front of a credential
     * whose scope simply excludes detection — and re-pairing returns it to the same 403.
     */
    @Test
    fun `a credential outside its scope is not asked to reconnect`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.NotPermitted

        assertEquals(PestDetectionState.NotPermitted, viewModel().state.settled())
    }

    @Test
    fun `an unreachable instance is a retryable failure`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Unavailable("boom")

        val state = viewModel().state.settled() as PestDetectionState.Failed

        assertTrue("an unreachable instance is worth another try", state.canRetry)
    }

    // ── Consent ──────────────────────────────────────────────────────────────────────

    /**
     * The one the whole ordering exists for: with consent outstanding, the shutter must not
     * have fired. A frame captured first and uploaded after the answer would mean the image
     * exists before the user agreed to it existing anywhere.
     */
    @Test
    fun `nothing is captured while consent is outstanding`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.ConsentRequired("pest_detection_cloud")

        val model = viewModel()
        model.state.settled()
        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            PestDetectionState.ConsentRequired("pest_detection_cloud"),
            model.state.value,
        )
        assertEquals("the shutter must not have fired", 0, camera.captures)
        assertTrue("and nothing may have been uploaded", detections.uploads.isEmpty())
    }

    /**
     * Granting re-asks rather than assuming. The grant answers *this* adapter's requirement,
     * and an instance whose active adapter changed in between would otherwise be treated as
     * ready on the strength of a consent that no longer applies to it.
     */
    @Test
    fun `granting consent re-asks the instance`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.ConsentRequired("pest_detection_cloud")
        val model = viewModel()
        model.state.settled()

        detections.readiness = DetectionReadiness.Ready
        model.grantConsent()

        assertEquals(PestDetectionState.Ready(), model.state.settled())
        assertEquals(listOf("pest_detection_cloud"), detections.granted)
        assertEquals("the readiness question is asked again", 2, detections.readinessCalls)
    }

    /**
     * A consent the credential may not record is not a refused credential.
     *
     * Re-pairing cannot widen a scope, so "connect again" sends the user round a loop back to
     * the same 403. The client already tells the two apart; this pins that the ViewModel keeps
     * them apart, which is where the distinction was previously lost.
     */
    @Test
    fun `a consent the credential may not record does not ask for a reconnect`() =
        runTest(dispatcher) {
            detections.readiness = DetectionReadiness.ConsentRequired("pest_detection_cloud")
            detections.consentOutcome = ConsentOutcome.NotPermitted
            val model = viewModel()
            model.state.settled()

            model.grantConsent()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(PestDetectionState.NotPermitted, model.state.value)
        }

    @Test
    fun `a consent the instance will not record is a failure, not a green light`() =
        runTest(dispatcher) {
            detections.readiness = DetectionReadiness.ConsentRequired("pest_detection_cloud")
            detections.consentOutcome = ConsentOutcome.Failed("boom")
            val model = viewModel()
            model.state.settled()

            model.grantConsent()
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(model.state.value is PestDetectionState.Failed)
            assertEquals(0, camera.captures)
        }

    // ── Capture ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a completed detection carries the frame it was taken from`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        detections.outcome = DetectionOutcome.Completed(detection())
        val model = viewModel()
        model.state.settled()

        model.capture("de")
        dispatcher.scheduler.advanceUntilIdle()

        val result = model.state.value as PestDetectionState.Result
        // The backend never stores the image, so the captured bytes are the only copy there
        // is — a findings list without the picture it refers to is unreadable.
        assertTrue(result.frame.contentEquals(camera.frame))
        assertEquals("det-1", result.detection.key)
        assertEquals(listOf("de"), detections.uploads)
    }

    @Test
    fun `a microscope that delivers no frame fails without uploading`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        camera.failCapture = true
        val model = viewModel()
        model.state.settled()

        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value is PestDetectionState.Failed)
        assertTrue("nothing to upload, so nothing was", detections.uploads.isEmpty())
    }

    /** Two taps on the shutter must not become two uploads of two different frames. */
    @Test
    fun `a second tap while uploading is ignored`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        detections.outcome = DetectionOutcome.Completed(detection())
        val model = viewModel()
        model.state.settled()

        model.capture("en")
        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, camera.captures)
        assertEquals(1, detections.uploads.size)
    }

    /**
     * A credential that may not run detections does not gain the permission by being asked
     * twice, and neither does a frame refused for its type.
     */
    @Test
    fun `the two hopeless refusals offer no retry`() = runTest(dispatcher) {
        listOf(
            RefusedReason.NOT_PERMITTED,
            RefusedReason.UNSUPPORTED_TYPE,
            // A proxy's body cap is a fixed number and every capture is over it, so "try
            // again, the next one is usually smaller" would be a loop with no exit.
            RefusedReason.REFUSED_BY_PROXY,
        ).forEach { reason ->
            detections.readiness = DetectionReadiness.Ready
            detections.outcome = DetectionOutcome.Refused(reason)
            val model = viewModel()
            model.state.settled()

            model.capture("en")
            dispatcher.scheduler.advanceUntilIdle()

            val state = model.state.value as PestDetectionState.Failed
            assertFalse("$reason cannot be retried into success", state.canRetry)
        }
    }

    /** The next frame is a different frame, so these two are worth another shot. */
    @Test
    fun `a rejected frame may be retried`() = runTest(dispatcher) {
        listOf(RefusedReason.TOO_LARGE, RefusedReason.NOT_PROCESSABLE).forEach { reason ->
            detections.readiness = DetectionReadiness.Ready
            detections.outcome = DetectionOutcome.Refused(reason)
            val model = viewModel()
            model.state.settled()

            model.capture("en")
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue((model.state.value as PestDetectionState.Failed).canRetry)
        }
    }

    /**
     * Reachable despite the pre-flight check: the consent can be revoked in the web UI between
     * asking and uploading. The user belongs back in front of the consent question, not at a
     * retry button that would send the same frame into the same refusal.
     */
    @Test
    fun `a consent revoked mid-flow returns to the consent question`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        detections.outcome = DetectionOutcome.Refused(RefusedReason.CONSENT_MISSING)
        val model = viewModel()
        model.state.settled()

        detections.readiness = DetectionReadiness.ConsentRequired("pest_detection_cloud")
        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            PestDetectionState.ConsentRequired("pest_detection_cloud"),
            model.state.value,
        )
    }

    @Test
    fun `taking another photo returns to the viewfinder`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        detections.outcome = DetectionOutcome.Completed(detection())
        val model = viewModel()
        model.state.settled()
        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        model.captureAgain()

        assertEquals(PestDetectionState.Ready(), model.state.value)
    }

    private fun detection() = Detection(
        key = "det-1",
        isConfident = true,
        findings = emptyList(),
        disclaimer = "Only an estimate.",
        suggestedNextStep = "Look again",
        tilesProcessed = 4,
    )

    /** Runs the pending work and returns the state it left behind. */
    private fun StateFlow<PestDetectionState>.settled(): PestDetectionState {
        dispatcher.scheduler.advanceUntilIdle()
        return value
    }

    private class FakeDetectionClient : PestDetectionClient {

        var readiness: DetectionReadiness = DetectionReadiness.Ready
        var consentOutcome: ConsentOutcome = ConsentOutcome.Granted
        var outcome: DetectionOutcome = DetectionOutcome.Unavailable("not set")

        var readinessCalls = 0
        val granted = mutableListOf<String>()

        /** The language of every upload that was actually made — empty means none was. */
        val uploads = mutableListOf<String>()

        override suspend fun readiness(): DetectionReadiness {
            readinessCalls++
            return readiness
        }

        override suspend fun grantConsent(purpose: String): ConsentOutcome {
            granted += purpose
            return consentOutcome
        }

        override suspend fun detect(
            jpeg: ByteArray,
            plantKey: String?,
            language: String,
        ): DetectionOutcome {
            uploads += language
            return outcome
        }
    }

    private class FakeCamera : MicroscopeCamera {

        val frame = byteArrayOf(1, 2, 3)
        var failCapture = false
        var captures = 0

        override val state: StateFlow<MicroscopeState> =
            MutableStateFlow(MicroscopeState.Streaming)

        override val buttonPresses: SharedFlow<MicroscopeButton> = MutableSharedFlow()

        override fun createPreviewView(context: Context): View = error("not used off-device")

        override fun start() = Unit

        override fun stop() = Unit

        override suspend fun captureFrame(): Result<CapturedFrame> {
            captures++
            return if (failCapture) {
                Result.failure(IllegalStateException("no frame"))
            } else {
                Result.success(CapturedFrame(frame, width = 1920, height = 1080))
            }
        }

        override fun zoomBy(deltaPercent: Int) = Unit
    }
}
