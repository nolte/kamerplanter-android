package io.github.nolte.kamerplanter.feature.microscope

import android.content.Context
import android.view.View
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicroscopeViewModelTest {

    private lateinit var camera: FakeMicroscopeCamera
    private lateinit var viewModel: MicroscopeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        camera = FakeMicroscopeCamera()
        viewModel = MicroscopeViewModel(camera)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState mirrors camera state`() = runTest {
        viewModel.uiState.test {
            assertEquals(
                MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED),
                awaitItem().camera,
            )
            camera.stateFlow.value = MicroscopeState.Streaming
            assertEquals(MicroscopeState.Streaming, awaitItem().camera)
        }
    }

    /**
     * The shutter ring is offered, not acted on.
     *
     * This ViewModel used to capture on every press for as long as it existed, and a tab's
     * ViewModel outlives its visit — so a press from anywhere else in the app captured into a
     * screen nobody was watching. Harmless while this was the only consumer; not once a
     * plant's diary grew a capture surface of its own, because the camera drops the second
     * capture of one press and whichever collector lost the race got nothing back. The screen
     * now collects this while it is STARTED, so exactly one surface answers a press.
     */
    @Test
    fun `the hardware shutter button is reported for the screen to act on`() = runTest {
        viewModel.shutterPresses.test {
            camera.buttonFlow.emit(MicroscopeButton.Shutter)

            awaitItem()
            // A button this build does not know is not a shutter, and must not fire one.
            camera.buttonFlow.emit(MicroscopeButton.Unknown(index = 4))
            expectNoEvents()
        }
    }

    @Test
    fun `capturing stores the frame it was given`() = runTest {
        val frame = CapturedFrame(jpeg = byteArrayOf(7), width = 1920, height = 1080)
        camera.captureResult = Result.success(frame)

        viewModel.uiState.test {
            awaitItem()
            viewModel.capture()
            assertSame(frame, expectMostRecentItem().lastCapture)
        }
    }

    @Test
    fun `on-screen zoom drives the zoom control in both directions`() {
        viewModel.zoomIn()
        viewModel.zoomOut()

        assertEquals(listOf(10, -10), camera.zoomDeltas)
    }

    @Test
    fun `an unmapped hardware button neither captures nor zooms`() = runTest {
        camera.captureResult = Result.success(CapturedFrame(byteArrayOf(1), width = 1, height = 1))

        viewModel.uiState.test {
            awaitItem()
            camera.buttonFlow.emit(MicroscopeButton.Unknown(index = 9))
            expectNoEvents()
        }
        assertTrue(camera.zoomDeltas.isEmpty())
    }

    @Test
    fun `successful capture stores the frame and clears previous errors`() = runTest {
        val frame = CapturedFrame(jpeg = byteArrayOf(1, 2, 3), width = 1920, height = 1080)
        camera.captureResult = Result.success(frame)

        viewModel.uiState.test {
            awaitItem()
            viewModel.capture()
            val state = expectMostRecentItem()
            assertSame(frame, state.lastCapture)
            assertNull(state.captureError)
        }
    }

    @Test
    fun `failed capture surfaces the error and keeps the last frame`() = runTest {
        val frame = CapturedFrame(jpeg = byteArrayOf(1), width = 1, height = 1)
        camera.captureResult = Result.success(frame)

        viewModel.uiState.test {
            awaitItem()
            viewModel.capture()
            camera.captureResult = Result.failure(IllegalStateException("microscope is not streaming"))
            viewModel.capture()
            val state = expectMostRecentItem()
            assertEquals("microscope is not streaming", state.captureError)
            assertSame(frame, state.lastCapture)
        }
    }

    @Test
    fun `retry restarts the camera so an engine error is not a dead end`() {
        viewModel.retry()

        assertEquals(listOf("stop", "start"), camera.lifecycleCalls)
    }

    @Test
    fun `start and stop delegate to the camera`() {
        viewModel.start()
        viewModel.stop()
        assertTrue(camera.started)
        assertTrue(camera.stopped)
    }
}

private class FakeMicroscopeCamera : MicroscopeCamera {
    val stateFlow =
        MutableStateFlow<MicroscopeState>(MicroscopeState.Unavailable(UnavailableReason.NO_DEVICE_ATTACHED))
    override val state: StateFlow<MicroscopeState> = stateFlow

    val buttonFlow = MutableSharedFlow<MicroscopeButton>(extraBufferCapacity = 4)
    override val buttonPresses: SharedFlow<MicroscopeButton> = buttonFlow

    val zoomDeltas = mutableListOf<Int>()

    var captureResult: Result<CapturedFrame> =
        Result.failure(IllegalStateException("microscope is not streaming"))
    var started = false
    var stopped = false
    val lifecycleCalls = mutableListOf<String>()

    override fun zoomBy(deltaPercent: Int) {
        zoomDeltas += deltaPercent
    }

    override fun createPreviewView(context: Context): View =
        throw UnsupportedOperationException("not used in unit tests")

    override fun start() {
        started = true
        lifecycleCalls += "start"
    }

    override fun stop() {
        stopped = true
        lifecycleCalls += "stop"
    }

    override suspend fun captureFrame(): Result<CapturedFrame> = captureResult
}
