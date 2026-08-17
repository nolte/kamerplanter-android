package io.github.nolte.kamerplanter.feature.pestdetection

import android.content.Context
import android.view.View
import androidx.lifecycle.SavedStateHandle
import io.github.nolte.kamerplanter.core.camera.PhoneCameraShutter
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
import org.junit.Assert.assertNull
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

    /**
     * [plantKey] is what the flow was opened for: absent from the Capture tab, present when a
     * plant's own page pushed it. The detection is filed against it either way, so the tests
     * can drive both.
     */
    private fun viewModel(plantKey: String? = null) = PestDetectionViewModel(
        detections,
        camera,
        SavedStateHandle(plantKey?.let { mapOf(PLANT_KEY_ARG to it) } ?: emptyMap()),
    )

    /**
     * A detection opened from a plant is filed against that plant.
     *
     * The whole point of the second entry point: the same camera, reached from a plant's own
     * page, must produce a finding the instance can attach to that plant rather than a
     * free-floating one the user has to file by hand.
     */
    @Test
    fun `a detection opened from a plant carries its key`() = runTest(dispatcher) {
        val model = viewModel(plantKey = "plant-1")
        model.state.settled()
        model.chooseSource(CaptureSource.MICROSCOPE)

        model.capture("de")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("plant-1"), detections.plantKeys)
    }

    /** Entered from the Capture tab there is no plant, and the upload must say so. */
    @Test
    fun `a detection opened from the capture tab carries no key`() = runTest(dispatcher) {
        val model = viewModel()
        model.state.settled()
        model.chooseSource(CaptureSource.MICROSCOPE)

        model.capture("de")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(null), detections.plantKeys)
    }

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

    /**
     * An answer the app cannot read is not an unreachable instance.
     *
     * Collapsing the two puts "check that your instance is running" in front of someone whose
     * instance answered 200, and attaches a retry that will meet the same answer every time.
     */
    @Test
    fun `an unreadable answer is its own state`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.NotUnderstood

        assertEquals(PestDetectionState.NotUnderstood, viewModel().state.settled())
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
        model.chooseSource(CaptureSource.MICROSCOPE)

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
        model.chooseSource(CaptureSource.MICROSCOPE)

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
        model.chooseSource(CaptureSource.MICROSCOPE)

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
            model.chooseSource(CaptureSource.MICROSCOPE)

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
            model.chooseSource(CaptureSource.MICROSCOPE)

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

        model.chooseSource(CaptureSource.MICROSCOPE)
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
        model.chooseSource(CaptureSource.MICROSCOPE)
        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        model.captureAgain()

        // The source survives: someone who just photographed one leaf through the microscope
        // is about to photograph another, and sending them back to the picker each time would
        // be a step they did not ask for.
        assertEquals(PestDetectionState.Ready(source = CaptureSource.MICROSCOPE), model.state.value)
    }

    /**
     * Each refusal keeps its own wording.
     *
     * The retry flags were pinned but the messages were not, and the two carry different
     * halves of the answer: a proxy's fixed body cap and the app's own size guard are both
     * "too large" with `canRetry` apart, but only one of them is worth another shot — and
     * swapping the strings would tell a self-hoster to keep photographing.
     */
    @Test
    fun `each refusal keeps its own message`() = runTest(dispatcher) {
        val messages = listOf(
            RefusedReason.TOO_LARGE to R.string.pest_failed_too_large,
            RefusedReason.REFUSED_BY_PROXY to R.string.pest_failed_proxy_limit,
            RefusedReason.NOT_PROCESSABLE to R.string.pest_failed_not_processable,
            RefusedReason.UNSUPPORTED_TYPE to R.string.pest_failed_unsupported_type,
            RefusedReason.NOT_PERMITTED to R.string.pest_failed_not_permitted,
        )

        messages.forEach { (reason, expected) ->
            detections.readiness = DetectionReadiness.Ready
            detections.outcome = DetectionOutcome.Refused(reason)
            val model = viewModel()
            model.state.settled()
            model.chooseSource(CaptureSource.MICROSCOPE)

            model.capture("en")
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("$reason", expected, (model.state.value as PestDetectionState.Failed).message)
        }
    }

    // ── Source choice (#10, phone camera) ────────────────────────────────────────────

    /**
     * The picker is a step, not a default. Both cameras feed the same detection, but they suit
     * opposite subjects — a whole leaf carries the damage pattern, the animal itself is usually
     * too small for a phone to resolve — and only the user knows which they are looking at.
     */
    @Test
    fun `a ready instance asks which camera before anything is captured`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        val model = viewModel()

        assertEquals(PestDetectionState.Ready(source = null), model.state.settled())

        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("no source, no shutter", 0, camera.captures)
        assertTrue(detections.uploads.isEmpty())
    }

    /** The phone path never touches the microscope, and vice versa. */
    @Test
    fun `the chosen source is the one that is asked for a frame`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        detections.outcome = DetectionOutcome.Completed(detection())
        val model = viewModel()
        model.state.settled()
        model.chooseSource(CaptureSource.PHONE)
        model.phoneShutter = FakeShutter(byteArrayOf(9, 9))
        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("the microscope was not asked", 0, camera.captures)
        val result = model.state.value as PestDetectionState.Result
        assertTrue(result.frame.contentEquals(byteArrayOf(9, 9)))
    }

    /**
     * A photo the phone cannot bring under the limit fails before the upload.
     *
     * The downscale is the phone path's own problem — a modern sensor produces several
     * megabytes where the microscope produces a few hundred kilobytes — and spending the
     * upload to be told a size the app could measure itself is the thing to avoid.
     */
    @Test
    fun `a phone photo that cannot be shrunk enough is not uploaded`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        val model = viewModel()
        model.state.settled()
        model.chooseSource(CaptureSource.PHONE)
        model.phoneShutter = FakeShutter(null)
        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value is PestDetectionState.Failed)
        assertTrue("nothing was uploaded", detections.uploads.isEmpty())
    }

    /**
     * The shutter's readiness reaches the state, because the button is gated on it.
     *
     * Binding the phone camera is asynchronous, and a press before it finishes does not fail
     * quietly — it ends the flow on an error screen and loses the chosen source.
     */
    @Test
    fun `the phone camera reports when it is ready to fire`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        val model = viewModel()
        model.state.settled()
        model.chooseSource(CaptureSource.PHONE)

        assertEquals(
            PestDetectionState.Ready(source = CaptureSource.PHONE, phoneReady = false),
            model.state.value,
        )

        model.phoneShutter = FakeShutter(byteArrayOf(1))

        assertEquals(
            PestDetectionState.Ready(source = CaptureSource.PHONE, phoneReady = true),
            model.state.value,
        )
    }

    /**
     * The source outlives the viewfinder.
     *
     * A result carries none of its own, and the screen reads it to decide whether USB
     * monitoring is still wanted — so losing it here pops a USB permission dialogue over the
     * photo somebody just took with the phone.
     */
    @Test
    fun `the chosen source survives a result`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        detections.outcome = DetectionOutcome.Completed(detection())
        val model = viewModel()
        model.state.settled()
        model.phoneShutter = FakeShutter(byteArrayOf(1))
        model.chooseSource(CaptureSource.PHONE)

        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value is PestDetectionState.Result)
        assertEquals(CaptureSource.PHONE, model.chosenSource.value)
    }

    /**
     * Re-asking the instance clears the source with it.
     *
     * The screen reads the chosen source to decide whether USB monitoring runs. A source left
     * over from the last attempt keeps it switched off across a retry — so the picker comes
     * back with the microscope option frozen at whatever it last reported, and a device plugged
     * in since is never noticed. The only way out would be leaving the screen.
     */
    @Test
    fun `re-asking the instance returns to the picker with no source held`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        val model = viewModel()
        model.state.settled()
        model.chooseSource(CaptureSource.PHONE)

        model.checkInstance()
        model.state.settled()

        assertEquals(PestDetectionState.Ready(), model.state.value)
        assertNull("nothing may still claim the camera", model.chosenSource.value)
    }

    /** A stale "the phone is bound" must not follow the user to the other camera. */
    @Test
    fun `switching source clears the phone camera's readiness`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        val model = viewModel()
        model.state.settled()
        model.chooseSource(CaptureSource.PHONE)
        model.phoneShutter = FakeShutter(byteArrayOf(1))

        model.chooseSource(CaptureSource.MICROSCOPE)

        assertEquals(
            PestDetectionState.Ready(source = CaptureSource.MICROSCOPE, phoneReady = false),
            model.state.value,
        )
    }

    /** Choosing the phone with no shutter bound yet fails rather than hanging on nothing. */
    @Test
    fun `capturing before the phone camera is bound fails`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        val model = viewModel()
        model.state.settled()

        model.chooseSource(CaptureSource.PHONE)
        model.capture("en")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.state.value is PestDetectionState.Failed)
        assertTrue(detections.uploads.isEmpty())
    }

    /** Going back to the picker mid-flow must not be possible while a frame is uploading. */
    @Test
    fun `the source cannot be changed while an upload is in flight`() = runTest(dispatcher) {
        detections.readiness = DetectionReadiness.Ready
        detections.outcome = DetectionOutcome.Completed(detection())
        val model = viewModel()
        model.state.settled()
        model.chooseSource(CaptureSource.MICROSCOPE)
        model.capture("en")

        model.chooseSource(null)

        assertEquals(
            PestDetectionState.Ready(source = CaptureSource.MICROSCOPE, isUploading = true),
            model.state.value,
        )
        // Both, not just the state: the screen reads this one to decide whether USB monitoring
        // runs, so letting it change while the state refuses pops a USB dialogue over a
        // capture that is already on its way.
        assertEquals(CaptureSource.MICROSCOPE, model.chosenSource.value)
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

        /** The plant each upload was filed against, `null` where it named none. */
        val plantKeys = mutableListOf<String?>()

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
            plantKeys += plantKey
            return outcome
        }
    }

    /** A phone shutter that hands back [jpeg], or `null` for a capture that could not be used. */
    private class FakeShutter(private val jpeg: ByteArray?) : PhoneCameraShutter {
        override suspend fun capture(maxBytes: Int): ByteArray? = jpeg
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
